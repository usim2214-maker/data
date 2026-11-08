"""히든 스탬프: TourAPI 명소/축제 반경 안에 들어오면 획득.

앱은 GET /landmarks/nearby 로 주변 명소를 받아 Pleos FusedLocation 근접 알림(addProximityAlert)을 등록하고,
ENTER 이벤트가 오면 POST /landmarks/{id}/visit 로 보고한다. 서버는 거리를 다시 검증한다.
"""
from datetime import date, datetime, timezone

from app.config import Settings
from app.schemas import Attraction, HiddenStamp, LandmarkNearby, LandmarkVisitResponse
from app.services import regions
from app.services.store import JsonStore
from app.services.tour import TourClient, distance_m

HIDDEN_BASE_POINTS = 15


def points_for(a: Attraction) -> int:
    """히든 스탬프 점수: 기본 15 + 지역 가중치의 절반 (지방 명소일수록 높게), 축제는 +10"""
    region = regions.get_region(a.region_id) if a.region_id else None
    return HIDDEN_BASE_POINTS + (region.travel_points // 2 if region else 0) + (10 if a.kind == "festival" else 0)


class LandmarkService:
    def __init__(self, settings: Settings, tour: TourClient, store: JsonStore, challenge=None, competition=None):
        self.settings, self.tour, self.store = settings, tour, store
        self.challenge, self.competition = challenge, competition

    def _candidates(self, lat: float, lon: float, radius_m: int) -> list[Attraction]:
        festivals = [f for f in self.tour.festivals(date.today(), within_days=0) if distance_m(lat, lon, f.lat, f.lon) <= radius_m]
        return self.tour.nearby(lat, lon, radius_m) + festivals

    def nearby(self, lat: float, lon: float, radius_m: int) -> list[LandmarkNearby]:
        collected = self.store.get_hidden()
        out = [
            LandmarkNearby(
                attraction=a,
                distance_m=int(distance_m(lat, lon, a.lat, a.lon)),
                collected=a.content_id in collected,
                points=points_for(a),
            )
            for a in self._candidates(lat, lon, radius_m)
        ]
        return sorted(out, key=lambda x: x.distance_m)

    def visit(self, content_id: str, lat: float, lon: float) -> LandmarkVisitResponse:
        limit = self.settings.landmark_radius_m
        target = next((a for a in self._candidates(lat, lon, max(limit * 4, 2_000)) if a.content_id == content_id), None)
        if target is None and self.challenge and (ch := self.challenge.current()):
            # 챌린지 코스 장소는 명소 목록 갱신 시점과 무관하게 인정
            stop = next((s for s in ch.course.stops if s.content_id == content_id), None)
            if stop:
                target = Attraction(content_id=stop.content_id, title=stop.name, lat=stop.lat, lon=stop.lon,
                                    kind=stop.kind, region_id=stop.region_id)
        if target is None:
            return LandmarkVisitResponse(acquired=False, message="주변에서 해당 명소를 찾지 못했어요.")
        dist = distance_m(lat, lon, target.lat, target.lon)
        if dist > limit:
            return LandmarkVisitResponse(acquired=False, message=f"{target.title}까지 {int(dist)}m 남았어요.")
        stamp, is_new = self.store.add_hidden(
            HiddenStamp(attraction=target, acquired_at=datetime.now(timezone.utc), points=points_for(target))
        )
        msg = f"✨ 히든 스탬프 '{target.title}' 획득! +{stamp.points}" if is_new else f"{target.title} — 이미 획득한 히든 스탬프"
        if is_new and self.competition:
            self.competition.record(stamp.points, target.region_id, "hidden_stamp", target.title)

        progress = self.challenge.on_visit(target.content_id) if self.challenge else None
        if progress:
            ch = progress.challenge
            if progress.just_completed:
                msg = f"🏁 코스 완주! +{progress.bonus_points}"
                if progress.badge:
                    msg += f" · 🎖️ 한정 배지 '{progress.badge.title}'"
            else:
                msg += f" · 코스 {len(ch.visited)}/{len(ch.course.stops)}"
        return LandmarkVisitResponse(acquired=True, is_new=is_new, stamp=stamp, message=msg, challenge=progress)
