"""이번 주의 차 미션 + 연속 기록.

- 매주(KST, 월~일) 도감 28종 중 '이번 주의 차' 하나 (전설 제외, 주 번호로 결정 → 모든 사용자가 같은 차)
  매일 찾으러 나갈 필요 없이 일주일 동안 평소 다니는 길에서 만나면 된다 (불필요한 운전 지양)
- 격주로 전기차 주간: 짝수 주는 전기차 중에서 골라 전동화 라인업 노출
- 이번 주의 차를 카드로 만들면 보너스 (희귀 +50, 일반 +30) — 주 1번
- 연속 기록 = 앱을 연 날 + **차가 쉰 날(그린 데이)**. 운전을 안 해도 연속이 끊기지 않는다 (매일 운전하게 만드는 출석은 지양)
- 그린 데이: 주행 기록(주차 리포트 0.5km 이상)이 없는 지난 날 → 잎 🌿 1장 = +GREEN_POINTS, 친환경 쇼룸 아이템 해금
현대차 관점: 매일 다른 모델에 시선이 가게 하는 라인업 노출, 사용자는 매일 앱을 켤 이유.
"""
import hashlib
import json
from datetime import date, datetime, timedelta, timezone

from app.schemas import CatalogVehicle, DailyMission
from app.services.catalog import load_catalog
from app.services.competition import KST
from app.services.store import JsonStore

BONUS = {"rare": 50, "common": 30}
GREEN_POINTS = 10  # 차가 쉰 날 1일당 (안 타도 꾸준히 쌓임 — 일주일 쉬면 주간 미션보다 큼)
GREEN_LOOKBACK = 14  # 최근 며칠까지 소급 인정


def today() -> date:
    return datetime.now(timezone.utc).astimezone(KST).date()


def week_of(d: date) -> str:
    iso = d.isocalendar()
    return f"{iso.year}-W{iso.week:02d}"


def target_for(d: date) -> dict:
    """그 주의 차. 짝수 주 = 전기차 주간"""
    pool = [v for v in load_catalog() if v["rarity"] != "legendary"]
    if d.isocalendar().week % 2 == 0:
        pool = [v for v in pool if v["facts"].get("연료") == "전기"] or pool
    pool.sort(key=lambda v: v["id"])
    idx = int(hashlib.sha256(week_of(d).encode()).hexdigest(), 16) % len(pool)
    return pool[idx]


class DailyService:
    def __init__(self, store: JsonStore):
        self.store = store
        self._path = store.extra_path("daily.json")

    def _load(self) -> dict:
        d = json.loads(self._path.read_text(encoding="utf-8")) if self._path.exists() else {}
        return {"found": [], "active": [], "green": [], **d}

    def _save(self, d: dict) -> None:
        self._path.write_text(json.dumps(d, ensure_ascii=False, indent=2), encoding="utf-8")

    def mark_active(self) -> None:
        d = self._load()
        t = today().isoformat()
        if t not in d["active"]:
            d["active"].append(t)
            self._save(d)

    def drive_days(self) -> set[date]:
        """주행한 날 (주차 리포트 기준, KST)"""
        p = self.store.extra_path("trips.json")
        trips = json.loads(p.read_text(encoding="utf-8")) if p.exists() else []
        return {datetime.fromisoformat(t["at"]).astimezone(KST).date() for t in trips if t.get("km", 0) >= 0.5}

    def settle_green(self) -> list[str]:
        """어제까지 중 주행 기록이 없는 날을 그린 데이로 확정. 새로 확정된 날짜 목록 반환.
        시작점은 앱을 처음 쓴 날 (그 전 날은 소급하지 않음)."""
        d = self._load()
        known = [date.fromisoformat(x) for x in d["active"]] + sorted(self.drive_days())
        if not known:
            return []
        t = today()
        start = max(min(known), t - timedelta(days=GREEN_LOOKBACK))
        driven, green = self.drive_days(), set(d["green"])
        new = []
        x = start
        while x < t:
            if x not in driven and x.isoformat() not in green:
                new.append(x.isoformat())
            x += timedelta(days=1)
        if new:
            d["green"] = sorted(green | set(new))
            self._save(d)
        return new

    def green_days(self) -> int:
        return len(self._load()["green"])

    def streak(self) -> tuple[int, int]:
        d = self._load()
        days = sorted({date.fromisoformat(x) for x in d["active"] + d["green"]})
        best = cur = 0
        prev = None
        for x in days:
            cur = cur + 1 if prev and x - prev == timedelta(days=1) else 1
            best, prev = max(best, cur), x
        # 오늘이나 어제까지 이어져 있어야 현재 연속
        current = cur if days and today() - days[-1] <= timedelta(days=1) else 0
        return current, best

    def on_card(self, card_id: str) -> int:
        """카드를 만들 때 호출. 이번 주의 차면 보너스 점수를 돌려준다 (주 1번)"""
        self.mark_active()
        t = today()
        target = target_for(t)
        d = self._load()
        if card_id != target["id"] or week_of(t) in d["found"]:
            return 0
        d["found"].append(week_of(t))
        self._save(d)
        return BONUS.get(target["rarity"], 20)

    def mission(self) -> DailyMission:
        t = today()
        v = target_for(t)
        cur, best = self.streak()
        return DailyMission(
            date=t.isoformat(),
            week=week_of(t),
            days_left=7 - t.isoweekday(),
            ev_week=v["facts"].get("연료") == "전기" and t.isocalendar().week % 2 == 0,
            target=CatalogVehicle(**{k: v[k] for k in ("id", "brand", "model", "body_type", "rarity")}),
            found=week_of(t) in self._load()["found"],
            bonus=BONUS.get(v["rarity"], 20),
            streak=cur,
            best_streak=best,
            green_days=self.green_days(),
        )
