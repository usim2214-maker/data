"""조향각을 이용한 예상 경로와 운전자 안내 문구.

실제 앱에서는 Pleos Vehicle SDK의 SteeringWheel.getCurrentSteeringWheelAngle /
registerSteeringWheelAngle 로 핸들 각도를 받는다. 핸들 각도가 없으면 영상만으로 안내한다.

부호 규칙
- 핸들 각도: 오른쪽(시계방향)으로 돌리면 +
- 후진할 때 핸들을 오른쪽으로 돌리면 차 뒤쪽이 오른쪽(+X)으로 휘어 간다
  ("후진할 땐 차 뒤가 가길 원하는 쪽으로 핸들을 돌린다")
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .detector import Alignment, SlotEstimate
from .vehicles import VehicleSpec


def wheel_to_curvature(steer_wheel_deg: float, v: VehicleSpec) -> float:
    delta = np.radians(np.clip(steer_wheel_deg / v.steering_ratio, -v.max_wheel_angle_deg, v.max_wheel_angle_deg))
    return float(np.tan(delta) / v.wheelbase)


def curvature_to_wheel(kappa: float, v: VehicleSpec) -> float:
    delta = np.degrees(np.arctan(kappa * v.wheelbase))
    delta = np.clip(delta, -v.max_wheel_angle_deg, v.max_wheel_angle_deg)
    return float(delta * v.steering_ratio)


def predict_path(v: VehicleSpec, steer_wheel_deg: float, dist_cm: float, n: int = 40) -> dict[str, np.ndarray]:
    """지금 핸들 각도로 dist_cm 만큼 후진할 때 뒷범퍼 좌/우 모서리와 중앙이 지나는 지면 좌표 (N,2)."""
    k = wheel_to_curvature(steer_wheel_deg, v)
    ya = -v.rear_overhang_cm  # 뒷바퀴 축 위치
    s = np.linspace(0.0, dist_cm, n)
    out = {}
    for name, x0 in (("left", -v.width_cm / 2), ("right", v.width_cm / 2), ("center", 0.0)):
        if abs(k) < 1e-7:
            out[name] = np.column_stack([np.full(n, x0), s])
            continue
        R = 1.0 / k
        phi = s * k
        vx, vy = x0 - R, 0.0 - ya
        px = R + vx * np.cos(phi) + vy * np.sin(phi)
        py = ya - vx * np.sin(phi) + vy * np.cos(phi)
        out[name] = np.column_stack([px, py])
    return out


def desired_steering(slot: SlotEstimate, v: VehicleSpec, lookahead_cm: float = 250.0) -> float:
    """뒷바퀴 축이 주차칸 중심선 위의 목표점(lookahead 앞)을 향하게 하는 핸들 각도 (pure pursuit)."""
    ya = -v.rear_overhang_cm
    yt = ya + lookahead_cm
    xt = float(slot.center_x_at(yt))
    ly = yt - ya
    kappa = 2.0 * xt / (xt * xt + ly * ly)
    return curvature_to_wheel(kappa, v)


@dataclass
class Guidance:
    level: str  # "ok" | "warn" | "stop" | "info"
    key: str  # 문구 종류 (흔들림 방지용)
    text_ko: str
    text_en: str
    arrow: int = 0  # -1 왼쪽, +1 오른쪽
    sub_ko: str = ""
    sub_en: str = ""
    desired_steer_deg: float | None = None
    current_steer_deg: float | None = None


def build_guidance(
    align: Alignment | None,
    slot: SlotEstimate | None,
    v: VehicleSpec,
    steer_deg: float | None = None,
    stop_cm: float = 30.0,
    slow_cm: float = 100.0,
    tolerance_deg: float = 30.0,
) -> Guidance:
    if align is None or slot is None:
        return Guidance("info", "search", "주차선을 찾고 있어요", "Looking for parking lines")

    end = align.end_dist_cm
    sub_ko = sub_en = ""
    if end is not None:
        sub_ko, sub_en = f"끝선까지 {max(end, 0) / 100:.1f}m", f"{max(end, 0) / 100:.1f}m to end line"

    if end is not None and end <= stop_cm:
        return Guidance("stop", "stop", "멈추세요", "STOP", 0, sub_ko, sub_en)

    # 끝선에 가까워질수록 목표점을 당겨서 남은 치우침을 더 적극적으로 바로잡는다
    lookahead = 250.0 if end is None else float(np.clip(end + 80.0, 150.0, 250.0))
    desired = desired_steering(slot, v, lookahead)
    near = end is not None and end <= slow_cm

    if steer_deg is not None:
        diff = desired - steer_deg
        if abs(diff) < tolerance_deg:
            if near:
                g = Guidance("ok", "slow", "천천히, 거의 다 왔어요", "Slowly, almost there")
            else:
                g = Guidance("ok", "keep", "좋아요, 그대로 후진하세요", "Good, keep reversing")
        else:
            arrow = 1 if diff > 0 else -1
            side_ko, side_en = ("오른쪽", "right") if arrow > 0 else ("왼쪽", "left")
            if abs(desired) < tolerance_deg and abs(steer_deg) >= tolerance_deg:
                g = Guidance("warn", f"unwind{arrow}", "핸들을 풀어 주세요", "Straighten the wheel", arrow)
            elif abs(diff) < 150:
                g = Guidance("warn", f"turn{arrow}s", f"핸들을 {side_ko}으로 조금 더",
                             f"Turn wheel a bit more {side_en}", arrow)
            else:
                g = Guidance("warn", f"turn{arrow}", f"핸들을 {side_ko}으로 돌리세요",
                             f"Turn wheel {side_en}", arrow)
    else:
        # 핸들 각도를 모를 때: 영상만으로 위치 상태를 알려 준다
        if align.status == "good":
            g = Guidance("ok", "centered", "좌우 균형이 좋아요", "Nicely centered")
        else:
            arrow = 1 if desired > 0 else -1
            side_ko, side_en = ("오른쪽", "right") if arrow > 0 else ("왼쪽", "left")
            g = Guidance("warn", f"hint{arrow}", f"차 뒤를 {side_ko}으로 보내세요",
                         f"Move the rear {side_en}", arrow)

    g.sub_ko, g.sub_en = sub_ko, sub_en
    g.desired_steer_deg, g.current_steer_deg = desired, steer_deg
    return g


class GuidanceStabilizer:
    """안내 문구가 프레임마다 바뀌어 깜빡이지 않도록, 새 문구가 몇 프레임 이어질 때만 바꾼다.
    '멈추세요'는 즉시 바꾼다."""

    def __init__(self, hold_frames: int = 4) -> None:
        self.hold = hold_frames
        self.current: Guidance | None = None
        self.pending_key: str | None = None
        self.count = 0

    def update(self, g: Guidance) -> Guidance:
        if self.current is None or g.level == "stop" or g.key == self.current.key:
            self.current, self.pending_key, self.count = g, None, 0
            return g
        if g.key == self.pending_key:
            self.count += 1
        else:
            self.pending_key, self.count = g.key, 1
        if self.count >= self.hold:
            self.current, self.pending_key, self.count = g, None, 0
            return g
        # 문구는 유지하되 거리 같은 보조 정보는 최신 값으로
        self.current.sub_ko, self.current.sub_en = g.sub_ko, g.sub_en
        return self.current
