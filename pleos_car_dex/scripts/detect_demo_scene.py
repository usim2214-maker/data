"""시연 영상용: 백엔드의 실제 YOLOv8 감지 코드(app.services.detector)로 도로 장면을 분석해 JSON 저장.
사용: python scripts/detect_demo_scene.py <이미지> <출력 json>   (backend 의존성 + requirements-vision 필요)
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "backend"))

from PIL import Image  # noqa: E402

from app.config import Settings  # noqa: E402
from app.services.detector import VehicleDetector  # noqa: E402

img_path, out_path = Path(sys.argv[1]), Path(sys.argv[2])
settings = Settings(detector_mode="yolo", yolo_weights=str(ROOT / "backend" / "yolov8n.pt"), yolo_conf=0.25)
detector = VehicleDetector(settings)
if detector._model is None:
    sys.exit("YOLO 를 불러오지 못했어요 (pip install -r backend/requirements-vision.txt)")

image = Image.open(img_path).convert("RGB")
dets = detector.detect(image)
primary = detector.pick_primary(dets)
crop = detector.crop(image, primary.bbox) if primary else None
if crop:
    crop.save(out_path.with_name("road_scene_crop.png"))
out = {
    "image": img_path.name, "width": image.width, "height": image.height, "model": "yolov8n.pt",
    "detections": [d.model_dump() for d in dets],
    "primary": primary.model_dump() if primary else None,
}
out_path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps({"detections": [(d.label, round(d.confidence, 2)) for d in dets],
                  "primary": primary and (primary.label, round(primary.confidence, 2))}, ensure_ascii=False))
