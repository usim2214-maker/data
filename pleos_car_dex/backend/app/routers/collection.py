from fastapi import APIRouter, Depends, HTTPException

from app.schemas import CardColorRequest, CatalogVehicle, CollectionEntry, CollectionResponse
from app.services.catalog import load_catalog
from app.services import mycar
from app.services.progress import synergy_for
from app.services.pipeline import RecognitionPipeline, get_pipeline

router = APIRouter(prefix="/api/v1/collection", tags=["collection"])


@router.get("", response_model=CollectionResponse)
def list_collection(pipeline: RecognitionPipeline = Depends(get_pipeline)):
    entries = pipeline.store.list_entries()
    my_car = pipeline.store.get_preferences().my_car_id
    entries = [e.model_copy(update={"card": mycar.annotate(e.card, my_car)}) for e in entries]  # 내 차 비교는 조회 시 계산
    return CollectionResponse(total=len(entries), entries=entries, synergy=synergy_for(pipeline.store))


@router.get("/catalog", response_model=list[CatalogVehicle])
def catalog():
    """도감 전체 칸 (희귀도 순). 앱은 아직 못 모은 차량을 실루엣으로 보여준다"""
    order = {"legendary": 0, "rare": 1, "common": 2}
    return sorted((CatalogVehicle(**{k: v[k] for k in ("id", "brand", "model", "body_type", "rarity")}) for v in load_catalog()),
                  key=lambda c: (order.get(c.rarity, 3), c.brand, c.model))


@router.delete("/{card_id}", status_code=204)
def delete_entry(card_id: str, pipeline: RecognitionPipeline = Depends(get_pipeline)):
    if not pipeline.store.delete(card_id):
        raise HTTPException(status_code=404, detail="도감에 없는 차량입니다.")


@router.put("/{card_id}/color", response_model=CollectionEntry)
def set_color(card_id: str, req: CardColorRequest, pipeline: RecognitionPipeline = Depends(get_pipeline)):
    """카드에 보여줄 공식 색상 선택 (None 이면 도로에서 본 색으로 자동)"""
    if req.color_code is not None and not (1 <= len(req.color_code) <= 12):
        raise HTTPException(status_code=400, detail="잘못된 색상 코드")
    e = pipeline.store.set_card_color(card_id, req.color_code)
    if e is None:
        raise HTTPException(status_code=404, detail="도감에 없는 카드")
    return e
