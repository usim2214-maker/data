from fastapi import APIRouter, Depends

from app.schemas import Preferences
from app.services.pipeline import RecognitionPipeline, get_pipeline

router = APIRouter(prefix="/api/v1/preferences", tags=["preferences"])


@router.get("", response_model=Preferences)
def get_preferences(pipeline: RecognitionPipeline = Depends(get_pipeline)):
    return pipeline.store.get_preferences()


@router.put("", response_model=Preferences)
def put_preferences(prefs: Preferences, pipeline: RecognitionPipeline = Depends(get_pipeline)):
    prefs.keywords = list(dict.fromkeys(k.strip() for k in prefs.keywords if k.strip()))
    return pipeline.store.save_preferences(prefs)
