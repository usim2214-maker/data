import io
import shutil
import tempfile
from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from PIL import Image, UnidentifiedImageError
from starlette.concurrency import run_in_threadpool

from app.schemas import GeoPoint, RecognitionResponse, VideoRecognitionResponse
from app.services import stamps
from app.services.deps import get_competition
from app.services.pipeline import RecognitionPipeline, get_pipeline

router = APIRouter(prefix="/api/v1", tags=["recognize"])


def _parse(keywords: str | None, lat: float | None, lon: float | None):
    kw = [k.strip() for k in keywords.split(",") if k.strip()] if keywords else None
    location = GeoPoint(lat=lat, lon=lon) if lat is not None and lon is not None else None
    return kw, location


@router.post("/recognize", response_model=RecognitionResponse)
async def recognize(
    image: UploadFile = File(..., description="영상에서 뽑은 프레임 1장 (jpeg/png)"),
    keywords: str | None = Form(None, description="쉼표 구분. 비우면 저장된 유저 키워드 사용"),
    lat: float | None = Form(None, description="Pleos FusedLocation 위도"),
    lon: float | None = Form(None, description="Pleos FusedLocation 경도"),
    save_to_collection: bool = Form(True, description="false 면 카드만 생성하고 도감 등록 안 함"),
    pipeline: RecognitionPipeline = Depends(get_pipeline),
):
    try:
        img = Image.open(io.BytesIO(await image.read()))
    except UnidentifiedImageError:
        raise HTTPException(status_code=400, detail="이미지를 읽을 수 없습니다.")
    kw, location = _parse(keywords, lat, lon)
    if location:
        stamps.record_visit(pipeline.store, location.lat, location.lon, get_competition())
    # 동기 파이프라인(YOLO/LLM)은 스레드풀에서 실행
    return await run_in_threadpool(
        pipeline.run, img, keywords=kw, location=location, filename_hint=image.filename or "",
        register=save_to_collection,
    )


@router.post("/recognize/video", response_model=VideoRecognitionResponse)
async def recognize_video(
    video: UploadFile = File(..., description="주행 영상 (mp4 등). 추후 서라운드뷰 API 로 대체 예정"),
    keywords: str | None = Form(None),
    lat: float | None = Form(None),
    lon: float | None = Form(None),
    save_to_collection: bool = Form(True),
    pipeline: RecognitionPipeline = Depends(get_pipeline),
):
    kw, location = _parse(keywords, lat, lon)
    suffix = Path(video.filename or "upload.mp4").suffix or ".mp4"
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tmp:
        shutil.copyfileobj(video.file, tmp)
        path = Path(tmp.name)
    try:
        return await run_in_threadpool(
            pipeline.run_video, path, keywords=kw, location=location, filename_hint=video.filename or "",
            register=save_to_collection,
        )
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    finally:
        path.unlink(missing_ok=True)

