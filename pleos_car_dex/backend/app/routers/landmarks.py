from fastapi import APIRouter, Depends, Query

from app.schemas import LandmarkNearby, LandmarkVisitRequest, LandmarkVisitResponse
from app.services.deps import get_landmarks
from app.services.landmarks import LandmarkService

router = APIRouter(prefix="/api/v1/landmarks", tags=["landmarks (히든 스탬프)"])


@router.get("/nearby", response_model=list[LandmarkNearby])
def nearby(
    lat: float,
    lon: float,
    radius_m: int = Query(20_000, le=20_000, description="TourAPI 반경 최대 20km"),
    svc: LandmarkService = Depends(get_landmarks),
):
    """주변 TourAPI 명소/진행 중 축제. 앱은 미획득 명소에 Pleos 근접 알림을 등록한다."""
    return svc.nearby(lat, lon, radius_m)


@router.post("/{content_id}/visit", response_model=LandmarkVisitResponse)
def visit(content_id: str, req: LandmarkVisitRequest, svc: LandmarkService = Depends(get_landmarks)):
    """근접 알림 ENTER 시 호출. 서버가 거리(LANDMARK_RADIUS_M)를 재검증한 뒤 히든 스탬프 지급."""
    return svc.visit(content_id, req.lat, req.lon)
