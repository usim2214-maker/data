"""인식 파이프라인: 감지 -> 크롭 -> 차종 판별 -> 차량 정보 목록에서 고른 항목 -> 카드 생성 -> 도감 등록.

- 개인정보: 크롭 이미지는 메모리에서만 쓰고 저장하지 않는다 (SAVE_CROPS=true 일 때만 저장)
- 비용/지연: 같은 차를 짧은 시간 안에 다시 보면 차종 판별(Claude 비전)을 재사용
"""
import time
import uuid
from datetime import datetime, timezone
from functools import lru_cache
from pathlib import Path

from PIL import Image

from app.config import get_settings
from app.schemas import (
    Detection,
    GeoPoint,
    RecognitionResponse,
    VehicleCard,
    VehicleIdentity,
    VideoRecognitionResponse,
)
from app.services import mycar
from app.services.catalog import find_vehicle
from app.services.detector import VehicleDetector, _iou
from app.services.identifier import VehicleIdentifier
from app.services.store import JsonStore
from app.services.summarizer import CardSummarizer

MIN_IDENTITY_CONFIDENCE = 0.4
HASH_MAX_DISTANCE = 6  # 64비트 평균 해시 해밍 거리 (작을수록 엄격)
HASH_NEAR_DISTANCE = 14  # 위치가 겹칠 때 허용하는 거리


def average_hash(img: Image.Image) -> int:
    """8x8 평균 해시: 같은 차를 조금 다른 프레임에서 봐도 비슷한 값"""
    small = img.convert("L").resize((8, 8))
    px = list(small.tobytes())  # 8비트 그레이스케일 64픽셀 (getdata 는 Pillow 14 에서 제거 예정)
    avg = sum(px) / len(px)
    return sum(1 << i for i, p in enumerate(px) if p >= avg)


class RecognitionPipeline:
    def __init__(self):
        s = get_settings()
        self.settings = s
        self.detector = VehicleDetector(s)
        self.identifier = VehicleIdentifier(s)
        self.summarizer = CardSummarizer(s)
        self.store = JsonStore(s.data_dir)
        self.on_twin = None  # 쌍둥이 차를 만났을 때 콜백 (deps 에서 원장 기록 연결)
        self.on_card = None  # 카드를 만들 때 콜백 -> 오늘의 차 보너스 점수 반환 (deps 에서 연결)
        # 최근 판별 결과 캐시: (시각, 박스, 이미지 해시, 판별 결과)
        self._recent: list[tuple[float, Detection, int, VehicleIdentity]] = []

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
        detections = self.detector.detect(image)
        detection = self.detector.pick_primary(detections)
        if detection is None:
            return RecognitionResponse(detected=False, message="차량을 찾지 못했어요.")
        res = self._process(image, detection, keywords, location, filename_hint, register)
        res.detections = detections
        return res

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
        identity = self._identify_cached(crop, detection, filename_hint)
        if identity is None or not identity.is_vehicle or identity.confidence < MIN_IDENTITY_CONFIDENCE:
            return RecognitionResponse(
                detected=True, detection=detection, identity=identity, message="차종을 확신할 수 없어요."
            )

        ref = find_vehicle(identity.brand, identity.model)
        if ref is None:  # 정보 목록(28종)에 없는 차량은 카드로 만들지 않는다
            return RecognitionResponse(
                detected=True, detection=detection, identity=identity,
                message=f"{identity.brand} {identity.model} — 도감에 없는 차량이에요",
            )
        if self.settings.save_crops:  # 기본은 저장하지 않음 (개인정보)
            crop.save(self.settings.crops_dir / f"{ref['id']}-{uuid.uuid4().hex[:8]}.jpg", quality=90)
        return self._card_from(ref, identity, detection, keywords, location, register)

    def _card_from(self, ref: dict, identity: VehicleIdentity, detection: Detection | None,
                   keywords: list[str] | None, location: GeoPoint | None, register: bool) -> RecognitionResponse:
        prefs = self.store.get_preferences()
        keywords = keywords or prefs.keywords
        card_id = ref["id"]
        card = self._reusable_card(card_id, keywords)
        if card is None:
            image_url = None
            summary = self.summarizer.summarize(identity, keywords, prefs.language)
            card = VehicleCard(
                id=card_id,
                brand=ref["brand"],
                model=ref["model"],
                body_type=ref["body_type"],
                image_url=image_url,
                one_liner=summary.one_liner,
                facts=summary.facts,
                rarity=ref.get("rarity", "common"),
            )

        is_new = False
        if register:
            _, is_new = self.store.register(card, location)
        my_car = prefs.my_car_id
        twin = bool(my_car) and card_id == my_car
        if twin and register and self.on_twin:
            self.on_twin(card)  # 쌍둥이 보너스 (원장 기록)
        message = ("새로운 차량을 도감에 등록했어요!" if is_new else "이미 수집한 차량이에요.") if register else "카드를 만들었어요 (도감 미등록)"
        daily_bonus = self.on_card(card) if register and self.on_card else 0
        if daily_bonus:
            message = f"오늘의 차 {card.model} 발견! +{daily_bonus}"
        if twin:
            message = f"내 차와 같은 {card.model}를 만났어요! 쌍둥이 +{mycar.TWIN_POINTS}"
        return RecognitionResponse(
            detected=True,
            detection=detection,
            identity=identity,
            card=mycar.annotate(card, my_car),
            is_new_entry=is_new,
            twin=twin,
            daily_bonus=daily_bonus,
            message=message,
        )

    def _cache_enabled(self) -> bool:
        return self.settings.identifier_mode == "llm" and self.settings.llm_enabled

    def _identify_cached(self, crop: Image.Image, detection: Detection, hint: str) -> VehicleIdentity | None:
        """같은 차(위치가 크게 겹치거나 모습이 비슷)를 최근에 판별했다면 재사용 — Claude 비전 호출 절약"""
        if not self._cache_enabled():  # mock 판별은 비용이 없으므로 캐시하지 않음
            return self.identifier.identify(crop, hint=hint)
        now = time.monotonic()
        window = self.settings.reuse_identity_within_sec
        self._recent = [r for r in self._recent if now - r[0] <= window]
        h = average_hash(crop)
        for t, det, rh, ident in reversed(self._recent):
            dist = bin(h ^ rh).count("1")
            # 모습이 거의 같거나, 위치가 크게 겹치면서 모습도 꽤 비슷할 때만 같은 차 (앞차가 바뀌는 경우 오인 방지)
            if dist <= HASH_MAX_DISTANCE or (_iou(det.bbox, detection.bbox) >= 0.6 and dist <= HASH_NEAR_DISTANCE):
                return ident
        ident = self.identifier.identify(crop, hint=hint)
        if ident is not None and ident.is_vehicle:
            self._recent.append((now, detection, h, ident))
        return ident

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
