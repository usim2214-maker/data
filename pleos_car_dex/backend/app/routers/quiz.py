import io

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from PIL import Image, UnidentifiedImageError
from starlette.concurrency import run_in_threadpool

from app.schemas import QuizResponse
from app.services import quiz
from app.services.pipeline import RecognitionPipeline, get_pipeline

router = APIRouter(prefix="/api/v1/quiz", tags=["quiz (아이용 차 맞히기)"])


@router.post("/question", response_model=QuizResponse)
async def question(
    image: UploadFile = File(..., description="방금 지나간 장면"),
    type: str | None = Form(None, description="kind | color (비우면 무작위)"),
    pipeline: RecognitionPipeline = Depends(get_pipeline),
):
    """YOLOv8 로 가장 가깝게 보인 차를 골라 종류·색깔 문제를 낸다 (차종 판별 AI 사용 안 함)"""
    try:
        img = Image.open(io.BytesIO(await image.read())).convert("RGB")
    except UnidentifiedImageError:
        raise HTTPException(status_code=400, detail="이미지를 읽을 수 없습니다.")

    def run() -> QuizResponse:
        dets = pipeline.detector.detect(img)
        primary = pipeline.detector.pick_primary(dets)
        if primary is None:
            return QuizResponse(found=False, message="지나가는 차를 찾지 못했어요. 조금 뒤에 다시 해 볼까요?")
        crop = pipeline.detector.crop(img, primary.bbox)
        return QuizResponse(found=True, question=quiz.make_question(primary, crop, type), detections=dets)

    return await run_in_threadpool(run)
