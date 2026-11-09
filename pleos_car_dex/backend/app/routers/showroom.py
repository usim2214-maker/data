"""쇼룸(차고): 도감 차량 중 대표 차량 1대를 전시하고, 여행으로 얻은 아이템으로 무대를 꾸민다.
(차고 꾸미기 방식만 참고한 자체 디자인 — 특정 게임의 에셋/명칭은 사용하지 않음)"""
from fastapi import APIRouter, Depends, HTTPException

from app.schemas import DecorCatalog, PlacedPropOut, Showroom, ShowroomUpdate
from app.services import regions, stamps
from app.services.decor import DecorService
from app.services.deps import get_competition, get_decor
from app.services.pipeline import RecognitionPipeline, get_pipeline

router = APIRouter(prefix="/api/v1/showroom", tags=["showroom"])


def _resolve(pipeline: RecognitionPipeline, decor: DecorService, state: ShowroomUpdate) -> Showroom:
    entry = pipeline.store.get_entry(state.card_id) if state.card_id else None
    masters = get_competition().masters()
    mastered = {m.region.id for m in masters.masters if m.is_me}
    items = decor.by_id()

    def pick(item_id: str, fallback: str):
        it = items.get(item_id)
        return it if it and it.unlocked else items[fallback]  # 잠김(예: 마스터 칭호 상실)이면 기본으로

    sign = pick(state.sign_id, "sign-garage")
    sign_text = sign.key.replace("{title}", masters.my_titles[0]) if "{title}" in sign.key and masters.my_titles else sign.key
    return Showroom(
        car=entry.card if entry else None,
        background=regions.get_region(state.background_region_id) if state.background_region_id else None,
        floor=pick(state.floor_id, "floor-concrete"),
        light=pick(state.light_id, "light-white"),
        pedestal=pick(state.pedestal_id, "pedestal-basic"),
        sign=sign,
        sign_text=sign_text,
        props=[PlacedPropOut(**p.model_dump(), item=items[p.item_id])
               for p in state.props if p.item_id in items and items[p.item_id].unlocked],
        background_mastered=state.background_region_id in mastered,
        titles=masters.my_titles,
        badges=pipeline.store.get_badges(),
    )


@router.get("", response_model=Showroom)
def get_showroom(pipeline: RecognitionPipeline = Depends(get_pipeline), decor: DecorService = Depends(get_decor)):
    return _resolve(pipeline, decor, pipeline.store.get_showroom())


@router.get("/items", response_model=DecorCatalog)
def items(decor: DecorService = Depends(get_decor)):
    """꾸미기 아이템 전체 (잠긴 것 포함 + 해금 조건)"""
    catalog = decor.catalog()
    return DecorCatalog(items=catalog, unlocked=sum(i.unlocked for i in catalog), total=len(catalog))


@router.put("", response_model=Showroom)
def update_showroom(
    req: ShowroomUpdate,
    pipeline: RecognitionPipeline = Depends(get_pipeline),
    decor: DecorService = Depends(get_decor),
):
    if req.card_id and pipeline.store.get_entry(req.card_id) is None:
        raise HTTPException(status_code=400, detail="도감에서 획득한 차량만 전시할 수 있어요.")
    if req.background_region_id and req.background_region_id not in stamps.acquired_ids(pipeline.store):
        raise HTTPException(status_code=400, detail="아직 획득하지 않은 지역 스탬프예요.")
    items = decor.by_id()
    expected = {"floor_id": "floor", "light_id": "light", "pedestal_id": "pedestal", "sign_id": "sign"}
    for field, slot in expected.items():
        it = items.get(getattr(req, field))
        if it is None or it.slot != slot:
            raise HTTPException(status_code=400, detail=f"잘못된 아이템: {getattr(req, field)}")
        if not it.unlocked:
            raise HTTPException(status_code=400, detail=f"'{it.name}' 은(는) 아직 잠겨 있어요 ({it.unlock_hint}).")
    for p in req.props:
        it = items.get(p.item_id)
        if it is None or it.slot != "prop":
            raise HTTPException(status_code=400, detail=f"잘못된 소품: {p.item_id}")
        if not it.unlocked:
            raise HTTPException(status_code=400, detail=f"'{it.name}' 은(는) 아직 잠겨 있어요 ({it.unlock_hint}).")
    return _resolve(pipeline, decor, pipeline.store.save_showroom(req))
