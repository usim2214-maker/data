from fastapi import APIRouter, Depends

from app.schemas import TripReport, TripReportRequest
from app.services.deps import get_trips
from app.services.trips import TripService

router = APIRouter(prefix="/api/v1/trips", tags=["trips (주행 리포트)"])


@router.post("/report", response_model=TripReport)
def report(req: TripReportRequest, svc: TripService = Depends(get_trips)):
    """주차(P단) 시 호출. Pleos Odometer 거리·시간 + 이번 주행에서 모은 카드·스탬프·점수 요약"""
    return svc.report(req)
