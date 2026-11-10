"""Android 앱(에뮬레이터) 시연용 후방카메라 프레임을 만든다.

Pleos SDK에 카메라 영상 API가 없어서, 에뮬레이터에서는 이 합성 프레임을 카메라 대신 재생한다.
안내 화면(오버레이)은 앱이 그리므로 여기서는 원본 영상만 저장한다.

  cd parking_guide
  python tools/export_demo_frames.py            # → android/app/src/main/assets/demo/frame_000.png ...
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from demo import DriverSim  # noqa: E402
from parking_guide.camera import CameraModel  # noqa: E402
from parking_guide.synth import LotLayout, ParkingLotWorld, Photometric  # noqa: E402
from parking_guide.vehicles import get_vehicle  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ROOT / "android/app/src/main/assets/demo"))
    ap.add_argument("--frames", type=int, default=150)
    ap.add_argument("--speed", type=float, default=5.0, help="프레임당 후진 거리 cm (10fps 재생 기준 약 1.8km/h)")
    ap.add_argument("--wear", type=float, default=0.2)
    args = ap.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    # 앱의 CameraModel.wideAngle(640, 360)과 같은 카메라
    cam = CameraModel.wide_angle(width=640, height=360, focal_px=215.0)
    layout = LotLayout(line_wear=args.wear)
    world = ParkingLotWorld(layout, seed=3)
    sim = DriverSim(get_vehicle("sonata"), gain=0.8, lag=0.15)
    rng = np.random.default_rng(0)
    stop_by = layout.slot_length_cm - layout.line_width_cm - 25.0
    n = 0
    for n in range(args.frames):
        img, _ = world.render(sim.pose(), cam, Photometric(), rng)
        cv2.imwrite(str(out / f"frame_{n:03d}.png"), img)
        if sim.by >= stop_by:
            break
        sim.step(args.speed)
    print(f"{n + 1}장 저장: {out}")


if __name__ == "__main__":
    main()
