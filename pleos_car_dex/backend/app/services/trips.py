"""주행 리포트: 주차(P단)하면 '이번 드라이브에서 모은 것'을 한 장으로.

- 거리·시간: Pleos Vehicle SDK Odometer (getTripCurrentDrivingDistance / getTripCurrentDrivingTime)
- 수집 결과: 이번 주행 시작 이후의 새 카드·스탬프·점수 (서버 원장 기준)
- 속도·급가속 같은 운전 평가는 하지 않는다 (경쟁·점수는 '어디를 가고 무엇을 모았는지'로만 — 안전).
사용자: 주차 후 10초 만에 오늘 드라이브를 돌아보는 재미 / 현대차: 주행 데이터 기반 차량 앱 경험 재방문.
"""
import json
from datetime import datetime, timezone

from app.schemas import TripReport, TripReportRequest
from app.services.store import JsonStore

MIN_KM = 0.5  # 이보다 짧으면 주행 횟수/누적에 넣지 않음 (주차장 이동 등)


def _aware(dt: datetime) -> datetime:
    return dt if dt.tzinfo else dt.replace(tzinfo=timezone.utc)


class TripService:
    def __init__(self, store: JsonStore, daily, settle_green, home_goal):
        self.store, self.daily, self.settle_green, self.home_goal = store, daily, settle_green, home_goal
        self._path = store.extra_path("trips.json")

    def _load(self) -> list[dict]:
        return json.loads(self._path.read_text(encoding="utf-8")) if self._path.exists() else []

    def report(self, req: TripReportRequest) -> TripReport:
        green_new = self.settle_green()  # 이번 주행 전까지 차가 쉰 날 (오늘 주행 기록 저장 전에 확정)
        since = _aware(req.started_at)
        cards = [e.card for e in self.store.list_entries() if _aware(e.first_seen_at) >= since]
        stamps = []
        from app.services import regions
        for rid, rec in self.store.get_stamps().items():
            if _aware(datetime.fromisoformat(rec["first_visited_at"])) >= since and (r := regions.get_region(rid)):
                stamps.append(f"{r.emoji} {r.name}")
        for h in self.store.get_hidden().values():
            if _aware(h.acquired_at) >= since:
                stamps.append(("🏢 " if h.attraction.kind == "brand" else "✨ ") + h.attraction.title)
        points = sum(e.points for e in self.store.get_events() if _aware(e.at) >= since and e.kind != "green_day")
        mission = self.daily.mission()

        trips = self._load()
        if req.distance_km >= MIN_KM:
            trips.append({"at": datetime.now(timezone.utc).isoformat(), "km": round(req.distance_km, 1),
                          "minutes": req.drive_minutes, "cards": len(cards), "stamps": len(stamps), "points": points})
            self._path.write_text(json.dumps(trips[-200:], ensure_ascii=False, indent=2), encoding="utf-8")
        total_km = round(sum(t["km"] for t in trips), 1)

        km = round(req.distance_km, 1)
        got = []
        if cards:
            got.append(f"새 카드 {len(cards)}장")
        if stamps:
            got.append(f"스탬프 {len(stamps)}개")
        if points:
            got.append(f"{points}점")
        if got:
            headline = f"{km}km 드라이브 · " + " · ".join(got)
            tts = f"수고하셨어요. 이번 {km}킬로미터 동안 {', '.join(got)}을 모았어요."
        else:
            headline = f"{km}km 드라이브 완료"
            tts = f"수고하셨어요. 이번엔 {km}킬로미터를 달렸어요. 다음엔 도감 카드를 모아 볼까요?"
        if mission.found:
            tts += " 오늘의 차도 찾았어요!"
        if green_new:
            tts += f" 그동안 차가 {green_new}일 쉬어서 잎 {green_new}장을 받았어요."
        return TripReport(
            distance_km=km, drive_minutes=req.drive_minutes,
            new_cards=[f"{c.brand} {c.model}" for c in cards], new_legendary=sum(c.rarity == "legendary" for c in cards),
            stamps=stamps, points=points, daily_found=mission.found, streak=mission.streak,
            green_new=green_new, green_days=mission.green_days,
            total_km=total_km, trips=len(trips), headline=headline, tts=tts, next_goal=self.home_goal(),
        )
