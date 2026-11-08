"""1단계: YOLO 로 차량 감지 및 크롭."""
import logging

from PIL import Image

from app.config import Settings
from app.schemas import BoundingBox, Detection

log = logging.getLogger(__name__)

# COCO 클래스 중 차량으로 취급할 것
VEHICLE_CLASSES = {"car", "truck", "bus", "motorcycle"}


class VehicleDetector:
    def __init__(self, settings: Settings):
        self.settings = settings
        self._model = None
        if settings.detector_mode == "yolo":
            try:
                from ultralytics import YOLO  # 무거운 의존성이라 지연 import

                self._model = YOLO(settings.yolo_weights)
                log.info("YOLO loaded: %s", settings.yolo_weights)
            except Exception as e:  # ultralytics 미설치/가중치 다운로드 실패 시 시연이 멈추지 않도록
                log.warning("YOLO unavailable (%s) -> mock detector 로 동작", e)

    def detect(self, image: Image.Image) -> list[Detection]:
        if self._model is None:
            w, h = image.size
            return [Detection(label="car", confidence=1.0, bbox=BoundingBox(x1=0, y1=0, x2=w, y2=h))]

        result = self._model.predict(image, conf=self.settings.yolo_conf, verbose=False)[0]
        detections: list[Detection] = []
        for box in result.boxes:
            label = result.names[int(box.cls)]
            if label not in VEHICLE_CLASSES:
                continue
            x1, y1, x2, y2 = (int(v) for v in box.xyxy[0].tolist())
            detections.append(
                Detection(label=label, confidence=float(box.conf), bbox=BoundingBox(x1=x1, y1=y1, x2=x2, y2=y2))
            )
        return detections

    @staticmethod
    def pick_primary(detections: list[Detection]) -> Detection | None:
        """MVP: 화면에서 가장 크게 잡힌(=가장 가까운) 차량 1대만 카드로 만든다."""
        return max(detections, key=lambda d: d.bbox.area * d.confidence, default=None)

    @staticmethod
    def crop(image: Image.Image, bbox: BoundingBox, pad_ratio: float = 0.05) -> Image.Image:
        w, h = image.size
        pw, ph = int((bbox.x2 - bbox.x1) * pad_ratio), int((bbox.y2 - bbox.y1) * pad_ratio)
        return image.crop((max(0, bbox.x1 - pw), max(0, bbox.y1 - ph), min(w, bbox.x2 + pw), min(h, bbox.y2 + ph)))
