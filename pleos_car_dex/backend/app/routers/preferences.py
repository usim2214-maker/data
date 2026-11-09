from fastapi import APIRouter, Depends

from app.schemas import Preferences
from app.services.catalog import available_fields
from app.services.pipeline import RecognitionPipeline, get_pipeline

router = APIRouter(prefix="/api/v1/preferences", tags=["preferences"])


@router.get("", response_model=Preferences)
def get_preferences(pipeline: RecognitionPipeline = Depends(get_pipeline)):
    return pipeline.store.get_preferences()


@router.get("/fields", response_model=list[str])
def fields():
    """카드 뒷면에 고를 수 있는 항목 (차량 정보 목록에 있는 항목만)"""
    return available_fields()


@router.put("", response_model=Preferences)
def put_preferences(prefs: Preferences, pipeline: RecognitionPipeline = Depends(get_pipeline)):
    valid = set(available_fields())
    prefs.keywords = [k for k in dict.fromkeys(k.strip() for k in prefs.keywords) if k in valid]  # 목록에 없는 항목은 제외
    from app.services.catalog import load_catalog
    if prefs.my_car_id and prefs.my_car_id not in {v["id"] for v in load_catalog()}:
        prefs.my_car_id = None  # 도감 28종 중에서만
    return pipeline.store.save_preferences(prefs)
