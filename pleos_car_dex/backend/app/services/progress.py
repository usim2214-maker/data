"""진행도 집계 (시너지 점수, 아이템 해금 판정에 공통 사용)"""
from app.schemas import SynergyScore
from app.services import regions, synergy
from app.services.competition import points_by_kind
from app.services.store import JsonStore


def synergy_for(store: JsonStore) -> SynergyScore:
    entries = store.list_entries()
    featured_id = store.get_showroom().card_id
    featured = next((e.card for e in entries if e.card.id == featured_id), None)
    kinds = points_by_kind(store, {"course_complete", "limited_badge"})
    return synergy.compute(
        entries,
        featured_brand=featured.brand if featured else None,
        stamps=store.get_stamps(),
        regions=regions.all_regions(),
        locate=regions.locate,
        hidden_points=[h.points for h in store.get_hidden().values()],
        course_points=kinds["course_complete"],
        badge_points=kinds["limited_badge"],
    )
