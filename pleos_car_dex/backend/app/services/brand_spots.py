"""브랜드 체험 스팟: 현대 모터스튜디오·기아 360·제네시스 등 = 특별 스탬프.

일반 히든 스탬프와 같은 근접 알림/방문 검증 흐름을 쓰되, 점수가 높고 방문 시 '전설' 쇼룸 아이템이 열린다.
→ 도감으로 생긴 브랜드 관심을 실제 브랜드 공간 방문(오프라인 체험·시승)으로 연결.
"""
import json
from functools import lru_cache
from pathlib import Path

from app.schemas import Attraction
from app.services import regions
from app.services.tour import distance_m

_PATH = Path(__file__).resolve().parent.parent / "data" / "brand_spots.json"
KIND = "brand"
POINTS = 40
ITEM_PREFIX = "spot:"


@lru_cache
def raw_spots() -> list[dict]:
    with open(_PATH, encoding="utf-8") as f:
        return json.load(f)["spots"]


def all_spots() -> list[Attraction]:
    out = []
    for s in raw_spots():
        region = regions.locate(s["lat"], s["lon"])
        out.append(Attraction(content_id=s["id"], title=s["title"], addr=s["addr"], lat=s["lat"], lon=s["lon"],
                              kind=KIND, region_id=region.id if region else None))
    return out


def nearby(lat: float, lon: float, radius_m: float) -> list[Attraction]:
    return [a for a in all_spots() if distance_m(lat, lon, a.lat, a.lon) <= radius_m]


def item_for(spot_id: str) -> dict | None:
    return next((s["item"] for s in raw_spots() if s["id"] == spot_id), None)


def ids() -> set[str]:
    return {s["id"] for s in raw_spots()}
