from fastapi import APIRouter, Depends, HTTPException

from app.schemas import Challenge, DriveCourse
from app.services.challenge import ChallengeService
from app.services.deps import get_challenge

router = APIRouter(prefix="/api/v1/challenge", tags=["challenge (코스 완주)"])


@router.post("", response_model=Challenge)
def start(course: DriveCourse, svc: ChallengeService = Depends(get_challenge)):
    """추천받은 코스로 챌린지 시작 (기존 챌린지는 교체). 모든 장소 방문 시 완주 보너스, 축제 코스는 한정 배지."""
    if not course.stops:
        raise HTTPException(status_code=400, detail="장소가 없는 코스예요.")
    return svc.start(course)


@router.get("", response_model=Challenge | None)
def current(svc: ChallengeService = Depends(get_challenge)):
    return svc.current()


@router.delete("", status_code=204)
def abandon(svc: ChallengeService = Depends(get_challenge)):
    svc.abandon()
