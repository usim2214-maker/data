"""후방 주차 가이드 데모.

예)
  python demo.py scenes                     # 합성 장면 여러 개 → output/scenes/*.png
  python demo.py video                      # 후진 주차 시뮬레이션 → output/reverse_parking.mp4
  python demo.py --lens pinhole video       # 일반 렌즈로
  python demo.py image photo.jpg --height 95 --pitch 28 --fisheye --focal 430   # 실제 사진
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np

from parking_guide.camera import CameraModel
from parking_guide.detector import AlignmentSmoother, SlotDetector
from parking_guide.guidance import GuidanceStabilizer, build_guidance, curvature_to_wheel, wheel_to_curvature
from parking_guide.overlay import GuideRenderer
from parking_guide.synth import CarPose, LotLayout, ParkingLotWorld, Photometric, pose_from_bumper
from parking_guide.vehicles import VehicleSpec, get_vehicle

# 이름: (자세, 바닥, 조명, 핸들 각도)
SCENES = {
    "01_centered": (CarPose(0, 0, 150), LotLayout(), Photometric(), 0.0),
    "02_turn_more_right": (CarPose(-30, -6, 160), LotLayout(), Photometric(), 40.0),
    "03_unwind": (CarPose(-6, -1.5, -150), LotLayout(), Photometric(), -220.0),
    "04_inside_good": (CarPose(3, 0.5, -300), LotLayout(), Photometric(), 0.0),
    "05_slow_near_end": (CarPose(2, 0.3, -390), LotLayout(), Photometric(), 0.0),
    "06_worn_lines": (CarPose(14, 3, 100), LotLayout(line_wear=0.6), Photometric(), -60.0),
    "08_retry": (CarPose(55, 1, -310), LotLayout(), Photometric(), 0.0),
    "07_dark_shadow": (CarPose(-8, 2, 80), LotLayout(shadow=True), Photometric(brightness=0.6, noise_sigma=7), None),
}


def make_camera(lens: str) -> CameraModel:
    return CameraModel.wide_angle() if lens == "wide" else CameraModel()


def debug_panel(det: SlotDetector, res) -> np.ndarray:
    bev = res.bev.copy()
    for m, col in ((res.mask, (0, 120, 255)), (res.end_mask, (255, 120, 0))):
        if m is not None:
            bev[m > 0] = (0.5 * bev[m > 0] + np.array(col) * 0.5).astype(np.uint8)
    b = det.bev
    for line in res.lines:
        p = [b.ground_to_pixels(line.x_at(y), y) for y in (line.y_min, line.y_max)]
        cv2.line(bev, tuple(map(int, p[0])), tuple(map(int, p[1])), (255, 0, 255), 3)
    return bev


def compose(frame: np.ndarray, det: SlotDetector, res) -> np.ndarray:
    dbg = debug_panel(det, res)
    h = frame.shape[0]
    dbg = cv2.resize(dbg, (int(dbg.shape[1] * h / dbg.shape[0]), h))
    return np.hstack([frame, dbg])


def run_scenes(args) -> None:
    out_dir = Path(args.out) / "scenes"
    out_dir.mkdir(parents=True, exist_ok=True)
    cam = make_camera(args.lens)
    vehicle = get_vehicle(args.vehicle)
    det = SlotDetector(cam, vehicle=vehicle)
    det.door_mode = args.door
    ren = GuideRenderer(cam, vehicle)
    print(f"{'scene':22s} {'GT off':>7s} {'est':>6s} {'GT yaw':>7s} {'est':>6s} {'GT end':>7s} {'est':>6s}  안내")
    for name, (pose, layout, photo, steer) in SCENES.items():
        img, gt = ParkingLotWorld(layout, seed=7).render(pose, cam, photo)
        res = det.analyze(img)
        g = build_guidance(res.alignment, res.slot, vehicle, steer)
        vis = ren.render(img, res.slot, res.alignment, g, steer)
        cv2.imwrite(str(out_dir / f"{name}.png"), compose(vis, det, res) if args.debug else vis)
        a = res.alignment
        fmt = lambda v: "   -" if v is None else f"{v:6.0f}"
        if a:
            print(f"{name:22s} {gt.perp_offset_cm:7.1f} {a.offset_cm:6.1f} {gt.yaw_deg:7.1f} {a.yaw_deg:6.2f} "
                  f"{fmt(gt.end_line_dist_cm):>7s} {fmt(a.end_dist_cm)}  {g.text_ko}")
        else:
            print(f"{name:22s} 검출 실패")
    print(f"저장: {out_dir}")


@dataclass
class DriverSim:
    """후진하는 차(자전거 모델)와 '완벽하지 않은' 운전자.

    운전자는 주차칸 중심을 따라가려 하지만 핸들을 늦게, 덜 돌린다. 그래서 안내 문구가 바뀌는 모습이 보인다.
    """

    vehicle: VehicleSpec
    bx: float = 70.0  # 주차칸 좌표계에서 뒷범퍼 중앙
    by: float = -330.0
    heading_deg: float = -16.0  # 후진 방향 (+ = s_x 쪽으로)
    steer_deg: float = 0.0
    gain: float = 0.55  # 필요한 만큼의 55%만 돌림
    lag: float = 0.12  # 프레임당 따라가는 비율

    def pose(self) -> CarPose:
        return pose_from_bumper(self.bx, self.by, self.heading_deg)

    def step(self, ds: float, advice_deg: float | None = None) -> None:
        """advice_deg: 앱이 안내한 핸들 각도. 안내가 나오면 운전자가 그쪽으로 고친다."""
        v = self.vehicle
        h = np.radians(self.heading_deg)
        # 운전자의 목표: 뒷바퀴 축이 2.5m 앞 주차칸 중심을 향하도록 (실제보다 덜 돌림)
        ov = v.rear_overhang_cm
        ax, ay = self.bx - ov * np.sin(h), self.by - ov * np.cos(h)
        tx, ty = 0.0, ay + 250.0
        rx, ry = tx - ax, ty - ay
        lat = rx * np.cos(h) - ry * np.sin(h)
        lon = rx * np.sin(h) + ry * np.cos(h)
        want = curvature_to_wheel(2 * lat / (lat * lat + lon * lon), v) * self.gain
        if advice_deg is not None:
            want = advice_deg
        self.steer_deg += (want - self.steer_deg) * self.lag
        k = wheel_to_curvature(self.steer_deg, v)
        # 뒷바퀴 축 이동 + 방향 회전, 뒷범퍼 위치 갱신
        ax += ds * np.sin(h)
        ay += ds * np.cos(h)
        h += ds * k
        self.heading_deg = float(np.degrees(h))
        self.bx, self.by = ax + ov * np.sin(h), ay + ov * np.cos(h)


def run_video(args) -> None:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    cam = make_camera(args.lens)
    vehicle = get_vehicle(args.vehicle)
    det = SlotDetector(cam, vehicle=vehicle)
    det.door_mode = args.door
    ren = GuideRenderer(cam, vehicle)
    # 끝선이 범퍼 밑으로 사라져도 이동 거리로 안내를 이어가도록 놓친 프레임을 넉넉히 허용
    smooth, stab = AlignmentSmoother(max_missed=60), GuidanceStabilizer()
    layout = LotLayout(line_wear=0.25)
    world = ParkingLotWorld(layout, seed=3)
    rng = np.random.default_rng(0)
    sim = DriverSim(vehicle)
    stop_by = layout.slot_length_cm - layout.line_width_cm - 25.0  # 끝선 25cm 앞에서 정지

    path = out / "reverse_parking.mp4"
    size = (cam.width * 2 if args.debug else cam.width, cam.height)
    writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), args.fps, size)
    n, hold = 0, 0
    last_slot = None
    while n < args.max_frames:
        img, _ = world.render(sim.pose(), cam, Photometric(), rng)
        res = det.analyze(img)
        a = smooth.update(res.alignment)
        if res.alignment is None:
            res.slot = last_slot  # 선이 안 보이는 마지막 구간은 마지막으로 본 주차칸을 이동 거리만큼 옮겨 쓴다
        else:
            last_slot = res.slot
        g = stab.update(build_guidance(a, res.slot, vehicle, sim.steer_deg))
        vis = ren.render(img, res.slot, a, g, sim.steer_deg)
        frame = compose(vis, det, res) if args.debug else vis
        writer.write(cv2.resize(frame, size) if args.debug else frame)
        n += 1
        if sim.by >= stop_by:
            hold += 1
            if hold > args.fps * 1.5:  # 멈춘 화면 1.5초
                break
        else:
            # 운전자는 노란 안내(핸들 방향)가 뜰 때만 안내를 따른다
            sim.step(args.speed, g.desired_steer_deg if g.level == "warn" else None)
            smooth.advance(args.speed)  # 실제 차량: 속도 신호 × 시간
            if last_slot is not None:
                last_slot = last_slot.shifted(args.speed)
    writer.release()
    print(f"저장: {path} ({n} frames, 최종 치우침 {sim.bx:.1f}cm, 각도 {sim.heading_deg:.1f}°)")


def run_image(args) -> None:
    img = cv2.imread(args.path)
    if img is None:
        raise SystemExit(f"이미지를 읽을 수 없어요: {args.path}")
    h, w = img.shape[:2]
    focal = args.focal or (0.336 if args.fisheye else 0.4375) * w
    cam = CameraModel(width=w, height=h, focal_px=focal, mount_height_cm=args.height,
                      pitch_deg=args.pitch, fisheye=args.fisheye)
    vehicle = get_vehicle(args.vehicle)
    det = SlotDetector(cam, vehicle=vehicle)
    det.door_mode = args.door
    res = det.analyze(img)
    g = build_guidance(res.alignment, res.slot, vehicle, args.steer)
    vis = GuideRenderer(cam, vehicle).render(img, res.slot, res.alignment, g, args.steer)
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
    ap.add_argument("--lens", choices=["wide", "pinhole"], default="wide", help="합성 카메라 렌즈")
    ap.add_argument("--debug", action="store_true", help="조감도/마스크 패널을 옆에 붙임")
    ap.add_argument("--door", choices=["center", "driver", "passenger"], default="center",
                    help="하차 모드: 가운데 / 운전석 여유 / 동승석·뒷좌석 여유 (문콕 방지)")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("scenes")
    v = sub.add_parser("video")
    v.add_argument("--fps", type=int, default=20)
    v.add_argument("--speed", type=float, default=4.0, help="프레임당 후진 거리 cm")
    v.add_argument("--max-frames", type=int, default=400)
    im = sub.add_parser("image")
    im.add_argument("path")
    im.add_argument("--height", type=float, default=100.0, help="카메라 설치 높이 cm")
    im.add_argument("--pitch", type=float, default=30.0, help="카메라가 아래로 숙인 각도")
    im.add_argument("--focal", type=float, default=None, help="초점거리 px")
    im.add_argument("--fisheye", action="store_true", help="광각(어안) 렌즈")
    im.add_argument("--steer", type=float, default=None, help="핸들 각도(도, 오른쪽 +)")
    args = ap.parse_args()
    {"scenes": run_scenes, "video": run_video, "image": run_image}[args.cmd](args)


if __name__ == "__main__":
    main()
