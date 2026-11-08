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
