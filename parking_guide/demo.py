"""후방 주차 가이드 데모.

예)
  python demo.py scenes            # 합성 장면 여러 개 → output/scenes/*.png
  python demo.py video             # 후진 주차 시뮬레이션 → output/reverse_parking.mp4
  python demo.py image photo.jpg --height 95 --pitch 28 --focal 600   # 실제 사진
"""
from __future__ import annotations

import argparse
from pathlib import Path

import cv2
import numpy as np

from parking_guide.camera import CameraModel
from parking_guide.detector import AlignmentSmoother, SlotDetector
from parking_guide.overlay import GuideRenderer
from parking_guide.synth import CarPose, LotLayout, ParkingLotWorld, Photometric
from parking_guide.vehicles import get_vehicle

SCENES = {
    "01_centered": (CarPose(0, 0, 150), LotLayout(), Photometric()),
    "02_shift_right": (CarPose(28, 0, 120), LotLayout(), Photometric()),
    "03_shift_left_angled": (CarPose(-20, -5, 60), LotLayout(), Photometric()),
    "04_inside_slot": (CarPose(6, 1, -250), LotLayout(), Photometric()),
    "05_worn_lines": (CarPose(12, 3, 100), LotLayout(line_wear=0.6), Photometric()),
    "06_dark_shadow": (CarPose(-8, 2, 80), LotLayout(shadow=True), Photometric(brightness=0.6, noise_sigma=7)),
    "07_far_offset": (CarPose(-70, 9, 280), LotLayout(), Photometric()),
}


def debug_panel(det: SlotDetector, res) -> np.ndarray:
    bev = res.bev.copy()
    bev[res.mask > 0] = (0.5 * bev[res.mask > 0] + (0, 120, 255) * np.array(0.5)).astype(np.uint8)
    s, b = det.bev.px_per_cm, det.bev
    for line in res.lines:
        p0 = ((line.x_at(line.y_min) - b.x_min_cm) * s, (b.y_max_cm - line.y_min) * s)
        p1 = ((line.x_at(line.y_max) - b.x_min_cm) * s, (b.y_max_cm - line.y_max) * s)
        cv2.line(bev, tuple(map(int, p0)), tuple(map(int, p1)), (255, 0, 255), 3)
    return bev


def compose(frame: np.ndarray, det: SlotDetector, res) -> np.ndarray:
    dbg = debug_panel(det, res)
    h = frame.shape[0]
    dbg = cv2.resize(dbg, (int(dbg.shape[1] * h / dbg.shape[0]), h))
    return np.hstack([frame, dbg])


def run_scenes(args) -> None:
    out_dir = Path(args.out) / "scenes"
    out_dir.mkdir(parents=True, exist_ok=True)
    cam = CameraModel()
    vehicle = get_vehicle(args.vehicle)
    det = SlotDetector(cam, vehicle=vehicle)
    ren = GuideRenderer(cam, vehicle)
    print(f"{'scene':24s} {'GT offset':>9s} {'est':>7s} {'GT yaw':>7s} {'est':>6s}  status")
    for name, (pose, layout, photo) in SCENES.items():
        img, gt = ParkingLotWorld(layout, seed=7).render(pose, cam, photo)
        res = det.analyze(img)
        vis = ren.render(img, res.slot, res.alignment)
        cv2.imwrite(str(out_dir / f"{name}.png"), compose(vis, det, res) if args.debug else vis)
        a = res.alignment
        est = (f"{a.offset_cm:7.1f} {gt.yaw_deg:7.1f} {a.yaw_deg:6.2f}  {a.status}" if a
               else "   miss")
        print(f"{name:24s} {gt.lateral_offset_cm:9.1f} {est}")
    print(f"저장: {out_dir}")


def run_video(args) -> None:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    cam = CameraModel()
    vehicle = get_vehicle(args.vehicle)
    det = SlotDetector(cam, vehicle=vehicle)
    ren = GuideRenderer(cam, vehicle)
    smooth = AlignmentSmoother()
    world = ParkingLotWorld(LotLayout(line_wear=0.25), seed=3)
    rng = np.random.default_rng(0)

    n = args.frames
    # 처음엔 오른쪽으로 치우치고 비스듬하게 접근 → 운전자가 핸들을 풀며 중앙에 맞춰 들어가는 궤적
    t = np.linspace(0, 1, n)
    entry = 320 - 640 * t
    offset = 45 * (1 - t) ** 1.6 - 4 * np.sin(np.pi * t)
    yaw = 12 * (1 - t) ** 2.2 - 1.0 * np.sin(np.pi * t)

    path = out / "reverse_parking.mp4"
    size = (cam.width * 2 if args.debug else cam.width, cam.height)
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), args.fps, size)
    for i in range(n):
        img, _ = world.render(CarPose(float(offset[i]), float(yaw[i]), float(entry[i])), cam, Photometric(), rng)
        res = det.analyze(img)
        a = smooth.update(res.alignment)
        vis = ren.render(img, res.slot, a)
        frame = compose(vis, det, res) if args.debug else vis
        if args.debug:
            frame = cv2.resize(frame, size)
        writer.write(frame)
    writer.release()
    print(f"저장: {path} ({n} frames)")


def run_image(args) -> None:
    img = cv2.imread(args.path)
    if img is None:
        raise SystemExit(f"이미지를 읽을 수 없어요: {args.path}")
    h, w = img.shape[:2]
    cam = CameraModel(width=w, height=h, focal_px=args.focal or 0.4375 * w,
                      mount_height_cm=args.height, pitch_deg=args.pitch)
    vehicle = get_vehicle(args.vehicle)
    det = SlotDetector(cam, vehicle=vehicle)
    res = det.analyze(img)
    vis = GuideRenderer(cam, vehicle).render(img, res.slot, res.alignment)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    dst = out / (Path(args.path).stem + "_guide.png")
    cv2.imwrite(str(dst), compose(vis, det, res) if args.debug else vis)
    print(res.alignment or "주차칸을 찾지 못했어요")
    print(f"저장: {dst}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default="output")
    ap.add_argument("--vehicle", default="sonata", help="프리셋 이름 또는 '폭x길이'(cm)")
    ap.add_argument("--debug", action="store_true", help="조감도/마스크 패널을 옆에 붙임")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("scenes")
    v = sub.add_parser("video")
    v.add_argument("--frames", type=int, default=120)
    v.add_argument("--fps", type=int, default=20)
    im = sub.add_parser("image")
    im.add_argument("path")
    im.add_argument("--height", type=float, default=100.0, help="카메라 설치 높이 cm")
    im.add_argument("--pitch", type=float, default=30.0, help="카메라가 아래로 숙인 각도")
    im.add_argument("--focal", type=float, default=None, help="초점거리 px")
    args = ap.parse_args()
    {"scenes": run_scenes, "video": run_video, "image": run_image}[args.cmd](args)


if __name__ == "__main__":
    main()
