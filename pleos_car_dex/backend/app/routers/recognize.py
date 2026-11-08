import io

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile
from PIL import Image, UnidentifiedImageError

from app.schemas import GeoPoint, RecognitionResponse
from app.services.pipeline import RecognitionPipeline, get_pipeline

router = APIRouter(prefix="/api/v1", tags=["recognize"])


@router.post("/recognize", response_model=RecognitionResponse)
async def recognize(
    image: UploadFile = File(..., description="카메라 프레임 (jpeg/png)"),
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

    kw = [k.strip() for k in keywords.split(",") if k.strip()] if keywords else None
    location = GeoPoint(lat=lat, lon=lon) if lat is not None and lon is not None else None
    # 동기 파이프라인(YOLO/LLM)은 FastAPI 스레드풀에서 실행
    from starlette.concurrency import run_in_threadpool

    return await run_in_threadpool(
        pipeline.run, img, keywords=kw, location=location, filename_hint=image.filename or "", register=save_to_collection
    )
