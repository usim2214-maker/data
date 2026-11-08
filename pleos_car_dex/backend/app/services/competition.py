"""경쟁 요소: 여행 점수 원장 -> 주간 랭킹 / 이번 달 지역 마스터.

경쟁은 '어디를 가고 무엇을 모았는지'로만 한다 (속도·주행 시간 경쟁 없음 — 안전).
MVP 는 단일 사용자(me) + 시연용 가상 경쟁자(app/data/rivals.json). 실제 서비스는 계정별 원장으로 교체.
"""
import json
from collections import defaultdict
from datetime import datetime, timezone
from functools import lru_cache
from pathlib import Path
from zoneinfo import ZoneInfo

from app.schemas import Leaderboard, MastersResponse, PointEvent, RankRow, RegionMaster
from app.services import regions
from app.services.store import JsonStore

KST = ZoneInfo("Asia/Seoul")
ME, MY_NICKNAME = "me", "나"
TOP_N = 10
_RIVALS = Path(__file__).resolve().parent.parent / "data" / "rivals.json"


@lru_cache
def _rivals() -> list[dict]:
    with open(_RIVALS, encoding="utf-8") as f:
        return json.load(f)["rivals"]


def week_key(dt: datetime) -> str:
    iso = dt.astimezone(KST).isocalendar()
    return f"{iso.year}-W{iso.week:02d}"


def month_key(dt: datetime) -> str:
    return dt.astimezone(KST).strftime("%Y-%m")


class CompetitionService:
    def __init__(self, store: JsonStore):
        self.store = store

    def record(self, points: int, region_id: str | None, kind: str, label: str = "") -> None:
        if points <= 0:
            return
        self.store.add_event(PointEvent(
            user_id=ME, nickname=MY_NICKNAME, points=points, region_id=region_id, kind=kind, label=label,
            at=datetime.now(timezone.utc),
        ))

    # ---------------- 점수 집계 ----------------
    def _scores(self, period: str, region_id: str | None, weekly: bool) -> dict[str, tuple[str, int]]:
        """{user_id: (nickname, points)}"""
        key = week_key if weekly else month_key
        out: dict[str, tuple[str, int]] = {}
        mine = sum(
            e.points for e in self.store.get_events()
            if key(e.at) == period and (region_id is None or e.region_id == region_id)
        )
        out[ME] = (MY_NICKNAME, mine)
        for r in _rivals():
            monthly = r["monthly"]
            pts = monthly.get(region_id, 0) if region_id else sum(monthly.values())
            if weekly:
                pts = round(pts * r["weekly_ratio"])
            out[r["user_id"]] = (r["nickname"], pts)
        return out

    @staticmethod
    def _rank(scores: dict[str, tuple[str, int]]) -> list[RankRow]:
        # 동점이면 경쟁자가 앞 (마스터는 '넘어야' 뺏는다)
        ordered = sorted(scores.items(), key=lambda kv: (-kv[1][1], kv[0] == ME))
        return [RankRow(rank=i + 1, user_id=uid, nickname=nick, points=pts, is_me=uid == ME)
                for i, (uid, (nick, pts)) in enumerate(ordered)]

    # ---------------- API ----------------
    def weekly(self, region_id: str | None = None) -> Leaderboard:
        period = week_key(datetime.now(timezone.utc))
        rows = self._rank(self._scores(period, region_id, weekly=True))
        me = next(r for r in rows if r.is_me)
        gap = rows[me.rank - 2].points - me.points + 1 if me.rank > 1 else None
        return Leaderboard(period=period, region_id=region_id, rows=rows[:TOP_N], me=me, gap_to_next=gap)

    def masters(self) -> MastersResponse:
        period = month_key(datetime.now(timezone.utc))
        out, titles = [], []
        for region in regions.all_regions():
            rows = [r for r in self._rank(self._scores(period, region.id, weekly=False)) if r.points > 0]
            master = rows[0] if rows else None
            mine = next((r.points for r in rows if r.is_me), 0)
            is_me = bool(master and master.is_me)
            if is_me:
                titles.append(region.name)
            gap = 0 if is_me else ((master.points - mine + 1) if master else 1)
            out.append(RegionMaster(region=region, master=master, my_points=mine, gap=gap, is_me=is_me))
        return MastersResponse(period=period, masters=out, my_titles=titles)

    def my_master_region_ids(self) -> set[str]:
        return {m.region.id for m in self.masters().masters if m.is_me}


def points_by_kind(store: JsonStore, kinds: set[str]) -> dict[str, list[int]]:
    acc: dict[str, list[int]] = defaultdict(list)
    for e in store.get_events():
        if e.kind in kinds:
            acc[e.kind].append(e.points)
    return acc
