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


def _move_points(pts: np.ndarray, k: float, s: float, ya: float) -> np.ndarray:
    """차체 위 점들을 '뒷바퀴 축이 s만큼 후진(곡률 k)'한 뒤의 위치로 옮긴다."""
    pts = np.asarray(pts, np.float64)
    if abs(k) < 1e-7:
        return pts + np.array([0.0, s])
    R, phi = 1.0 / k, s * k
    vx, vy = pts[:, 0] - R, pts[:, 1] - ya
    return np.column_stack([R + vx * np.cos(phi) + vy * np.sin(phi), ya - vx * np.sin(phi) + vy * np.cos(phi)])


@dataclass
class FinalPrediction:
    """지금 핸들 각도를 그대로 유지했을 때 끝선 앞에서 멈춘 모습."""

    corners: np.ndarray  # (4,2) 뒤왼쪽, 뒤오른쪽, 앞오른쪽, 앞왼쪽 (현재 차량 좌표계)
    clear_left_cm: float
    clear_right_cm: float
    offset_cm: float  # + = 주차칸 중심보다 오른쪽 (뒷범퍼 위치)
    yaw_deg: float  # 주차칸 축 대비 남는 각도
    travel_cm: float
    front_offset_cm: float = 0.0  # 앞범퍼 위치의 치우침

    @property
    def cost(self) -> float:
        """목표에서 벗어난 정도 (작을수록 좋음).

        마지막 1~2m에서는 차를 옆으로 옮길 수 없어서, 위치 오차를 줄이려고 차를 비틀면 오히려 나쁘다.
        그래서 '비뚤어짐'(앞뒤 치우침 차이)에 위치 오차보다 큰 가중치를 준다.
        """
        center = 0.5 * (self.offset_cm + self.front_offset_cm)
        skew = self.front_offset_cm - self.offset_cm
        pen = max(0.0, -self.clear_left_cm) + max(0.0, -self.clear_right_cm)
        return center ** 2 + 4.0 * skew ** 2 + 20.0 * pen ** 2

    @property
    def status(self) -> str:
        if min(self.clear_left_cm, self.clear_right_cm) < 0:
            return "danger"
        if abs(self.offset_cm) < 8 and abs(self.yaw_deg) < 1.5:
            return "good"
        return "adjust"


def predict_final(slot: SlotEstimate, v: VehicleSpec, steer_deg: float,
                  end_dist_cm: float | None, stop_margin_cm: float = 25.0,
                  default_travel_cm: float = 300.0) -> FinalPrediction:
    k = wheel_to_curvature(steer_deg, v)
    ya = -v.rear_overhang_cm
    travel = default_travel_cm if end_dist_cm is None else max(0.0, end_dist_cm - stop_margin_cm)
    hw, L = v.width_cm / 2, v.length_cm
    body = np.array([[-hw, 0.0], [hw, 0.0], [hw, -L], [-hw, -L], [0.0, 0.0], [0.0, -L]])
    moved = _move_points(body, k, travel, ya)
    corners, rear_c, front_c = moved[:4], moved[4], moved[5]

    cos_t = float(np.cos(np.arctan(slot.slope)))

    def side_clear(p):
        left_in = slot.left.x_at(p[1]) + slot.left.width_cm / 2
        right_in = slot.right.x_at(p[1]) - slot.right.width_cm / 2
        return (p[0] - left_in) * cos_t, (right_in - p[0]) * cos_t

    cl = min(side_clear(corners[0])[0], side_clear(corners[3])[0])
    cr = min(side_clear(corners[1])[1], side_clear(corners[2])[1])
    offset = float((rear_c[0] - slot.target_x_at(rear_c[1])) * cos_t)
    front_off = float((front_c[0] - slot.target_x_at(front_c[1])) * cos_t)
    axis = rear_c - front_c  # 차의 후진 방향
    car_ang = np.degrees(np.arctan2(axis[0], axis[1]))
    yaw = float(slot.yaw_deg - car_ang)
    return FinalPrediction(corners, float(cl), float(cr), offset, yaw, travel, front_off)


def best_constant_steering(slot: SlotEstimate, v: VehicleSpec, end_dist_cm: float | None) -> float:
    """핸들을 그 각도로 고정한 채 끝까지 갔을 때 가장 반듯하게 가운데 멈추는 핸들 각도.

    고스트카(지금 핸들 유지 시 예상 위치)와 같은 모델을 쓰므로, 화면 안내와 고스트카가 서로 다른 말을 하지 않는다.
    """
    full = v.max_steer_wheel_deg
    grid = np.arange(-full, full + 1e-6, 15.0)
    costs = [predict_final(slot, v, w, end_dist_cm).cost for w in grid]
    best = float(grid[int(np.argmin(costs))])
    for step in (5.0, 1.0):  # 주변을 촘촘히 다시 찾기
        cand = np.arange(best - 3 * step, best + 3 * step + 1e-6, step)
        cand = cand[np.abs(cand) <= full]
        costs = [predict_final(slot, v, w, end_dist_cm).cost for w in cand]
        best = float(cand[int(np.argmin(costs))])
    return best


def desired_steering(slot: SlotEstimate, v: VehicleSpec, lookahead_cm: float = 250.0) -> float:
    """뒷바퀴 축이 주차칸 중심선 위의 목표점(lookahead 앞)을 향하게 하는 핸들 각도 (pure pursuit)."""
    ya = -v.rear_overhang_cm
    yt = ya + lookahead_cm
    xt = float(slot.target_x_at(yt))
    ly = yt - ya
    kappa = 2.0 * xt / (xt * xt + ly * ly)
    return curvature_to_wheel(kappa, v)


def turns_phrase_ko(deg: float) -> str:
    """핸들 회전량을 초보도 감이 오는 '바퀴' 단위로 바꾼다."""
    a = abs(deg)
    if a < 60:
        return "살짝"
    if a < 135:
        return "1/4바퀴"
    if a < 225:
        return "반 바퀴"
    if a < 315:
        return "3/4바퀴"
    if a < 450:
        return "한 바퀴"
    return "한 바퀴 반"


def turns_phrase_en(deg: float) -> str:
    a = abs(deg)
    for lim, txt in ((60, "slightly"), (135, "a quarter turn"), (225, "half a turn"),
                     (315, "three quarters"), (450, "one turn")):
        if a < lim:
            return txt
    return "one and a half turns"


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


LOW_CONFIDENCE = 0.45  # 이보다 낮으면 핸들 안내를 하지 않는다
GHOST_CONFIDENCE = 0.5  # 고스트카(예측)는 이 이상일 때만
FINAL_ZONE_CM = 250.0  # 끝선까지 이 거리 안쪽을 마무리 구간으로 본다


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

    if slot.confidence < LOW_CONFIDENCE:
        # 확실하지 않을 땐 확실하지 않다고 말한다: 핸들 안내 대신 직접 확인을 요청
        return Guidance("info", "lowconf", "주차선이 희미해요", "Lines are faint", 0,
                        "직접 확인하며 천천히 후진하세요", "Check around and reverse slowly")

    near = end is not None and end <= slow_cm
    final_zone = end is not None and end <= FINAL_ZONE_CM

    if final_zone:
        # 마무리 구간: 남은 거리가 짧아 '지금 핸들 유지' 모델이 잘 맞는다. 고스트카와 같은 기준으로 판단
        desired = best_constant_steering(slot, v, end)
        if predict_final(slot, v, desired, end).status == "danger":
            g = Guidance("warn", "retry", "앞으로 나갔다가 다시 넣으세요", "Pull forward and try again")
            g.sub_ko, g.sub_en = sub_ko, sub_en
            g.desired_steer_deg, g.current_steer_deg = desired, steer_deg
            return g
    else:
        # 멀리서: 한쪽으로 꺾었다 푸는 S자 조작이 필요하므로 pure pursuit로 목표 핸들 각도를 정한다
        desired = desired_steering(slot, v, 250.0)

    if steer_deg is not None:
        diff = desired - steer_deg
        landing_ok = final_zone and predict_final(slot, v, steer_deg, end).status == "good"
        if abs(diff) < tolerance_deg or landing_ok:
            if near:
                g = Guidance("ok", "slow", "천천히, 거의 다 왔어요", "Slowly, almost there")
            else:
                g = Guidance("ok", "keep", "좋아요, 그대로 후진하세요", "Good, keep reversing")
        else:
            arrow = 1 if diff > 0 else -1
            side_ko, side_en = ("오른쪽", "right") if arrow > 0 else ("왼쪽", "left")
            if abs(desired) < tolerance_deg and abs(steer_deg) >= tolerance_deg:
                g = Guidance("warn", f"unwind{arrow}", "핸들을 풀어 주세요", "Straighten the wheel", arrow)
            else:
                amount_ko, amount_en = turns_phrase_ko(diff), turns_phrase_en(diff)
                # key에는 방향과 대략의 양만 넣어 문구가 자잘하게 바뀌지 않게 한다
                g = Guidance("warn", f"turn{arrow}{'s' if abs(diff) < 150 else ''}",
                             f"핸들을 {side_ko}으로 {amount_ko} 더",
                             f"Turn {amount_en} more {side_en}", arrow)
    else:
        # 핸들 각도를 모를 때: 영상만으로 위치 상태를 알려 준다
        if align.status == "good":
            g = Guidance("ok", "centered", "좌우 균형이 좋아요", "Nicely centered")
        else:
            arrow = 1 if desired > 0 else -1
            side_ko, side_en = ("오른쪽", "right") if arrow > 0 else ("왼쪽", "left")
            g = Guidance("warn", f"hint{arrow}", f"차 뒤를 {side_ko}으로 보내세요",
                         f"Move the rear {side_en}", arrow)

    if final_zone and steer_deg is not None and g.level == "ok":
        landing = predict_final(slot, v, steer_deg, end)
        if landing.status != "good" and abs(landing.offset_cm) >= 5:
            # 마지막 구간에선 더 고칠 수 없더라도 결과를 솔직하게 알려 준다
            side_ko, side_en = ("오른쪽", "right") if landing.offset_cm > 0 else ("왼쪽", "left")
            ref = "목표보다 " if slot.target_bias_cm else ""
            sub_ko += f" · {ref}{side_ko} {abs(landing.offset_cm):.0f}cm"
            sub_en += f" · {abs(landing.offset_cm):.0f}cm {side_en}"
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
