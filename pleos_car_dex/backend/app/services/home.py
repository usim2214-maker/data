"""여행 탭 상단 요약: 심사·사용자가 첫 화면에서 '지금 내 상태와 다음 목표'를 바로 알 수 있게."""
from app.schemas import HomeSummary
from collections import Counter

from app.services import regions
from app.services.catalog import load_catalog
from app.services.challenge import ChallengeService
from app.services.competition import CompetitionService
from app.services.decor import DecorService
from app.services.progress import synergy_for
from app.services.store import JsonStore


def summary(store: JsonStore, competition: CompetitionService, challenge: ChallengeService, decor: DecorService) -> HomeSummary:
    weekly = competition.weekly()
    masters = competition.masters()
    catalog = decor.catalog()
    ch = challenge.current()
    active = ch if ch and ch.status == "active" else None

    vehicles = load_catalog()
    catalog_ids = {v["id"] for v in vehicles}
    owned = [e for e in store.list_entries() if e.card.id in catalog_ids]
    owned_ids = {e.card.id for e in owned}
    brand_count = Counter(e.card.brand for e in owned)
    near_set = [b for b, n in brand_count.items() if n == 2]  # 1종만 더 모으면 브랜드 컬렉터(3종)

    # 다음 목표: 진행 중 챌린지 > 첫 카드 > 브랜드 세트 > 가장 가까운 지역 마스터 > 다음 순위
    reachable = [m for m in masters.masters if not m.is_me and m.my_points > 0]
    if active:
        left = len(active.course.stops) - len(active.visited)
        goal = f"코스 완주까지 {left}곳 남음 · 완주 +{active.course.completion_bonus}"
    elif not owned:
        goal = "도로에서 마주친 차량으로 첫 카드를 만들어 보세요"
    elif near_set:
        goal = f"{near_set[0]} 차량 1종 더 모으면 '{near_set[0]} 컬렉터' +50"
    elif reachable:
        m = min(reachable, key=lambda x: x.gap)
        goal = f"{m.region.emoji} {m.region.name} 마스터까지 {m.gap}점"
    elif weekly.gap_to_next:
        goal = f"이번 주 한 계단 위까지 {weekly.gap_to_next}점 — AI 코스로 새 지역을 달려 보세요"
    else:
        goal = "AI 코스를 추천받아 첫 지역 스탬프를 모아 보세요"

    return HomeSummary(
        dex_owned=len(owned_ids),
        dex_total=len(vehicles),
        legendary_owned=sum(1 for e in owned if e.card.rarity == "legendary"),
        legendary_total=sum(1 for v in vehicles if v["rarity"] == "legendary"),
        synergy_total=synergy_for(store).total,
        weekly_rank=weekly.me.rank if weekly.me else 0,
        weekly_points=weekly.me.points if weekly.me else 0,
        gap_to_next=weekly.gap_to_next,
        region_stamps=len(store.get_stamps()),
        region_total=len(regions.all_regions()),
        hidden_stamps=len(store.get_hidden()),
        collection=len(store.list_entries()),
        items_unlocked=sum(i.unlocked for i in catalog),
        items_total=len(catalog),
        titles=masters.my_titles,
        challenge_title=active.course.title if active else None,
        challenge_progress=f"{len(active.visited)}/{len(active.course.stops)}" if active else None,
        next_goal=goal,
    )
