"""카트라이더식 쇼룸: 도감 차량 중 대표 차량 1대를 전시하고, 획득한 지역 스탬프로 배경/스티커를 꾸민다."""
from fastapi import APIRouter, Depends, HTTPException

from app.schemas import Showroom, ShowroomUpdate
from app.services import regions, stamps
from app.services.deps import get_competition
from app.services.pipeline import RecognitionPipeline, get_pipeline

router = APIRouter(prefix="/api/v1/showroom", tags=["showroom"])


def _resolve(pipeline: RecognitionPipeline, state: ShowroomUpdate) -> Showroom:
    entry = pipeline.store.get_entry(state.card_id) if state.card_id else None
    masters = get_competition().masters()
    mastered = {m.region.id for m in masters.masters if m.is_me}
    return Showroom(
        car=entry.card if entry else None,
        background=regions.get_region(state.background_region_id) if state.background_region_id else None,
        stickers=[r for rid in state.sticker_region_ids if (r := regions.get_region(rid))],
        background_mastered=state.background_region_id in mastered,
        titles=masters.my_titles,
        badges=pipeline.store.get_badges(),
    )


@router.get("", response_model=Showroom)
def get_showroom(pipeline: RecognitionPipeline = Depends(get_pipeline)):
    return _resolve(pipeline, pipeline.store.get_showroom())


@router.put("", response_model=Showroom)
def update_showroom(req: ShowroomUpdate, pipeline: RecognitionPipeline = Depends(get_pipeline)):
    if req.card_id and pipeline.store.get_entry(req.card_id) is None:
        raise HTTPException(status_code=400, detail="도감에서 획득한 차량만 전시할 수 있어요.")
    owned = stamps.acquired_ids(pipeline.store)
    used = ([req.background_region_id] if req.background_region_id else []) + req.sticker_region_ids
    if missing := [rid for rid in used if rid not in owned]:
        raise HTTPException(status_code=400, detail=f"아직 획득하지 않은 지역 스탬프예요: {', '.join(missing)}")
    req.sticker_region_ids = list(dict.fromkeys(req.sticker_region_ids))
    return _resolve(pipeline, pipeline.store.save_showroom(req))
