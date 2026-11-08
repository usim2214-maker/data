"""인식 파이프라인: 감지 -> 크롭 -> 식별 -> 키워드 요약 -> 카드 생성 -> 도감 등록."""
import uuid
from functools import lru_cache

from PIL import Image

from app.config import get_settings
from app.schemas import GeoPoint, RecognitionResponse, VehicleCard
from app.services.catalog import find_vehicle, slugify
from app.services.detector import VehicleDetector
from app.services.identifier import VehicleIdentifier
from app.services.store import JsonStore
from app.services.summarizer import CardSummarizer

MIN_IDENTITY_CONFIDENCE = 0.4


class RecognitionPipeline:
    def __init__(self):
        s = get_settings()
        self.settings = s
        self.detector = VehicleDetector(s)
        self.identifier = VehicleIdentifier(s)
        self.summarizer = CardSummarizer(s)
        self.store = JsonStore(s.data_dir)

    def run(
        self,
        image: Image.Image,
        *,
        keywords: list[str] | None = None,
        location: GeoPoint | None = None,
        filename_hint: str = "",
        register: bool = True,
    ) -> RecognitionResponse:
        image = image.convert("RGB")
        prefs = self.store.get_preferences()
        keywords = keywords or prefs.keywords

        detection = self.detector.pick_primary(self.detector.detect(image))
        if detection is None:
            return RecognitionResponse(detected=False, message="차량을 찾지 못했어요.")

        crop = self.detector.crop(image, detection.bbox)
        identity = self.identifier.identify(crop, hint=filename_hint)
        if identity is None or not identity.is_vehicle or identity.confidence < MIN_IDENTITY_CONFIDENCE:
            return RecognitionResponse(
                detected=True, detection=detection, identity=identity, message="차종을 확신할 수 없어요."
            )

        card_id = slugify(identity.brand, identity.model)
        crop_name = f"{card_id}-{uuid.uuid4().hex[:8]}.jpg"
        crop.save(self.settings.crops_dir / crop_name, quality=90)

        summary = self.summarizer.summarize(identity, keywords, prefs.language)
        ref = find_vehicle(identity.brand, identity.model)
        card = VehicleCard(
            id=card_id,
            brand=identity.brand,
            model=identity.model,
            body_type=identity.body_type,
            image_url=f"/static/crops/{crop_name}",
            one_liner=summary.one_liner,
            facts=summary.facts,
            rarity=(ref or {}).get("rarity", "common"),
        )

        is_new = False
        if register:
            _, is_new = self.store.register(card, location)
        return RecognitionResponse(
            detected=True,
            detection=detection,
            identity=identity,
            card=card,
            is_new_entry=is_new,
            message="새로운 차량을 도감에 등록했어요!" if is_new else "이미 수집한 차량이에요.",
        )


@lru_cache
def get_pipeline() -> RecognitionPipeline:
    return RecognitionPipeline()
