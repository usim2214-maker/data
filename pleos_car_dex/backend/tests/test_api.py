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
    assert body["card"]["image_url"] is None  # 개인정보: 크롭 이미지 미저장 (기본)
    assert len(body["detections"]) == 1

    # 재발견 -> 신규 아님, seen_count 증가
    again = client.post("/api/v1/recognize", files={"image": ("ioniq-5.jpg", _jpeg(), "image/jpeg")}).json()
    assert again["is_new_entry"] is False
    col = client.get("/api/v1/collection").json()
    assert col["total"] == 1 and col["entries"][0]["seen_count"] == 2
    assert col["entries"][0]["first_seen_location"] == {"lat": 37.5, "lon": 127.0}


def test_keyword_override_and_delete(client):
    r = client.post(
        "/api/v1/recognize",
        files={"image": ("hyundai-ioniq-5-n.png", _jpeg((0, 0, 0)), "image/jpeg")},
        data={"keywords": "브랜드, 연비"},
    ).json()
    assert r["card"]["rarity"] == "legendary" and r["card"]["id"] == "hyundai-ioniq-5-n"
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

    assert client.put("/api/v1/showroom", json={"card_id": "hyundai-ioniq-5-n"}).status_code == 400  # 미획득 차량
    assert client.put("/api/v1/showroom", json={"background_region_id": "seoul"}).status_code == 400  # 미획득 스탬프

    sr = client.put(
        "/api/v1/showroom",
        json={"card_id": "hyundai-ioniq-5", "background_region_id": "jeju",
              "props": [{"item_id": "region:jeju", "x": 0.2, "y": 0.8}, {"item_id": "prop-flag", "x": 0.8, "y": 0.7}]},
    ).json()
    assert sr["car"]["id"] == "hyundai-ioniq-5" and sr["background"]["name"] == "제주"
    assert sr["background"]["scene"] == "island"
    assert [p["item"]["key"] for p in sr["props"]] == ["🍊", "🚩"]
    assert sr["floor"]["id"] == "floor-concrete" and sr["sign_text"] == "MY GARAGE"
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


def _course(limited_end="20991231"):
    stops = [
        {"content_id": "mock-haeundae", "name": "해운대 해수욕장", "lat": 35.1587, "lon": 129.1604, "kind": "attraction",
         "region_id": "busan", "reason": "명소"},
        {"content_id": "mock-fest-busan", "name": "부산 불꽃 축제 (시연용)", "lat": 35.1532, "lon": 129.1186, "kind": "festival",
         "region_id": "busan", "reason": "축제"},
    ]
    return {"title": "부산 바다 코스", "summary": "s", "stops": stops, "est_drive_hours": 1.0, "expected_points": 60,
            "candidates_source": "mock", "completion_bonus": 60,
            "limited": {"content_id": "mock-fest-busan", "title": "부산 불꽃 축제 (시연용)", "event_end": limited_end}}


def test_course_challenge_and_limited_badge(client):
    ch = client.post("/api/v1/challenge", json=_course()).json()
    assert ch["status"] == "active" and ch["visited"] == []

    r1 = client.post("/api/v1/landmarks/mock-haeundae/visit", json={"lat": 35.1588, "lon": 129.1600}).json()
    assert r1["challenge"]["challenge"]["visited"] == ["mock-haeundae"] and "코스 1/2" in r1["message"]

    r2 = client.post("/api/v1/landmarks/mock-fest-busan/visit", json={"lat": 35.1533, "lon": 129.1187}).json()
    assert r2["challenge"]["just_completed"] is True and r2["challenge"]["bonus_points"] == 60
    assert r2["challenge"]["badge"]["festival_title"] == "부산 불꽃 축제 (시연용)"
    assert client.get("/api/v1/challenge").json()["status"] == "completed"

    syn = {b["name"]: b["points"] for b in client.get("/api/v1/collection").json()["synergy"]["bonuses"]}
    assert syn["코스 완주"] == 60 and syn["축제 한정"] == 50
    assert len(client.get("/api/v1/showroom").json()["badges"]) == 1


def test_rankings_and_region_master(client):
    lb = client.get("/api/v1/rankings/weekly").json()
    assert lb["me"]["points"] == 0 and lb["gap_to_next"] >= 1

    # 부산 마스터(부산갈매기 120점)를 넘어보자: 지역 20 + 히든(명소 25 + 축제 35) + 코스완주 60 + 한정 50 = 190
    client.post("/api/v1/stamps/visit", json={"lat": 35.18, "lon": 129.08})
    client.post("/api/v1/challenge", json=_course())
    client.post("/api/v1/landmarks/mock-haeundae/visit", json={"lat": 35.1588, "lon": 129.1600})
    client.post("/api/v1/landmarks/mock-fest-busan/visit", json={"lat": 35.1533, "lon": 129.1187})

    masters = {m["region"]["id"]: m for m in client.get("/api/v1/rankings/masters").json()["masters"]}
    assert masters["busan"]["is_me"] is True and masters["busan"]["my_points"] == 190
    assert masters["jeju"]["is_me"] is False and masters["jeju"]["gap"] == 161

    busan = client.get("/api/v1/rankings/weekly", params={"region_id": "busan"}).json()
    assert busan["me"]["rank"] == 1

    client.put("/api/v1/showroom", json={"background_region_id": "busan"})
    sr = client.get("/api/v1/showroom").json()
    assert sr["background_mastered"] is True and sr["titles"] == ["부산"]


def test_decor_unlocks(client):
    cat = client.get("/api/v1/showroom/items").json()
    items = {i["id"]: i for i in cat["items"]}
    assert items["floor-concrete"]["unlocked"] and not items["floor-checker"]["unlocked"]
    assert items["floor-checker"]["unlock_hint"] == "코스 완주 1회"
    assert not items["region:busan"]["unlocked"]

    # 잠긴 아이템은 장착 불가
    r = client.put("/api/v1/showroom", json={"floor_id": "floor-checker"})
    assert r.status_code == 400 and "코스 완주 1회" in r.json()["detail"]

    # 부산 코스 완주 -> 체커 바닥·네온 링·체커기·부산 소품 + (부산 마스터) 골드·마스터 간판 해금
    client.post("/api/v1/stamps/visit", json={"lat": 35.18, "lon": 129.08})
    client.post("/api/v1/challenge", json=_course())
    client.post("/api/v1/landmarks/mock-haeundae/visit", json={"lat": 35.1588, "lon": 129.1600})
    client.post("/api/v1/landmarks/mock-fest-busan/visit", json={"lat": 35.1533, "lon": 129.1187})
    items = {i["id"]: i for i in client.get("/api/v1/showroom/items").json()["items"]}
    for k in ["floor-checker", "pedestal-neon", "prop-checker", "region:busan", "light-aurora", "floor-marble", "sign-master"]:
        assert items[k]["unlocked"], k

    sr = client.put("/api/v1/showroom", json={
        "background_region_id": "busan", "floor_id": "floor-marble", "light_id": "light-gold",
        "pedestal_id": "pedestal-gold", "sign_id": "sign-master",
        "props": [{"item_id": "prop-trophy", "x": 0.15, "y": 0.85, "scale": 1.5}],
    }).json()
    assert sr["sign_text"] == "부산 MASTER" and sr["pedestal"]["key"] == "gold" and sr["props"][0]["scale"] == 1.5


def test_home_and_demo_tools(client):
    h = client.get("/api/v1/home").json()
    assert h["collection"] == 0 and h["region_total"] == 17
    assert "첫 카드" in h["next_goal"]  # 도감 중심: 첫 목표는 첫 카드

    seeded = client.post("/api/v1/demo/seed").json()
    assert seeded["cars"] == 4 and set(seeded["region_names"]) == {"서울", "경기", "강원"}
    h = client.get("/api/v1/home").json()
    assert h["collection"] == 4 and h["region_stamps"] == 3 and h["hidden_stamps"] == 2
    assert h["synergy_total"] > 0 and h["weekly_points"] > 0
    assert "현대 차량 1종 더" in h["next_goal"]  # 시드: 현대 2종(아이오닉 5·그랜저) -> 브랜드 컬렉터 목표
    assert client.get("/api/v1/showroom").json()["car"]["id"] == "hyundai-ioniq-5"

    client.post("/api/v1/challenge", json=_course())
    assert "코스 완주까지 2곳" in client.get("/api/v1/home").json()["next_goal"]

    assert client.post("/api/v1/demo/reset").json()["reset"]
    assert client.get("/api/v1/home").json()["collection"] == 0


def test_demo_tools_disabled_by_default(client, monkeypatch):
    from app.config import get_settings

    get_settings().demo_mode = False
    assert client.post("/api/v1/demo/seed").status_code == 403
    get_settings().demo_mode = True


def test_iou_fallback_tracker():
    """YOLO 내장 추적기(lap)가 없을 때 쓰는 대체 추적: 겹치는 박스는 같은 차로 묶는다."""
    from app.config import Settings
    from app.schemas import BoundingBox, Detection
    from app.services.detector import VehicleDetector

    det = VehicleDetector(Settings(detector_mode="mock"))
    car = lambda x: Detection(label="car", confidence=0.9, bbox=BoundingBox(x1=x, y1=100, x2=x + 200, y2=220))
    f1 = det._iou_track([car(100), car(600)], first=True)
    f2 = det._iou_track([car(130), car(630)], first=False)  # 조금씩 이동
    f3 = det._iou_track([car(1000)], first=False)  # 새 차
    assert [t for t, _ in f1] == [0, 1] and [t for t, _ in f2] == [0, 1] and [t for t, _ in f3] == [2]


def test_catalog_and_dex_home(client):
    cat = client.get("/api/v1/collection/catalog").json()
    assert len(cat) == 28 and cat[0]["rarity"] == "legendary"
    assert {c["id"] for c in cat} >= {"hyundai-ioniq-5-n", "hyundai-avante-n", "genesis-g90"}
    h = client.get("/api/v1/home").json()
    assert h["dex_total"] == 28 and h["legendary_total"] == 3 and "첫 카드" in h["next_goal"]

    for name in ["hyundai-avante-n.jpg", "hyundai-avante.jpg", "hyundai-sonata.jpg"]:
        client.post("/api/v1/recognize", files={"image": (name, _jpeg(), "image/jpeg")})
    h = client.get("/api/v1/home").json()
    assert h["dex_owned"] == 3 and h["legendary_owned"] == 1  # 아반떼 N 이 아반떼로 잘못 잡히지 않음
    assert "현대" in h["next_goal"] or "마스터" in h["next_goal"] or "계단" in h["next_goal"]


def test_identity_cache_and_non_catalog_car(client, monkeypatch):
    from app.schemas import VehicleIdentity
    from app.services.pipeline import get_pipeline

    pipe = get_pipeline()
    monkeypatch.setattr(pipe, "_cache_enabled", lambda: True)  # 실제 Claude 판별일 때와 같은 조건
    calls = []
    orig = pipe.identifier.identify
    monkeypatch.setattr(pipe.identifier, "identify", lambda crop, hint="": calls.append(hint) or orig(crop, hint))
    for _ in range(3):  # 같은 차를 연속으로 봄 -> 판별은 1번만
        client.post("/api/v1/recognize", files={"image": ("kia-ev9.jpg", _jpeg(), "image/jpeg")})
    assert len(calls) == 1
    # 다른 모습의 차가 같은 위치에 나타나면 다시 판별 (앞차가 바뀐 경우)
    import io as _io
    from PIL import Image as _Image, ImageDraw as _Draw
    img = _Image.new("RGB", (640, 360), (20, 20, 20)); _Draw.Draw(img).rectangle([0, 0, 320, 360], fill=(250, 250, 250))
    buf = _io.BytesIO(); img.save(buf, format="JPEG")
    client.post("/api/v1/recognize", files={"image": ("kia-ev9.jpg", buf.getvalue(), "image/jpeg")})
    assert len(calls) == 2

    # 정보 목록에 없는 차 -> 카드를 만들지 않음
    pipe._recent.clear()
    monkeypatch.setattr(pipe.identifier, "identify", lambda crop, hint="": VehicleIdentity(
        is_vehicle=True, brand="테슬라", model="모델 Y", body_type="SUV", confidence=0.9))
    r = client.post("/api/v1/recognize", files={"image": ("x.jpg", _jpeg((10, 200, 10)), "image/jpeg")}).json()
    assert r["card"] is None and "도감에 없는 차량" in r["message"]


def test_card_info_comes_from_catalog_list(client):
    fields = client.get("/api/v1/preferences/fields").json()
    assert fields == ["가격", "브랜드", "차급", "마력", "연비", "연료", "출시연도", "구동방식", "승차인원"]
    # 목록에 없는 항목(제로백)은 저장되지 않음
    p = client.put("/api/v1/preferences", json={"keywords": ["차급", "제로백", "승차인원"]}).json()
    assert p["keywords"] == ["차급", "승차인원"]
    card = client.post("/api/v1/recognize", files={"image": ("kia-carnival.jpg", _jpeg(), "image/jpeg")}).json()["card"]
    assert card["one_liner"] == "가족 나들이의 대표 미니밴"
    assert card["facts"] == [{"keyword": "차급", "value": "대형 MPV"}, {"keyword": "승차인원", "value": "7~11인승"}]
