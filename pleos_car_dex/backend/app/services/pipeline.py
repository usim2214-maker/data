"""인식 파이프라인: 감지 -> 크롭 -> 식별 -> 키워드 요약 -> 카드 생성 -> 도감 등록."""
import uuid
from datetime import datetime, timezone
from functools import lru_cache
from pathlib import Path

from PIL import Image

from app.config import get_settings
from app.schemas import Detection, GeoPoint, RecognitionResponse, VehicleCard, VideoRecognitionResponse
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

    # ---------------- 이미지 1장 (앱이 영상에서 프레임을 뽑아 주기적으로 전송) ----------------
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
        detection = self.detector.pick_primary(self.detector.detect(image))
        if detection is None:
            return RecognitionResponse(detected=False, message="차량을 찾지 못했어요.")
        return self._process(image, detection, keywords, location, filename_hint, register)

    # ---------------- 주행 영상 파일 통째로 ----------------
    def run_video(
        self,
        path: Path,
        *,
        keywords: list[str] | None = None,
        location: GeoPoint | None = None,
        filename_hint: str = "",
        register: bool = True,
    ) -> VideoRecognitionResponse:
        tracked, analyzed = self.detector.detect_video(
            path, self.settings.video_sample_fps, self.settings.video_max_vehicles
        )
        results = [
            self._process(t.frame, t.detection, keywords, location, filename_hint if len(tracked) == 1 else "", register)
            for t in tracked
        ]
        return VideoRecognitionResponse(frames_analyzed=analyzed, vehicles=results)

    # ---------------- 공통: 크롭 -> 식별 -> 요약 -> 도감 ----------------
    def _process(
        self,
        image: Image.Image,
        detection: Detection,
        keywords: list[str] | None,
        location: GeoPoint | None,
        filename_hint: str,
        register: bool,
    ) -> RecognitionResponse:
        prefs = self.store.get_preferences()
        keywords = keywords or prefs.keywords

        crop = self.detector.crop(image, detection.bbox)
        identity = self.identifier.identify(crop, hint=filename_hint)
        if identity is None or not identity.is_vehicle or identity.confidence < MIN_IDENTITY_CONFIDENCE:
            return RecognitionResponse(
                detected=True, detection=detection, identity=identity, message="차종을 확신할 수 없어요."
            )

        card_id = slugify(identity.brand, identity.model)
        card = self._reusable_card(card_id, keywords)
        if card is None:
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
            message=("새로운 차량을 도감에 등록했어요!" if is_new else "이미 수집한 차량이에요.") if register else "카드를 만들었어요 (도감 미등록)",
        )

    def _reusable_card(self, card_id: str, keywords: list[str]) -> VehicleCard | None:
        """방금 본 차량(같은 키워드)이면 저장된 카드를 재사용해 LLM 호출을 생략한다."""
        entry = self.store.get_entry(card_id)
        if entry is None or [f.keyword for f in entry.card.facts] != keywords:
            return None
        age = (datetime.now(timezone.utc) - entry.last_seen_at).total_seconds()
        return entry.card if age <= self.settings.reuse_card_within_sec else None


@lru_cache
def get_pipeline() -> RecognitionPipeline:
    return RecognitionPipeline()
