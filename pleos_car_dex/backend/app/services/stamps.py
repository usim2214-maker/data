"""지역 스탬프: 방문 기록 -> 스탬프북 / 쇼룸 꾸미기 재료."""
from datetime import datetime

from app.schemas import Stamp, StampBook, VisitResponse
from app.services import regions
from app.services.store import JsonStore


def _stamp(region, rec: dict | None) -> Stamp:
    if rec is None:
        return Stamp(region=region, acquired=False)
    return Stamp(
        region=region,
        acquired=True,
        first_visited_at=datetime.fromisoformat(rec["first_visited_at"]),
        visit_count=rec["visit_count"],
    )


def record_visit(store: JsonStore, lat: float, lon: float, competition=None) -> VisitResponse:
    region = regions.locate(lat, lon)
    if region is None:
        return VisitResponse(message="스탬프 지역 밖이에요.")
    rec, is_new, revisit = store.visit(region.id)
    from app.services.deps import get_daily
    get_daily().mark_active()  # 방문도 출석
    if competition is not None:  # 랭킹·지역 마스터용 원장 기록 (synergy 의 재방문 상한과 동일하게)
        from app.services.synergy import REVISIT_CAP, REVISIT_POINTS

        if is_new:
            competition.record(region.travel_points, region.id, "region_stamp", region.name)
        elif revisit and (rec["visit_count"] - 1) * REVISIT_POINTS <= REVISIT_CAP:
            competition.record(REVISIT_POINTS, region.id, "revisit", region.name)
    return VisitResponse(
        stamp=_stamp(region, rec),
        is_new=is_new,
        message=f"{region.emoji} {region.name} 스탬프 획득!" if is_new else f"{region.name} 방문 중",
    )


def stamp_book(store: JsonStore) -> StampBook:
    recs = store.get_stamps()
    stamps = [_stamp(r, recs.get(r.id)) for r in regions.all_regions()]
    hidden = sorted(store.get_hidden().values(), key=lambda h: h.acquired_at, reverse=True)
    return StampBook(acquired=sum(s.acquired for s in stamps), total=len(stamps), stamps=stamps, hidden=hidden)


def acquired_ids(store: JsonStore) -> set[str]:
    return set(store.get_stamps())
