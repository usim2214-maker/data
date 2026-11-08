"""카트라이더식 차량 소환: 도감에서 고른 차량을 '내 파트너 차량'으로 대시보드에 띄운다."""
from datetime import datetime, timezone

from fastapi import APIRouter, Depends, HTTPException

from app.schemas import GarageState, SummonRequest
from app.services.pipeline import RecognitionPipeline, get_pipeline

router = APIRouter(prefix="/api/v1/garage", tags=["garage"])


@router.get("", response_model=GarageState)
def get_garage(pipeline: RecognitionPipeline = Depends(get_pipeline)):
    return pipeline.store.get_garage()


@router.post("/summon", response_model=GarageState)
def summon(req: SummonRequest, pipeline: RecognitionPipeline = Depends(get_pipeline)):
    entry = pipeline.store.get_entry(req.card_id)
    if entry is None:
        raise HTTPException(status_code=404, detail="도감에 있는 차량만 소환할 수 있어요.")
    return pipeline.store.set_garage(GarageState(active=entry.card, summoned_at=datetime.now(timezone.utc)))


@router.delete("/summon", response_model=GarageState)
def dismiss(pipeline: RecognitionPipeline = Depends(get_pipeline)):
    return pipeline.store.set_garage(GarageState())
