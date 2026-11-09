"""시연 도구: 빈 화면 대신 '어느 정도 여행한 사용자' 상태로 채우기 / 초기화 (DEMO_MODE 에서만)."""
from datetime import datetime, timezone

from app.schemas import VehicleCard, VehicleIdentity
from app.services import regions, stamps
from app.services.catalog import load_catalog
from app.services.competition import CompetitionService
from app.services.landmarks import LandmarkService
from app.services.store import JsonStore
from app.services.summarizer import CardSummarizer

SEED_CARS = ["hyundai-ioniq-5", "kia-ev9", "genesis-g80", "hyundai-grandeur"]
SEED_VISITS = [(37.5665, 126.9780), (37.2750, 127.0094), (37.8228, 128.1555)]  # 서울·경기·강원
SEED_LANDMARKS = [("mock-gyeongbokgung", 37.5796, 126.9770), ("mock-nami", 37.7911, 127.5256)]


def seed(store: JsonStore, competition: CompetitionService, landmarks: LandmarkService, summarizer: CardSummarizer) -> dict:
    keywords = store.get_preferences().keywords
    by_id = {v["id"]: v for v in load_catalog()}
    for cid in SEED_CARS:
        v = by_id[cid]
        ident = VehicleIdentity(is_vehicle=True, brand=v["brand"], model=v["model"], body_type=v["body_type"], confidence=1.0)
        summary = summarizer.summarize(ident, keywords)  # 차량 정보 목록에서 고른 항목
        card = VehicleCard(id=cid, brand=v["brand"], model=v["model"], body_type=v["body_type"],
                           one_liner=summary.one_liner, facts=summary.facts, rarity=v["rarity"])
        store.register(card, None)
    for lat, lon in SEED_VISITS:
        stamps.record_visit(store, lat, lon, competition)
    for cid, lat, lon in SEED_LANDMARKS:
        landmarks.visit(cid, lat, lon)
    showroom = store.get_showroom()
    showroom.card_id = SEED_CARS[0]
    showroom.background_region_id = "gangwon"
    store.save_showroom(showroom)
    return {"seeded_at": datetime.now(timezone.utc).isoformat(), "cars": len(SEED_CARS), "regions": len(SEED_VISITS),
            "hidden": len(SEED_LANDMARKS), "region_names": [r.name for r in regions.all_regions() if r.id in store.get_stamps()]}
