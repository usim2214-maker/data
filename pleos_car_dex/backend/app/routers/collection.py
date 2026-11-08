from fastapi import APIRouter, Depends, HTTPException

from app.schemas import CollectionResponse
from app.services import regions, synergy
from app.services.pipeline import RecognitionPipeline, get_pipeline

router = APIRouter(prefix="/api/v1/collection", tags=["collection"])


@router.get("", response_model=CollectionResponse)
def list_collection(pipeline: RecognitionPipeline = Depends(get_pipeline)):
    entries = pipeline.store.list_entries()
    featured_id = pipeline.store.get_showroom().card_id
    featured = next((e.card for e in entries if e.card.id == featured_id), None)
    score = synergy.compute(
        entries,
        featured_brand=featured.brand if featured else None,
        stamps=pipeline.store.get_stamps(),
        regions=regions.all_regions(),
        locate=regions.locate,
        hidden_points=[h.points for h in pipeline.store.get_hidden().values()],
    )
    return CollectionResponse(total=len(entries), entries=entries, synergy=score)


@router.delete("/{card_id}", status_code=204)
def delete_entry(card_id: str, pipeline: RecognitionPipeline = Depends(get_pipeline)):
    if not pipeline.store.delete(card_id):
        raise HTTPException(status_code=404, detail="도감에 없는 차량입니다.")
