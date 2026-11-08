"""위치(Pleos FusedLocation) -> 지역 스탬프 판정."""
import json
import math
from functools import lru_cache
from pathlib import Path

from app.schemas import Region

_PATH = Path(__file__).resolve().parent.parent / "data" / "regions.json"


@lru_cache
def _data() -> dict:
    with open(_PATH, encoding="utf-8") as f:
        return json.load(f)


@lru_cache
def all_regions() -> list[Region]:
    return [Region.model_validate(r) for r in _data()["regions"]]


def get_region(region_id: str) -> Region | None:
    return next((r for r in all_regions() if r.id == region_id), None)


def _haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 6371 * 2 * math.asin(math.sqrt(a))


def locate(lat: float, lon: float) -> Region | None:
    """가장 가까운 지역 중심이 반경 안이면 그 지역. (MVP 근사 — 역지오코딩으로 교체 가능)"""
    nearest = min(all_regions(), key=lambda r: _haversine_km(lat, lon, r.lat, r.lon))
    return nearest if _haversine_km(lat, lon, nearest.lat, nearest.lon) <= _data()["radius_km"] else None
