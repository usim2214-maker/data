"""쇼룸 꾸미기 아이템 카탈로그 + 해금 판정.

아이템은 여행 활동으로 열린다 → '꾸미고 싶어서 여행 가는' 동기.
"""
import json
from functools import lru_cache
from pathlib import Path

from app.schemas import DecorItem
from app.services import brand_spots, regions
from app.services.competition import CompetitionService, points_by_kind
from app.services.progress import synergy_for
from app.services.store import JsonStore
from app.services.quiz import stars as quiz_stars

_PATH = Path(__file__).resolve().parent.parent / "data" / "decor_items.json"
REGION_PREFIX = "region:"


@lru_cache
def _raw() -> list[dict]:
    with open(_PATH, encoding="utf-8") as f:
        return json.load(f)["items"]


def _hint(rule: str) -> str:
    kind, _, arg = rule.partition(":")
    if kind == "region":
        r = regions.get_region(arg)
        return f"{r.name} 스탬프 획득" if r else "지역 스탬프"
    return {
        "default": "기본 제공",
        "regions": f"지역 스탬프 {arg}개",
        "hidden": f"히든 스탬프 {arg}개",
        "course": f"코스 완주 {arg}회",
        "badge": f"축제 한정 배지 {arg}개",
        "master": f"지역 마스터 {arg}곳",
        "synergy": f"시너지 {arg}점",
        "quiz": f"퀴즈 별 {arg}개",
    }.get(kind, rule)


class DecorService:
    def __init__(self, store: JsonStore, competition: CompetitionService):
        self.store, self.competition = store, competition

    def _progress(self) -> dict[str, int]:
        kinds = points_by_kind(self.store, {"course_complete"})
        return {
            "regions": len(self.store.get_stamps()),
            "hidden": len(self.store.get_hidden()),
            "course": len(kinds["course_complete"]),
            "badge": len(self.store.get_badges()),
            "master": len(self.competition.my_master_region_ids()),
            "synergy": synergy_for(self.store).total,
            "quiz": quiz_stars(self.store),
        }

    def catalog(self) -> list[DecorItem]:
        p = self._progress()
        owned_regions = set(self.store.get_stamps())

        def unlocked(rule: str) -> bool:
            kind, _, arg = rule.partition(":")
            if kind == "default":
                return True
            if kind == "region":
                return arg in owned_regions
            return p.get(kind, 0) >= int(arg)

        items = [
            DecorItem(id=i["id"], slot=i["slot"], key=i["key"], name=i["name"],
                      unlocked=unlocked(i["unlock"]), unlock_hint=_hint(i["unlock"]))
            for i in _raw()
        ]
        # 지역 스탬프 = 그 지역 랜드마크 소품
        items += [
            DecorItem(id=f"{REGION_PREFIX}{r.id}", slot="prop", key=r.emoji, name=f"{r.name} {r.landmark}",
                      unlocked=r.id in owned_regions, unlock_hint=_hint(f"region:{r.id}"))
            for r in regions.all_regions()
        ]
        # 브랜드 체험 스팟 = 전설 아이템 (방문해야만 열림)
        hidden = self.store.get_hidden()
        for spot in brand_spots.raw_spots():
            it = spot["item"]
            items.append(DecorItem(id=f"{brand_spots.ITEM_PREFIX}{spot['id']}", slot=it["slot"], key=it["key"], name=it["name"],
                                   unlocked=spot["id"] in hidden, unlock_hint=f"{spot['title']} 방문", legendary=True))
        return items

    def by_id(self) -> dict[str, DecorItem]:
        return {i.id: i for i in self.catalog()}
