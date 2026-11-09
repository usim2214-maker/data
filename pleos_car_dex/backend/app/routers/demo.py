from fastapi import APIRouter, Depends, HTTPException

from app.config import get_settings
from app.schemas import HomeSummary
from app.services import demo, home
from app.services.deps import get_challenge, get_competition, get_decor, get_landmarks
from app.services.pipeline import RecognitionPipeline, get_pipeline

router = APIRouter(prefix="/api/v1", tags=["home / demo"])


@router.get("/home", response_model=HomeSummary)
def home_summary(pipeline: RecognitionPipeline = Depends(get_pipeline)):
    """여행 탭 상단 요약 (점수·순위·스탬프·다음 목표)"""
    return home.summary(pipeline.store, get_competition(), get_challenge(), get_decor())


def _require_demo():
    if not get_settings().demo_mode:
        raise HTTPException(status_code=403, detail="DEMO_MODE=true 일 때만 사용할 수 있어요.")


@router.post("/demo/seed", dependencies=[Depends(_require_demo)])
def seed(pipeline: RecognitionPipeline = Depends(get_pipeline)):
    """시연용 데이터 채우기: 차량 4대, 지역 스탬프 3곳(서울·경기·강원), 히든 스탬프 2곳"""
    return demo.seed(pipeline.store, get_competition(), get_landmarks(), pipeline.summarizer)


@router.post("/demo/reset", dependencies=[Depends(_require_demo)])
def reset(pipeline: RecognitionPipeline = Depends(get_pipeline)):
    """시연 초기화: 진행 데이터 전부 삭제 (영상 재촬영용)"""
    pipeline.store.reset_all()
    return {"reset": True}
