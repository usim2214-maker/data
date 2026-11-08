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


def test_synergy(client):
    for name in ["hyundai-ioniq-5.jpg", "kia-ev9.jpg", "genesis-g80.jpg", "hyundai-grandeur.jpg"]:
        client.post("/api/v1/recognize", files={"image": (name, _jpeg(), "image/jpeg")})
    syn = client.get("/api/v1/collection").json()["synergy"]
    names = {b["name"] for b in syn["bonuses"]}
    assert syn["base"] == 10 + 30 + 30 + 10
    assert "그룹 트리오" in names and "다양성" in names


def test_stamps(client):
    book = client.get("/api/v1/stamps").json()
    assert book["acquired"] == 0 and book["total"] == 17

    r = client.post("/api/v1/stamps/visit", json={"lat": 35.16, "lon": 129.16}).json()  # 해운대
    assert r["is_new"] is True and r["stamp"]["region"]["id"] == "busan"
    assert client.post("/api/v1/stamps/visit", json={"lat": 35.17, "lon": 129.07}).json()["is_new"] is False
    assert client.post("/api/v1/stamps/visit", json={"lat": 40.0, "lon": 140.0}).json()["stamp"] is None

    # 인식 요청에 위치가 오면 스탬프 자동 기록 (서울)
    client.post("/api/v1/recognize", files={"image": ("kia-ev9.jpg", _jpeg(), "image/jpeg")}, data={"lat": "37.57", "lon": "126.98"})
    assert client.get("/api/v1/stamps").json()["acquired"] == 2


def test_showroom(client):
    client.post("/api/v1/recognize", files={"image": ("hyundai-ioniq-5.jpg", _jpeg(), "image/jpeg")})
    client.post("/api/v1/recognize", files={"image": ("hyundai-grandeur.jpg", _jpeg(), "image/jpeg")})
    client.post("/api/v1/stamps/visit", json={"lat": 33.5, "lon": 126.53})  # 제주

    assert client.put("/api/v1/showroom", json={"card_id": "porsche-911"}).status_code == 400  # 미획득 차량
    assert client.put("/api/v1/showroom", json={"background_region_id": "seoul"}).status_code == 400  # 미획득 스탬프

    sr = client.put(
        "/api/v1/showroom",
        json={"card_id": "hyundai-ioniq-5", "background_region_id": "jeju", "sticker_region_ids": ["jeju", "jeju"]},
    ).json()
    assert sr["car"]["id"] == "hyundai-ioniq-5" and sr["background"]["name"] == "제주"
    assert [s["id"] for s in sr["stickers"]] == ["jeju"]
    assert client.get("/api/v1/showroom").json()["background"]["id"] == "jeju"

    syn = client.get("/api/v1/collection").json()["synergy"]
    assert any(b["name"] == "대표차 공명" for b in syn["bonuses"])  # 현대 2장

    client.delete("/api/v1/collection/hyundai-ioniq-5")
    assert client.get("/api/v1/showroom").json()["car"] is None


def test_travel_score(client):
    # 부산에서 차량 발견 (스탬프 자동 획득 + 여행지 발견 보너스)
    client.post("/api/v1/recognize", files={"image": ("kia-ev9.jpg", _jpeg(), "image/jpeg")}, data={"lat": "35.18", "lon": "129.08"})
    for lat, lon in [(37.57, 126.98), (37.46, 126.71), (37.28, 127.01)]:  # 서울·인천·경기 -> 수도권 완주
        client.post("/api/v1/stamps/visit", json={"lat": lat, "lon": lon})
    syn = client.get("/api/v1/collection").json()["synergy"]
    names = {b["name"]: b["points"] for b in syn["bonuses"]}
    assert syn["travel"] == 20 + 10 + 10 + 10
    assert names["수도권 완주"] == 100
    assert names["여행지 발견"] == 10
    assert syn["total"] == syn["base"] + syn["travel"] + sum(names.values())


def test_hidden_landmark_stamp(client):
    near = client.get("/api/v1/landmarks/nearby", params={"lat": 35.16, "lon": 129.15, "radius_m": 5000}).json()
    assert near[0]["attraction"]["content_id"] == "mock-haeundae" and near[0]["collected"] is False

    far = client.post("/api/v1/landmarks/mock-haeundae/visit", json={"lat": 35.165, "lon": 129.15}).json()
    assert far["acquired"] is False  # 약 1km 떨어짐 -> 반경 500m 밖

    ok = client.post("/api/v1/landmarks/mock-haeundae/visit", json={"lat": 35.1588, "lon": 129.1600}).json()
    assert ok["acquired"] and ok["is_new"] and ok["stamp"]["points"] == 15 + 20 // 2
    again = client.post("/api/v1/landmarks/mock-haeundae/visit", json={"lat": 35.1588, "lon": 129.1600}).json()
    assert again["is_new"] is False

    assert client.get("/api/v1/stamps").json()["hidden"][0]["attraction"]["title"] == "해운대 해수욕장"
    syn = client.get("/api/v1/collection").json()["synergy"]
    assert any(b["name"] == "히든 스탬프" and b["points"] == 25 for b in syn["bonuses"])


def test_course_recommend_rule_based(client):
    # 서울 출발, 6시간, 11월 초 -> 시연용 축제/미방문 지역 명소로 코스 구성
    r = client.post("/api/v1/courses/recommend", json={"lat": 37.5665, "lon": 126.9780, "max_hours": 6, "date": "20261108"})
    body = r.json()
    assert r.status_code == 200, body
    assert 1 <= len(body["stops"]) <= 4
    assert body["candidates_source"] == "mock"
    assert body["est_drive_hours"] > 0 and body["expected_points"] > 0
    assert all(s["content_id"].startswith("mock-") for s in body["stops"])
