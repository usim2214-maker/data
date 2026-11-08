from fastapi import APIRouter, Depends

from app.schemas import Leaderboard, MastersResponse
from app.services.competition import CompetitionService
from app.services.deps import get_competition

router = APIRouter(prefix="/api/v1/rankings", tags=["rankings (경쟁)"])


@router.get("/weekly", response_model=Leaderboard)
def weekly(region_id: str | None = None, svc: CompetitionService = Depends(get_competition)):
    """이번 주 여행 점수 랭킹 (전국 또는 지역). 속도·주행시간이 아닌 방문·수집 점수로만 경쟁."""
    return svc.weekly(region_id)


@router.get("/masters", response_model=MastersResponse)
def masters(svc: CompetitionService = Depends(get_competition)):
    """이번 달 시·도별 지역 마스터 (그 지역 여행 점수 1위). 다른 사용자가 넘으면 칭호를 빼앗긴다."""
    return svc.masters()
