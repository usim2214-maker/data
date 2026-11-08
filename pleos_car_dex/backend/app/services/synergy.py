"""도감 시너지 점수.

기획 메모의 '시너지 점수'를 MVP 규칙으로 정의한 것. 규칙/배점은 RULES 상수만 바꿔 튜닝한다.
- 기본 점수: 수집한 차량의 희귀도 합
- 재회 보너스: 같은 차량을 다시 마주칠 때마다 소량 가산 (차량당 상한)
- 세트 보너스: 특정 조합을 모으면 추가 점수
"""
from collections import Counter

from app.schemas import CollectionEntry, SynergyBonus, SynergyScore

RARITY_POINTS = {"common": 10, "rare": 30, "legendary": 100}
REENCOUNTER_POINTS, REENCOUNTER_CAP = 2, 10
BRAND_SET_SIZE, BRAND_SET_POINTS = 3, 50
GROUP_TRIO = {"현대", "기아", "제네시스"}
GROUP_TRIO_POINTS = 100
BODY_VARIETY_SIZE, BODY_VARIETY_POINTS = 3, 30
SUMMON_MATCH_POINTS = 5  # 소환한 차량과 같은 브랜드 카드 1장당


def compute(entries: list[CollectionEntry], summoned_brand: str | None = None) -> SynergyScore:
    base = sum(RARITY_POINTS.get(e.card.rarity, 10) for e in entries)
    bonuses: list[SynergyBonus] = []

    reencounter = sum(min((e.seen_count - 1) * REENCOUNTER_POINTS, REENCOUNTER_CAP) for e in entries)
    if reencounter:
        bonuses.append(SynergyBonus(name="재회", points=reencounter, description="이미 수집한 차량을 다시 마주침"))

    brands = Counter(e.card.brand for e in entries)
    for brand, n in brands.items():
        if n >= BRAND_SET_SIZE:
            bonuses.append(
                SynergyBonus(name=f"{brand} 컬렉터", points=BRAND_SET_POINTS, description=f"{brand} 차량 {n}종 수집")
            )

    if GROUP_TRIO <= set(brands):
        bonuses.append(SynergyBonus(name="그룹 트리오", points=GROUP_TRIO_POINTS, description="현대·기아·제네시스 모두 수집"))

    bodies = {e.card.body_type for e in entries}
    if len(bodies) >= BODY_VARIETY_SIZE:
        bonuses.append(
            SynergyBonus(name="다양성", points=BODY_VARIETY_POINTS, description=f"차체 유형 {len(bodies)}가지 수집")
        )

    if summoned_brand and brands.get(summoned_brand, 0) > 1:
        pts = (brands[summoned_brand] - 1) * SUMMON_MATCH_POINTS
        bonuses.append(SynergyBonus(name="소환 공명", points=pts, description=f"소환 차량과 같은 {summoned_brand} 카드"))

    return SynergyScore(total=base + sum(b.points for b in bonuses), base=base, bonuses=bonuses)
