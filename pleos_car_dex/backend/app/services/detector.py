"""1단계: YOLOv8 로 차량 감지 및 크롭 (이미지 1장 / 주행 영상)."""
import logging
from dataclasses import dataclass
from pathlib import Path

from PIL import Image

from app.config import Settings
from app.schemas import BoundingBox, Detection

log = logging.getLogger(__name__)

# COCO 클래스 중 차량으로 취급할 것
VEHICLE_CLASSES = {"car", "truck", "bus", "motorcycle"}
COCO_VEHICLE_IDS = [2, 3, 5, 7]


@dataclass
class TrackedVehicle:
    track_id: int
    frame: Image.Image  # 이 차량이 가장 크게 잡힌 프레임
    detection: Detection
    frame_index: int


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

    # ---------------- 영상 입력 ----------------
    def detect_video(self, path: Path, sample_fps: float, max_vehicles: int) -> tuple[list[TrackedVehicle], int]:
        """영상에서 프레임을 샘플링해 차량을 추적(ID 부여)하고, 차량별 '가장 잘 보인' 프레임 1장을 고른다.

        반환: (가까운/크게 보인 순 상위 max_vehicles 대, 분석한 프레임 수)
        """
        import cv2  # opencv-python-headless

        cap = cv2.VideoCapture(str(path))
        if not cap.isOpened():
            raise ValueError("영상을 열 수 없습니다.")
        src_fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
        step = max(1, round(src_fps / sample_fps))

        best: dict[int, TrackedVehicle] = {}
        idx = analyzed = 0
        try:
            while True:
                ok, bgr = cap.read()
                if not ok:
                    break
                if idx % step == 0:
                    frame = Image.fromarray(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB))
                    for track_id, det in self._track(frame, first=analyzed == 0):
                        cur = best.get(track_id)
                        if cur is None or det.bbox.area * det.confidence > cur.detection.bbox.area * cur.detection.confidence:
                            best[track_id] = TrackedVehicle(track_id, frame, det, idx)
                    analyzed += 1
                idx += 1
        finally:
            cap.release()

        ranked = sorted(best.values(), key=lambda t: t.detection.bbox.area * t.detection.confidence, reverse=True)
        return ranked[:max_vehicles], analyzed

    def _track(self, frame: Image.Image, first: bool) -> list[tuple[int, Detection]]:
        if self._model is None:
            # mock: 프레임 전체를 같은 차량(ID 0) 1대로 간주 -> 첫 프레임이 대표로 남는다
            w, h = frame.size
            return [(0, Detection(label="car", confidence=1.0, bbox=BoundingBox(x1=0, y1=0, x2=w, y2=h)))]

        # persist=False 로 첫 프레임에서 트래커 초기화, 이후 같은 ID 유지
        result = self._model.track(
            frame, persist=not first, conf=self.settings.yolo_conf, classes=COCO_VEHICLE_IDS, verbose=False
        )[0]
        out: list[tuple[int, Detection]] = []
        if result.boxes.id is None:
            return out
        for box, tid in zip(result.boxes, result.boxes.id.int().tolist()):
            x1, y1, x2, y2 = (int(v) for v in box.xyxy[0].tolist())
            det = Detection(
                label=result.names[int(box.cls)], confidence=float(box.conf), bbox=BoundingBox(x1=x1, y1=y1, x2=x2, y2=y2)
            )
            out.append((tid, det))
        return out
