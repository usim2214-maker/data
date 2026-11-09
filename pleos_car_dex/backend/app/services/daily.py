"""오늘의 차 미션 + 연속 출석.

- 매일(KST) 도감 28종 중 '오늘의 차' 하나 (전설 제외, 날짜로 결정 → 모든 사용자가 같은 차)
- 오늘의 차를 카드로 만들면 보너스 (희귀 +30, 일반 +20) — 하루 1번
- 수집·방문한 날이 이어지면 연속 출석
현대차 관점: 매일 다른 모델에 시선이 가게 하는 라인업 노출, 사용자는 매일 앱을 켤 이유.
"""
import hashlib
import json
from datetime import date, datetime, timedelta, timezone

from app.schemas import CatalogVehicle, DailyMission
from app.services.catalog import load_catalog
from app.services.competition import KST
from app.services.store import JsonStore

BONUS = {"rare": 30, "common": 20}


def today() -> date:
    return datetime.now(timezone.utc).astimezone(KST).date()


def target_for(d: date) -> dict:
    pool = sorted((v for v in load_catalog() if v["rarity"] != "legendary"), key=lambda v: v["id"])
    idx = int(hashlib.sha256(d.isoformat().encode()).hexdigest(), 16) % len(pool)
    return pool[idx]


class DailyService:
    def __init__(self, store: JsonStore):
        self.store = store
        self._path = store._collection_path.with_name("daily.json")

    def _load(self) -> dict:
        return json.loads(self._path.read_text(encoding="utf-8")) if self._path.exists() else {"found": [], "active": []}

    def _save(self, d: dict) -> None:
        self._path.write_text(json.dumps(d, ensure_ascii=False, indent=2), encoding="utf-8")

    def mark_active(self) -> None:
        d = self._load()
        t = today().isoformat()
        if t not in d["active"]:
            d["active"].append(t)
            self._save(d)

    def streak(self) -> tuple[int, int]:
        days = sorted({date.fromisoformat(x) for x in self._load()["active"]})
        best = cur = 0
        prev = None
        for x in days:
            cur = cur + 1 if prev and x - prev == timedelta(days=1) else 1
            best, prev = max(best, cur), x
        # 오늘이나 어제까지 이어져 있어야 현재 연속
        current = cur if days and today() - days[-1] <= timedelta(days=1) else 0
        return current, best

    def on_card(self, card_id: str) -> int:
        """카드를 만들 때 호출. 오늘의 차면 보너스 점수를 돌려준다 (하루 1번)"""
        self.mark_active()
        t = today()
        target = target_for(t)
        d = self._load()
        if card_id != target["id"] or t.isoformat() in d["found"]:
            return 0
        d["found"].append(t.isoformat())
        self._save(d)
        return BONUS.get(target["rarity"], 20)

    def mission(self) -> DailyMission:
        t = today()
        v = target_for(t)
        cur, best = self.streak()
        return DailyMission(
            date=t.isoformat(),
            target=CatalogVehicle(**{k: v[k] for k in ("id", "brand", "model", "body_type", "rarity")}),
            found=t.isoformat() in self._load()["found"],
            bonus=BONUS.get(v["rarity"], 20),
            streak=cur,
            best_streak=best,
        )
