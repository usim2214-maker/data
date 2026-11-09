"""여행 탭 상단 요약: 심사·사용자가 첫 화면에서 '지금 내 상태와 다음 목표'를 바로 알 수 있게."""
from app.schemas import HomeSummary
from app.services import regions
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

    # 다음 목표: 진행 중 챌린지 > 가장 가까운 지역 마스터 > 다음 순위 > 첫 스탬프
    reachable = [m for m in masters.masters if not m.is_me and m.my_points > 0]
    if active:
        left = len(active.course.stops) - len(active.visited)
        goal = f"코스 완주까지 {left}곳 남음 · 완주 +{active.course.completion_bonus}"
    elif reachable:
        m = min(reachable, key=lambda x: x.gap)
        goal = f"{m.region.emoji} {m.region.name} 마스터까지 {m.gap}점"
    elif weekly.gap_to_next:
        goal = f"이번 주 한 계단 위까지 {weekly.gap_to_next}점 — AI 코스로 새 지역을 달려 보세요"
    else:
        goal = "AI 코스를 추천받아 첫 지역 스탬프를 모아 보세요"

    return HomeSummary(
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
