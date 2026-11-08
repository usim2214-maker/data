"""AI 드라이브 코스 추천.

1) 후보 수집 (서버): 아직 없는 지역 스탬프 근처 명소 + 기간 내 축제 + 주변 미획득 명소 (TourAPI)
2) 코스 선택 (LLM): 후보 ID 중에서 2~4곳을 골라 순서·이유 작성 → 구조화 출력
3) 검증/계산 (서버): 후보에 없는 ID 제거, 이동 시간·획득 예상 점수는 서버가 계산 (LLM 수치 환각 방지)
LLM 미사용/실패 시 규칙 기반(그리디)으로 코스를 만든다.
"""
import json
import logging
from datetime import date, datetime

import anthropic

from app.config import Settings
from app.schemas import Attraction, CourseRequest, CourseStop, DriveCourse, LimitedEvent, LLMCoursePick
from app.services.challenge import completion_bonus
from app.services import llm, regions
from app.services.landmarks import points_for
from app.services.store import JsonStore
from app.services.tour import TourClient, distance_m

log = logging.getLogger(__name__)

ROAD_FACTOR = 1.35  # 직선거리 -> 도로거리 보정
AVG_KMH = 80.0  # 고속도로 위주 평균
MAX_STOPS = 4
LOCAL_KM = 15  # 출발지에서 이보다 가까운 곳은 '여행 목적지'로 보지 않음 (경유지로만)

SYSTEM_PROMPT = (
    "당신은 국내 드라이브 여행 코스를 짜는 여행 큐레이터입니다. 목표는 운전자가 아직 가 보지 않은 지역과 "
    "지역 명소·축제를 방문하도록 돕는 것입니다(지역 관광 활성화).\n"
    "반드시 주어진 후보 목록의 id 만 사용해 2~4곳을 방문 순서대로 고르세요. 왕복 이동 시간이 희망 시간을 넘지 않도록 "
    "출발지에서의 거리(dist_km)를 고려하고, 서로 가까운 곳끼리 묶으세요. 새 지역 스탬프(new_region)·진행 중인 축제를 우선합니다.\n"
    "title 은 15자 내외의 코스 이름, summary 는 2문장 이내, reasons 는 stop_ids 와 같은 순서·개수로 각 25자 이내로 씁니다."
)


def _road_hours(points: list[tuple[float, float]]) -> float:
    km = sum(distance_m(*points[i], *points[i + 1]) for i in range(len(points) - 1)) / 1000 * ROAD_FACTOR
    return round(km / AVG_KMH, 1)


class CourseService:
    def __init__(self, settings: Settings, tour: TourClient, store: JsonStore):
        self.settings, self.tour, self.store = settings, tour, store

    def recommend(self, req: CourseRequest) -> DriveCourse:
        on = datetime.strptime(req.date, "%Y%m%d").date() if req.date else date.today()
        cands = self._candidates(req, on)
        if not cands:
            return DriveCourse(title="추천할 코스가 없어요", summary="희망 시간을 늘려 보세요.", stops=[],
                               est_drive_hours=0, expected_points=0, candidates_source=self.tour.source)

        pick = self._llm_pick(req, cands, on) if self.settings.llm_enabled else None
        if pick is None:
            pick = self._greedy_pick(req, cands)

        by_id = {c.content_id: c for c in cands}
        stops = []
        for cid, reason in zip(pick.stop_ids, pick.reasons + [""] * len(pick.stop_ids)):
            if (c := by_id.get(cid)) and c.content_id not in {s.content_id for s in stops}:
                stops.append(c.model_copy(update={"reason": reason or c.reason}))
        stops = stops[:MAX_STOPS]

        def route_of(ss):
            return [(req.lat, req.lon)] + [(x.lat, x.lon) for x in ss] + [(req.lat, req.lon)]

        # 희망 시간을 넘으면 마지막 경유지부터 줄인다 (LLM 이 거리 계산을 틀려도 안전)
        while len(stops) > 1 and _road_hours(route_of(stops)) > req.max_hours:
            stops.pop()
        route = route_of(stops)
        new_regions = {s.region_id for s in stops if s.new_region_stamp and s.region_id}
        expected = sum(r.travel_points for rid in new_regions if (r := regions.get_region(rid)))
        expected += sum(s.points for s in stops if s.new_hidden_stamp)
        fest = next((s for s in stops if s.kind == "festival" and s.event), None)
        limited = LimitedEvent(content_id=fest.content_id, title=fest.name, event_end=fest.event.split("~")[1]) if fest else None
        return DriveCourse(
            title=pick.title,
            summary=pick.summary,
            stops=[CourseStop(**s.model_dump(exclude={"points", "dist_km", "event"})) for s in stops],
            est_drive_hours=_road_hours(route),
            expected_points=expected,
            candidates_source=self.tour.source,
            limited=limited,
            completion_bonus=completion_bonus(expected),
        )

    # ---------------- 후보 ----------------
    def _candidates(self, req: CourseRequest, on: date) -> list["_Cand"]:
        reach_m = req.max_hours / 2 * AVG_KMH / ROAD_FACTOR * 1000  # 편도 직선 도달 거리
        visited_regions = set(self.store.get_stamps())
        if here := regions.locate(req.lat, req.lon):
            visited_regions.add(here.id)  # 현재 지역 스탬프는 위치 보고로 이미 받는다고 본다
        hidden = set(self.store.get_hidden())

        pool: dict[str, Attraction] = {}
        for f in self.tour.festivals(on):
            pool[f.content_id] = f
        unvisited = sorted(
            (r for r in regions.all_regions() if r.id not in visited_regions),
            key=lambda r: distance_m(req.lat, req.lon, r.lat, r.lon),
        )
        for r in [r for r in unvisited if distance_m(req.lat, req.lon, r.lat, r.lon) <= reach_m][:6]:  # TourAPI 호출 수 제한
            for a in self.tour.in_region(r):
                pool.setdefault(a.content_id, a)
        for a in self.tour.nearby(req.lat, req.lon, limit=4):
            pool.setdefault(a.content_id, a)

        out = []
        for a in pool.values():
            d = distance_m(req.lat, req.lon, a.lat, a.lon)
            if d > reach_m or (a.content_id in hidden and a.region_id in visited_regions):
                continue  # 얻을 스탬프가 없는 곳은 제외
            new_region = bool(a.region_id) and a.region_id not in visited_regions
            out.append(_Cand(
                content_id=a.content_id, name=a.title, lat=a.lat, lon=a.lon, kind=a.kind, region_id=a.region_id,
                reason=("진행 중인 축제" if a.kind == "festival" else "지역 명소"),
                new_region_stamp=new_region, new_hidden_stamp=a.content_id not in hidden,
                points=points_for(a), dist_km=round(d / 1000, 1),
                event=f"{a.event_start}~{a.event_end}" if a.kind == "festival" else "",
            ))
        return sorted(out, key=lambda c: c.dist_km)[:25]

    # ---------------- 선택 ----------------
    def _llm_pick(self, req: CourseRequest, cands: list["_Cand"], on: date) -> LLMCoursePick | None:
        payload = [
            {"id": c.content_id, "name": c.name, "kind": c.kind, "region": c.region_id, "dist_km": c.dist_km,
             "new_region": c.new_region_stamp, "event": c.event}
            for c in cands
        ]
        content = (
            f"날짜: {on.isoformat()}\n희망 왕복 시간: {req.max_hours}시간\n취향: {req.note or '없음'}\n"
            f"후보:\n{json.dumps(payload, ensure_ascii=False)}"
        )
        try:
            pick = llm.parse(output_format=LLMCoursePick, system=SYSTEM_PROMPT, content=content)
        except (anthropic.APIConnectionError, anthropic.RateLimitError, anthropic.APIStatusError) as e:
            log.warning("course LLM 실패 -> 규칙 기반: %s", e)
            return None
        if pick is None or not any(sid in {c.content_id for c in cands} for sid in pick.stop_ids):
            return None
        return pick

    @staticmethod
    def _greedy_pick(req: CourseRequest, cands: list["_Cand"]) -> LLMCoursePick:
        reach_km = req.max_hours / 2 * AVG_KMH / ROAD_FACTOR
        """축제·새 지역 우선 점수로 고르되, 첫 장소에서 가까운 곳을 이어 붙인다."""
        def score(c):
            region = regions.get_region(c.region_id) if c.region_id else None
            return (
                (3 if c.kind == "festival" else 0)
                + (2 if c.new_region_stamp else 0)
                + (1 if c.new_hidden_stamp else 0)
                + (region.travel_points / 20 if region else 0)  # 지방일수록 가산
                + min(c.dist_km / reach_km, 1.0) * 1.5  # 희망 시간을 활용하는 '여행다운' 거리 선호
            )

        trips = [c for c in cands if c.dist_km >= LOCAL_KM] or cands
        first = max(trips, key=score)
        rest = sorted((c for c in cands if c is not first), key=lambda c: distance_m(first.lat, first.lon, c.lat, c.lon))
        chosen = [first] + [c for c in rest if distance_m(first.lat, first.lon, c.lat, c.lon) <= 40_000][:2]
        chosen.sort(key=lambda c: c.dist_km)
        region = regions.get_region(first.region_id) if first.region_id else None
        title = f"{region.name if region else ''} {first.name} 드라이브".strip()
        return LLMCoursePick(
            title=title,
            summary=f"{len(chosen)}곳을 잇는 코스예요. 새 스탬프를 모으며 지역 명소를 둘러보세요.",
            stop_ids=[c.content_id for c in chosen],
            reasons=[c.reason + (" · 새 지역 스탬프" if c.new_region_stamp else "") for c in chosen],
        )


class _Cand(CourseStop):
    points: int = 0
    dist_km: float = 0
    event: str = ""
