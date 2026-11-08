import io

from PIL import Image


def _jpeg(color=(200, 30, 30)) -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (640, 360), color).save(buf, format="JPEG")
    return buf.getvalue()


def test_health(client):
    assert client.get("/health").json()["status"] == "ok"


def test_recognize_registers_and_uses_keywords(client):
    client.put("/api/v1/preferences", json={"keywords": ["가격", "마력"], "language": "ko"})
    r = client.post(
        "/api/v1/recognize",
        files={"image": ("hyundai-ioniq-5.jpg", _jpeg(), "image/jpeg")},
        data={"lat": "37.5", "lon": "127.0"},
    )
    body = r.json()
    assert r.status_code == 200, body
    assert body["card"]["id"] == "hyundai-ioniq-5"
    assert [f["keyword"] for f in body["card"]["facts"]] == ["가격", "마력"]
    assert body["is_new_entry"] is True
    assert client.get(body["card"]["image_url"]).status_code == 200

    # 재발견 -> 신규 아님, seen_count 증가
    again = client.post("/api/v1/recognize", files={"image": ("ioniq-5.jpg", _jpeg(), "image/jpeg")}).json()
    assert again["is_new_entry"] is False
    col = client.get("/api/v1/collection").json()
    assert col["total"] == 1 and col["entries"][0]["seen_count"] == 2
    assert col["entries"][0]["first_seen_location"] == {"lat": 37.5, "lon": 127.0}


def test_keyword_override_and_delete(client):
    r = client.post(
        "/api/v1/recognize",
        files={"image": ("porsche-911.png", _jpeg((0, 0, 0)), "image/jpeg")},
        data={"keywords": "브랜드, 연비"},
    ).json()
    assert r["card"]["rarity"] == "legendary"
    assert [f["keyword"] for f in r["card"]["facts"]] == ["브랜드", "연비"]
    assert client.delete(f"/api/v1/collection/{r['card']['id']}").status_code == 204
    assert client.get("/api/v1/collection").json()["total"] == 0


def test_bad_image(client):
    r = client.post("/api/v1/recognize", files={"image": ("x.jpg", b"not an image", "image/jpeg")})
    assert r.status_code == 400


def _mp4(path, frames=20, color=(30, 30, 200)):
    import cv2
    import numpy as np

    w = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), 10, (320, 240))
    for _ in range(frames):
        w.write(np.full((240, 320, 3), color, dtype=np.uint8))
    w.release()
    return path.read_bytes()


def test_video_recognition(client, tmp_path):
    data = _mp4(tmp_path / "kia-ev9.mp4")
    r = client.post("/api/v1/recognize/video", files={"video": ("kia-ev9.mp4", data, "video/mp4")})
    body = r.json()
    assert r.status_code == 200, body
    assert body["frames_analyzed"] == 4  # 10fps 영상, 2fps 샘플링, 20프레임
    assert [v["card"]["id"] for v in body["vehicles"]] == ["kia-ev9"]


def test_synergy_and_summon(client):
    for name in ["hyundai-ioniq-5.jpg", "kia-ev9.jpg", "genesis-g80.jpg", "hyundai-grandeur.jpg"]:
        client.post("/api/v1/recognize", files={"image": (name, _jpeg(), "image/jpeg")})
    syn = client.get("/api/v1/collection").json()["synergy"]
    names = {b["name"] for b in syn["bonuses"]}
    assert syn["base"] == 10 + 30 + 30 + 10
    assert "그룹 트리오" in names and "다양성" in names

    assert client.post("/api/v1/garage/summon", json={"card_id": "nope"}).status_code == 404
    g = client.post("/api/v1/garage/summon", json={"card_id": "hyundai-ioniq-5"}).json()
    assert g["active"]["id"] == "hyundai-ioniq-5"
    syn2 = client.get("/api/v1/collection").json()["synergy"]
    assert any(b["name"] == "소환 공명" for b in syn2["bonuses"])  # 현대 2장
    client.delete("/api/v1/collection/hyundai-ioniq-5")
    assert client.get("/api/v1/garage").json()["active"] is None
