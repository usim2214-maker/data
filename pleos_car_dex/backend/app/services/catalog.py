"""시연용 차종 카탈로그 로더."""
import json
import re
from functools import lru_cache
from pathlib import Path

_CATALOG_PATH = Path(__file__).resolve().parent.parent / "data" / "vehicle_catalog.json"


@lru_cache
def _data() -> dict:
    with open(_CATALOG_PATH, encoding="utf-8") as f:
        return json.load(f)


def load_catalog() -> list[dict]:
    return _data()["vehicles"]


def available_fields() -> list[str]:
    """사용자가 카드 뒷면에 고를 수 있는 항목 (목록에 실제로 있는 정보만)"""
    return _data()["fields"]


def _norm(text: str) -> str:
    return re.sub(r"[\s\-_]+", "", text).lower()


def find_vehicle(brand: str, model: str) -> dict | None:
    """LLM 이 돌려준 brand/model 을 카탈로그 항목과 느슨하게 매칭."""
    target = _norm(model)
    for v in load_catalog():
        if _norm(v["model"]) == target or target in _norm(v["id"]):
            return v
    return None


def slugify(brand: str, model: str) -> str:
    v = find_vehicle(brand, model)
    if v:
        return v["id"]
    return re.sub(r"[^0-9a-zA-Z가-힣]+", "-", f"{brand}-{model}").strip("-").lower()


def eco_type(vehicle_id: str) -> str | None:
    """친환경 구분: 'ev'(전기 전용) | 'eco_option'(하이브리드·전기 트림 선택 가능) | None"""
    v = next((x for x in load_catalog() if x["id"] == vehicle_id), None)
    if v is None:
        return None
    fuel = v["facts"].get("연료", "")
    if fuel.strip() == "전기" or fuel.strip() == "수소":
        return "ev"
    if any(k in fuel for k in ("전기", "하이브리드", "수소")):
        return "eco_option"
    return None
