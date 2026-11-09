"""도감 시너지 점수 = 차량 수집 점수 + 국내 여행(지역 스탬프) 점수.

여행 점수는 '덜 가 본 곳에 가고, 다시 가고, 권역을 완성하고, 여행지에서 차량을 수집'하도록 설계했다.
모든 배점은 아래 상수와 regions.json 의 travel_points 로 튜닝한다.
"""
from collections import Counter, defaultdict

from app.schemas import CollectionEntry, Region, SynergyBonus, SynergyScore

# ---- 차량 ----
RARITY_POINTS = {"common": 10, "rare": 30, "legendary": 100}
REENCOUNTER_POINTS, REENCOUNTER_CAP = 2, 10
BRAND_SET_SIZE, BRAND_SET_POINTS = 3, 50
GROUP_TRIO = {"현대", "기아", "제네시스"}
GROUP_TRIO_POINTS = 100
BODY_VARIETY_SIZE, BODY_VARIETY_POINTS = 3, 30
FEATURED_MATCH_POINTS = 5  # 쇼룸 대표 차량과 같은 브랜드 카드 1장당

# ---- 여행 (지역 스탬프) ----
REVISIT_POINTS, REVISIT_CAP = 5, 15  # 재방문(1시간 이상 간격) 1회당 / 지역당 상한 — 단골 지역 장려
AREA_COMPLETE_POINTS = 100  # 권역 내 모든 시·도 방문
TRAVEL_FIND_POINTS = 10  # 수도권 밖에서 처음 발견한 차량 1대당 — 여행지에서의 수집 장려
HOME_AREA = "수도권"


def compute(
    entries: list[CollectionEntry],
    featured_brand: str | None = None,
    stamps: dict[str, dict] | None = None,
    regions: list[Region] | None = None,
    locate=None,
    hidden_points: list[int] | None = None,
    course_points: list[int] | None = None,
    badge_points: list[int] | None = None,
    twin_points: list[int] | None = None,
) -> SynergyScore:
    stamps, regions = stamps or {}, regions or []
    base = sum(RARITY_POINTS.get(e.card.rarity, 10) for e in entries)
    bonuses: list[SynergyBonus] = []

    # ---------- 차량 ----------
    reencounter = sum(min((e.seen_count - 1) * REENCOUNTER_POINTS, REENCOUNTER_CAP) for e in entries)
    if reencounter:
        bonuses.append(SynergyBonus(name="재회", points=reencounter, description="이미 수집한 차량을 다시 마주침"))

    brands = Counter(e.card.brand for e in entries)
    for brand, n in brands.items():
        if n >= BRAND_SET_SIZE:
            bonuses.append(SynergyBonus(name=f"{brand} 컬렉터", points=BRAND_SET_POINTS, description=f"{brand} 차량 {n}종 수집"))
    if GROUP_TRIO <= set(brands):
        bonuses.append(SynergyBonus(name="그룹 트리오", points=GROUP_TRIO_POINTS, description="현대·기아·제네시스 모두 수집"))

    bodies = {e.card.body_type for e in entries}
    if len(bodies) >= BODY_VARIETY_SIZE:
        bonuses.append(SynergyBonus(name="다양성", points=BODY_VARIETY_POINTS, description=f"차체 유형 {len(bodies)}가지 수집"))

    if featured_brand and brands.get(featured_brand, 0) > 1:
        pts = (brands[featured_brand] - 1) * FEATURED_MATCH_POINTS
        bonuses.append(SynergyBonus(name="대표차 공명", points=pts, description=f"쇼룸 대표 차량과 같은 {featured_brand} 카드"))

    # ---------- 여행 ----------
    by_id = {r.id: r for r in regions}
    visited = [by_id[rid] for rid in stamps if rid in by_id]
    travel = sum(r.travel_points for r in visited)

    revisit = sum(min((stamps[r.id]["visit_count"] - 1) * REVISIT_POINTS, REVISIT_CAP) for r in visited)
    if revisit:
        bonuses.append(SynergyBonus(name="단골 여행", points=revisit, description="같은 지역 재방문"))

    area_members: dict[str, set[str]] = defaultdict(set)
    for r in regions:
        area_members[r.area].add(r.id)
    for area, members in area_members.items():
        if len(members) > 1 and members <= set(stamps):  # 1개짜리 권역(강원·제주)은 스탬프 점수로 충분
            bonuses.append(SynergyBonus(name=f"{area} 완주", points=AREA_COMPLETE_POINTS, description=f"{area} 모든 시·도 방문"))

    if hidden_points:
        bonuses.append(SynergyBonus(name="히든 스탬프", points=sum(hidden_points), description=f"명소·축제 {len(hidden_points)}곳 방문"))

    if course_points:
        bonuses.append(SynergyBonus(name="코스 완주", points=sum(course_points), description=f"추천 코스 {len(course_points)}회 완주"))
    if badge_points:
        bonuses.append(SynergyBonus(name="축제 한정", points=sum(badge_points), description=f"축제 한정 배지 {len(badge_points)}개"))

    if twin_points:
        bonuses.append(SynergyBonus(name="쌍둥이 차", points=sum(twin_points), description=f"내 차와 같은 차 {len(twin_points)}번 만남"))

    if locate:
        finds = [
            e for e in entries
            if e.first_seen_location and (r := locate(e.first_seen_location.lat, e.first_seen_location.lon)) and r.area != HOME_AREA
        ]
        if finds:
            bonuses.append(
                SynergyBonus(name="여행지 발견", points=len(finds) * TRAVEL_FIND_POINTS, description=f"수도권 밖에서 발견한 차량 {len(finds)}대")
            )

    return SynergyScore(total=base + travel + sum(b.points for b in bonuses), base=base, travel=travel, bonuses=bonuses)
