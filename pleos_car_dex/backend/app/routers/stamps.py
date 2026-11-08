from fastapi import APIRouter, Depends

from app.schemas import StampBook, VisitRequest, VisitResponse
from app.services import stamps
from app.services.pipeline import RecognitionPipeline, get_pipeline

router = APIRouter(prefix="/api/v1/stamps", tags=["stamps"])


@router.get("", response_model=StampBook)
def get_stamp_book(pipeline: RecognitionPipeline = Depends(get_pipeline)):
    """전체 지역 스탬프 (미획득 포함 — 앱에서 잠금 표시)"""
    return stamps.stamp_book(pipeline.store)


@router.post("/visit", response_model=VisitResponse)
def visit(req: VisitRequest, pipeline: RecognitionPipeline = Depends(get_pipeline)):
    """앱이 Pleos FusedLocation 위치를 주기적으로 보고 -> 새 지역이면 스탬프 획득"""
    return stamps.record_visit(pipeline.store, req.lat, req.lon)
