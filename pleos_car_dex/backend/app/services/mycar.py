"""내 차: 사용자가 등록한 차와 같은 차(쌍둥이)를 만나면 보너스, 카드 뒷면에 '내 차와 비교'.

현대차 관점: 오너의 브랜드 애착을 키우고, 다른 차와의 비교로 다음 차 관심(업그레이드)을 자연스럽게 만든다.
"""
import re

from app.schemas import VehicleCard
from app.services.catalog import load_catalog

TWIN_POINTS = 10


def _num(text: str) -> float | None:
    """'약 3,500만 원대~' -> 3500, '약 245~294마력' -> 245, '최대 약 650마력' -> 650 (첫 숫자)"""
    m = re.search(r"\d[\d,]*(?:\.\d+)?", text or "")
    return float(m.group().replace(",", "")) if m else None


def _price_manwon(text: str) -> float | None:
    """'약 1억 6,000만' 같은 억 단위도 만원으로"""
    t = text or ""
    eok = re.search(r"(\d+)\s*억", t)
    man = re.search(r"억\s*([\d,]+)\s*만", t) if eok else None
    if eok:
        return int(eok.group(1)) * 10000 + (float(man.group(1).replace(",", "")) if man else 0)
    return _num(t)


def compare(card: VehicleCard, my_car_id: str | None) -> list[str]:
    """카드 차량 vs 내 차: 가격·마력·차급 차이를 짧은 문장으로"""
    if not my_car_id or card.id == my_car_id:
        return []
    by_id = {v["id"]: v for v in load_catalog()}
    mine, other = by_id.get(my_car_id), by_id.get(card.id)
    if not mine or not other:
        return []
    out = []
    p1, p2 = _price_manwon(other["facts"].get("가격", "")), _price_manwon(mine["facts"].get("가격", ""))
    if p1 and p2:
        d = p1 - p2
        out.append("가격 비슷" if abs(d) < 300 else f"가격 {'+' if d > 0 else '-'}{abs(d):,.0f}만 원")
    h1, h2 = _num(other["facts"].get("마력", "")), _num(mine["facts"].get("마력", ""))
    if h1 and h2:
        d = h1 - h2
        out.append("마력 비슷" if abs(d) < 20 else f"마력 {'+' if d > 0 else '-'}{abs(d):.0f}마력")
    c1, c2 = other["facts"].get("차급"), mine["facts"].get("차급")
    if c1 and c2 and c1 != c2:
        out.append(f"차급 {c1} · 내 차 {c2}")
    return out


def annotate(card: VehicleCard, my_car_id: str | None) -> VehicleCard:
    return card.model_copy(update={"is_my_car": bool(my_car_id) and card.id == my_car_id, "vs_my_car": compare(card, my_car_id)})
