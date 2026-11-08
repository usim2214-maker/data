"""한국관광공사 TourAPI (공공데이터포털 KorService2) 클라이언트.

- locationBasedList2: 좌표 주변 관광지 (반경 최대 20km)
- searchFestival2: 행사/축제
TOUR_API_KEY 가 없으면 app/data/mock_attractions.json 으로 동작해 오프라인 시연이 가능하다.
"""
import json
import logging
import math
import time
from datetime import date, datetime, timedelta
from pathlib import Path

import httpx

from app.config import Settings
from app.schemas import Attraction
from app.services import regions

log = logging.getLogger(__name__)

CONTENT_TYPE_ATTRACTION = "12"  # 관광지
MAX_RADIUS_M = 20_000
_MOCK_PATH = Path(__file__).resolve().parent.parent / "data" / "mock_attractions.json"
_CACHE_TTL = 30 * 60


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 6_371_000 * 2 * math.asin(math.sqrt(a))


class TourClient:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._cache: dict[tuple, tuple[float, list[Attraction]]] = {}
        self._http = httpx.Client(timeout=10.0)

    @property
    def source(self) -> str:
        return "tourapi" if self.settings.tour_enabled else "mock"

    # ---------------- public ----------------
    def nearby(self, lat: float, lon: float, radius_m: int = MAX_RADIUS_M, limit: int = 20) -> list[Attraction]:
        radius_m = min(radius_m, MAX_RADIUS_M)
        if not self.settings.tour_enabled:
            items = [a for a in self._mock("attractions") if distance_m(lat, lon, a.lat, a.lon) <= radius_m]
            return sorted(items, key=lambda a: distance_m(lat, lon, a.lat, a.lon))[:limit]
        key = ("nearby", round(lat, 2), round(lon, 2), radius_m, limit)
        return self._cached(key, lambda: self._fetch(
            "locationBasedList2",
            {"contentTypeId": CONTENT_TYPE_ATTRACTION, "mapX": lon, "mapY": lat, "radius": radius_m, "arrange": "E"},
            limit,
        ))

    def in_region(self, region, limit: int = 4) -> list[Attraction]:
        """지역 대표 명소. 실 API 는 지역 중심 반경 20km, 목업은 해당 지역 전체에서 고른다."""
        if not self.settings.tour_enabled:
            return [a for a in self._mock("attractions") if a.region_id == region.id][:limit]
        return self.nearby(region.lat, region.lon, limit=limit)

    def festivals(self, on: date, within_days: int = 14) -> list[Attraction]:
        """on ~ on+within_days 사이에 진행 중인 축제"""
        until = on + timedelta(days=within_days)
        if not self.settings.tour_enabled:
            items = []
            for f in self._mock("festivals"):
                start = datetime.strptime(f"{on.year}{f.event_start}", "%Y%m%d").date()
                end = datetime.strptime(f"{on.year}{f.event_end}", "%Y%m%d").date()
                if start <= until and end >= on:
                    items.append(f.model_copy(update={"event_start": start.strftime("%Y%m%d"), "event_end": end.strftime("%Y%m%d")}))
            return items
        key = ("festival", on.isoformat(), within_days)

        def load():
            # eventStartDate 이후 시작하는 행사를 주므로, 이미 진행 중인 축제를 잡기 위해 60일 전부터 조회 후 기간 필터
            raw = self._fetch("searchFestival2", {"eventStartDate": (on - timedelta(days=60)).strftime("%Y%m%d"), "arrange": "A"}, 300)
            fmt = "%Y%m%d"
            return [
                a for a in raw
                if a.event_start and a.event_end
                and datetime.strptime(a.event_start, fmt).date() <= until
                and datetime.strptime(a.event_end, fmt).date() >= on
            ]

        return self._cached(key, load)

    # ---------------- internal ----------------
    def _cached(self, key: tuple, loader) -> list[Attraction]:
        hit = self._cache.get(key)
        if hit and time.time() - hit[0] < _CACHE_TTL:
            return hit[1]
        try:
            value = loader()
        except (httpx.HTTPError, ValueError, KeyError) as e:
            log.warning("TourAPI 호출 실패 (%s) -> 빈 결과", e)
            return []
        self._cache[key] = (time.time(), value)
        return value

    def _fetch(self, operation: str, params: dict, rows: int) -> list[Attraction]:
        query = {
            "serviceKey": self.settings.tour_api_key,
            "MobileOS": "AND",
            "MobileApp": "PleosCarDex",
            "_type": "json",
            "numOfRows": rows,
            "pageNo": 1,
            **params,
        }
        res = self._http.get(f"{self.settings.tour_api_base}/{operation}", params=query)
        res.raise_for_status()
        data = res.json()
        header = data["response"]["header"]
        if header.get("resultCode") != "0000":
            raise ValueError(f"TourAPI {header.get('resultCode')}: {header.get('resultMsg')}")
        body = data["response"].get("body") or {}
        items = body.get("items")
        items = items.get("item", []) if isinstance(items, dict) else []  # 결과 없으면 items == ""
        if isinstance(items, dict):  # 1건이면 객체로 옴
            items = [items]
        return [a for it in items if (a := self._to_attraction(it, festival=operation == "searchFestival2"))]

    @staticmethod
    def _to_attraction(it: dict, festival: bool) -> Attraction | None:
        try:
            lat, lon = float(it["mapy"]), float(it["mapx"])  # mapx=경도, mapy=위도
        except (KeyError, TypeError, ValueError):
            return None
        region = regions.locate(lat, lon)
        return Attraction(
            content_id=str(it.get("contentid", "")),
            title=it.get("title", ""),
            addr=it.get("addr1", ""),
            lat=lat,
            lon=lon,
            image=it.get("firstimage") or None,
            kind="festival" if festival else "attraction",
            event_start=it.get("eventstartdate") or None,
            event_end=it.get("eventenddate") or None,
            region_id=region.id if region else None,
        )

    def _mock(self, kind: str) -> list[Attraction]:
        with open(_MOCK_PATH, encoding="utf-8") as f:
            raw = json.load(f)[kind]
        out = []
        for r in raw:
            region = regions.locate(r["lat"], r["lon"])
            out.append(Attraction(**r, kind="festival" if kind == "festivals" else "attraction",
                                  region_id=region.id if region else None))
        return out
