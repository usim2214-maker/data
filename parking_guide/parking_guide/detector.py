"""주차선 검출과 좌우 균형 판정.

처리 순서
1. 후방카메라 영상 → 조감도(BEV) 변환 (렌즈 왜곡 + 원근 보정을 remap 한 번으로)
2. 방향별 Top-hat + 임계값으로 밝은 페인트만 남김
   - 세로 성분(좌우 주차선)과 가로 성분(주차칸 끝선)을 따로 뽑는다
3. 연결 요소마다 직선 피팅 → 끊긴 조각 병합
4. 간격이 주차칸 폭과 맞고 차량 중심에 가장 가까운 선 한 쌍을 주차칸으로 선택
5. 끝선이 보이면 각도를 보정하고 끝선까지 거리를 구함
6. 차폭을 기준으로 좌우 여유 거리, 치우침, 각도를 계산
"""
from __future__ import annotations

from dataclasses import dataclass, field, replace

import cv2
import numpy as np

from .camera import BevConfig, CameraModel, Projector
from .vehicles import DEFAULT_VEHICLE, VehicleSpec


@dataclass
class DetectorConfig:
    line_width_range_cm: tuple[float, float] = (6.0, 40.0)
    slot_width_range_cm: tuple[float, float] = (200.0, 340.0)
    max_line_angle_deg: float = 35.0
    min_line_length_cm: float = 50.0
    min_end_line_length_cm: float = 60.0
    tophat_kernel_cm: float = 60.0
    min_contrast: float = 25.0
    open_kernel_cm: float = 22.0  # 끝선 굵기(15cm)보다 커야 하고, 닳아 짧아진 선 조각보다는 작아야 한다
    ignore_bottom_ratio: float = 0.06  # 범퍼가 보이는 영상 하단 비율
    merge_dx_cm: float = 15.0
    y_ref_cm: float = 150.0  # 선을 정렬/비교할 기준 거리


@dataclass
class AlignmentThresholds:
    good_offset_cm: float = 8.0
    good_yaw_deg: float = 1.5
    warn_clearance_cm: float = 15.0  # 이보다 좁으면 주의


@dataclass
class LineEstimate:
    """좌우 주차선: X = a + b·Y (선 중심)."""

    a: float
    b: float
    width_cm: float
    y_min: float
    y_max: float
    support: int
    pts: np.ndarray = field(repr=False, default_factory=lambda: np.zeros((0, 2)))
    coverage: float = 1.0  # 선 길이 중 페인트가 보이는 비율 (닳을수록 낮음)

    def x_at(self, y: float | np.ndarray) -> float | np.ndarray:
        return self.a + self.b * np.asarray(y)

    @property
    def angle_deg(self) -> float:
        return float(np.degrees(np.arctan(self.b)))

    @property
    def length_cm(self) -> float:
        return float((self.y_max - self.y_min) * np.sqrt(1 + self.b ** 2))


@dataclass
class EndLine:
    """주차칸 끝 가로선: Y = c + d·X (선 중심)."""

    c: float
    d: float
    width_cm: float
    x_min: float
    x_max: float

    def y_at(self, x: float | np.ndarray) -> float | np.ndarray:
        return self.c + self.d * np.asarray(x)

    @property
    def length_cm(self) -> float:
        return float((self.x_max - self.x_min) * np.sqrt(1 + self.d ** 2))


@dataclass
class SlotEstimate:
    left: LineEstimate
    right: LineEstimate
    end: EndLine | None = None
    target_bias_cm: float = 0.0  # 목표 중심선을 주차칸 중심에서 옮긴 양 (+ = 오른쪽, 하차 모드)
    confidence: float = 1.0  # 인식 신뢰도 0~1

    def center_x_at(self, y: float | np.ndarray) -> float | np.ndarray:
        """주차칸의 기하학적 중심선."""
        return 0.5 * (self.left.x_at(y) + self.right.x_at(y))

    def target_x_at(self, y: float | np.ndarray) -> float | np.ndarray:
        """차 중심이 맞춰야 할 목표선 (하차 모드에 따라 옆으로 옮겨질 수 있다)."""
        return self.center_x_at(y) + self.target_bias_cm / np.cos(np.arctan(self.slope))

    @property
    def half_inner_width_cm(self) -> float:
        """선 안쪽 폭의 절반."""
        return 0.5 * (self.width_cm - 0.5 * (self.left.width_cm + self.right.width_cm))

    def with_target_bias(self, bias_cm: float) -> "SlotEstimate":
        return replace(self, target_bias_cm=float(bias_cm))

    @property
    def slope(self) -> float:
        return 0.5 * (self.left.b + self.right.b)

    @property
    def yaw_deg(self) -> float:
        return float(np.degrees(np.arctan(self.slope)))

    @property
    def width_cm(self) -> float:
        """선 중심 간 거리 (주차칸 축에 수직 방향)."""
        dx = self.right.x_at(0.0) - self.left.x_at(0.0)
        return float(dx * np.cos(np.arctan(self.slope)))

    def end_y_for(self, line: LineEstimate) -> float:
        """해당 주차선이 끝나는 지점의 Y (끝선이 있으면 끝선 중심과 만나는 곳)."""
        if self.end is None:
            return line.y_max
        # X = a + bY, Y = c + dX 의 교점
        return float((self.end.c + self.end.d * line.a) / (1 - self.end.d * line.b))

    def moved(self, ds_cm: float, kappa: float = 0.0, axle_y: float = 0.0) -> "SlotEstimate":
        """차가 뒷바퀴 축 기준 ds_cm 만큼 곡률 kappa로 후진한 뒤, 새 차량 좌표계에서 본 주차칸.

        선이 가려지거나 범퍼 밑으로 사라졌을 때 이동 거리(속도)와 핸들 각도로 위치를 이어 가는 데 쓴다.
        지면에 고정된 점은 차와 반대로 움직이므로 같은 원호를 -ds만큼 되돌린 것과 같다.
        """
        def tf(pts: np.ndarray) -> np.ndarray:
            pts = np.asarray(pts, np.float64)
            if abs(kappa) < 1e-9:
                return pts - np.array([0.0, ds_cm])
            R, phi = 1.0 / kappa, -ds_cm * kappa
            vx, vy = pts[:, 0] - R, pts[:, 1] - axle_y
            return np.column_stack([R + vx * np.cos(phi) + vy * np.sin(phi),
                                    axle_y - vx * np.sin(phi) + vy * np.cos(phi)])

        def mv(l: LineEstimate) -> LineEstimate:
            ys = np.array([l.y_min, l.y_max])
            p = tf(np.column_stack([l.x_at(ys), ys]))
            b = (p[1, 0] - p[0, 0]) / (p[1, 1] - p[0, 1])
            return replace(l, a=float(p[0, 0] - b * p[0, 1]), b=float(b),
                           y_min=float(p[:, 1].min()), y_max=float(p[:, 1].max()))

        end = None
        if self.end is not None:
            e = self.end
            xs = np.array([e.x_min, e.x_max])
            p = tf(np.column_stack([xs, e.y_at(xs)]))
            d = (p[1, 1] - p[0, 1]) / (p[1, 0] - p[0, 0])
            end = EndLine(float(p[0, 1] - d * p[0, 0]), float(d), e.width_cm,
                          float(p[:, 0].min()), float(p[:, 0].max()))
        return replace(self, left=mv(self.left), right=mv(self.right), end=end)

    def shifted(self, ds_cm: float) -> "SlotEstimate":
        """차가 ds_cm 만큼 곧게 후진한 뒤의 주차칸."""
        return self.moved(ds_cm)

    @property
    def far_end_y(self) -> float:
        return max(self.end_y_for(self.left), self.end_y_for(self.right))

    @property
    def end_dist_cm(self) -> float | None:
        """뒷범퍼 중앙에서 끝선 안쪽 가장자리까지 수직 거리."""
        if self.end is None:
            return None
        return float(self.end.c / np.sqrt(1 + self.end.d ** 2) - self.end.width_cm / 2)


@dataclass
class Alignment:
    offset_cm: float  # + = 차가 주차칸 중심보다 오른쪽
    yaw_deg: float  # + = 주차칸이 차 기준 시계방향으로 틀어짐
    clear_left_rear_cm: float
    clear_right_rear_cm: float
    clear_left_front_cm: float
    clear_right_front_cm: float
    status: str  # "good" | "adjust" | "danger"
    end_dist_cm: float | None = None
    target_bias_cm: float = 0.0  # 하차 모드로 옮긴 목표 (offset은 이 목표 기준)

    @property
    def clear_left_cm(self) -> float:
        return min(self.clear_left_rear_cm, self.clear_left_front_cm)

    @property
    def clear_right_cm(self) -> float:
        return min(self.clear_right_rear_cm, self.clear_right_front_cm)

    def messages_ko(self) -> list[str]:
        msgs: list[str] = []
        if self.status == "danger":
            side = "왼쪽" if self.clear_left_cm < self.clear_right_cm else "오른쪽"
            msgs.append(f"{side} 주차선 침범 예상!")
        elif self.status == "good":
            msgs.append("좌우 균형 양호")
        if abs(self.offset_cm) >= AlignmentThresholds.good_offset_cm:
            side = "오른쪽" if self.offset_cm > 0 else "왼쪽"
            ref = "목표보다 " if self.target_bias_cm else ""
            msgs.append(f"{ref}{side}으로 {abs(self.offset_cm):.0f}cm 치우침")
        if abs(self.yaw_deg) >= AlignmentThresholds.good_yaw_deg:
            msgs.append(f"각도 {abs(self.yaw_deg):.1f}° 틀어짐")
        return msgs

    def messages_en(self) -> list[str]:
        msgs: list[str] = []
        if self.status == "danger":
            side = "LEFT" if self.clear_left_cm < self.clear_right_cm else "RIGHT"
            msgs.append(f"Crossing {side} line!")
        elif self.status == "good":
            msgs.append("Well centered")
        if abs(self.offset_cm) >= AlignmentThresholds.good_offset_cm:
            side = "right" if self.offset_cm > 0 else "left"
            msgs.append(f"Shifted {side} {abs(self.offset_cm):.0f}cm")
        if abs(self.yaw_deg) >= AlignmentThresholds.good_yaw_deg:
            msgs.append(f"Angle off {abs(self.yaw_deg):.1f} deg")
        return msgs


def slot_confidence(slot: SlotEstimate) -> float:
    """인식 신뢰도 0~1. 평행하게 맞추기 전의 좌우 선으로 계산한다.

    - 보이는 길이: 짧게 보일수록 각도 추정이 불안하다
    - 칠해진 비율: 닳거나 물웅덩이·사람에 가려 끊긴 정도
    - 좌우 선 폭 일치, 평행도, 주차칸 폭이 흔한 범위(2.2~3.0m)인지
    """
    l, r = slot.left, slot.right
    length = float(np.clip(min(l.length_cm, r.length_cm) / 150.0, 0.0, 1.0))
    cover = float(np.clip((min(l.coverage, r.coverage) - 0.3) / 0.5, 0.0, 1.0))
    width_match = 1.0 - 0.5 * float(np.clip(abs(l.width_cm - r.width_cm) / 10.0, 0.0, 1.0))
    parallel = 1.0 - float(np.clip(abs(l.angle_deg - r.angle_deg) / 6.0, 0.0, 1.0))
    slot_w = 1.0 if 220.0 <= slot.width_cm <= 300.0 else 0.6
    score = np.sqrt(length) * np.sqrt(cover) * width_match * (0.5 + 0.5 * parallel) * slot_w
    return float(np.clip(score, 0.0, 1.0))


class SlotTracker:
    """한 번 고른 주차칸을 고정하고 매 프레임 예측 → 검증 → 융합한다.

    - 예측: 이동 거리와 핸들 각도로 지난 주차칸을 옮긴다 (선이 가려져도 이어 감)
    - 검증: 예측과 너무 다른 검출(그림자, 옆 칸, 지나가는 사람)은 무시한다
    - 같은 '다른 칸'이 몇 프레임 계속 보이면 그때 새 칸으로 바꾼다
    - 검출이 없으면 신뢰도를 조금씩 낮추고, 오래 없으면 놓는다
    """

    def __init__(self, gate_cm: float = 30.0, gate_deg: float = 6.0, alpha: float = 0.5,
                 coast_decay: float = 0.97, max_coast: int = 60, switch_after: int = 3) -> None:
        self.gate_cm, self.gate_deg, self.alpha = gate_cm, gate_deg, alpha
        self.coast_decay, self.max_coast, self.switch_after = coast_decay, max_coast, switch_after
        self.slot: SlotEstimate | None = None
        self.coast = 0
        self.outliers = 0
        self.last_rejected = False

    def reset(self) -> None:
        self.slot, self.coast, self.outliers = None, 0, 0

    def update(self, det: SlotEstimate | None, ds_cm: float = 0.0, kappa: float = 0.0,
               axle_y: float = 0.0) -> SlotEstimate | None:
        self.last_rejected = False
        if self.slot is not None and ds_cm:
            self.slot = self.slot.moved(ds_cm, kappa, axle_y)
        if det is None:
            return self._coast()
        if self.slot is None:
            self.slot, self.coast, self.outliers = det, 0, 0
            return self.slot
        p = self.slot
        y_ref = 150.0
        dc = abs(float(det.center_x_at(y_ref) - p.center_x_at(y_ref)))
        dyaw = abs(det.yaw_deg - p.yaw_deg)
        if dc > self.gate_cm or dyaw > self.gate_deg:
            self.outliers += 1
            self.last_rejected = True
            if self.outliers >= self.switch_after:  # 정말로 다른 칸이 계속 보이면 갈아탄다
                self.slot, self.coast, self.outliers = det, 0, 0
                return self.slot
            return self._coast()
        self.outliers, self.coast = 0, 0
        a = self.alpha

        def mix(pl: LineEstimate, dl: LineEstimate) -> LineEstimate:
            return replace(dl, a=(1 - a) * pl.x_at(0.0) + a * dl.a, b=(1 - a) * pl.b + a * dl.b)

        end = det.end if det.end is not None else p.end
        conf = (1 - a) * p.confidence + a * det.confidence
        self.slot = replace(det, left=mix(p.left, det.left), right=mix(p.right, det.right), end=end,
                            confidence=float(conf))
        return self.slot

    def _coast(self) -> SlotEstimate | None:
        if self.slot is None:
            return None
        self.coast += 1
        if self.coast > self.max_coast:
            self.slot = None
            return None
        self.slot = replace(self.slot, confidence=self.slot.confidence * self.coast_decay)
        return self.slot


DOOR_MODES = ("center", "driver", "passenger")


def door_bias_cm(mode: str, slot: SlotEstimate, vehicle: VehicleSpec,
                 max_bias_cm: float = 15.0, keep_cm: float = 8.0) -> float:
    """하차 모드별 목표 이동량. 국내 차량은 운전석이 왼쪽이므로
    운전석 여유 = 차를 오른쪽(+)으로, 동승석·뒷좌석 여유 = 왼쪽(-)으로 옮긴다.
    반대쪽에도 keep_cm 이상은 남긴다."""
    if mode == "center":
        return 0.0
    margin = slot.half_inner_width_cm - vehicle.width_cm / 2  # 가운데 세웠을 때 한쪽 여유
    b = float(np.clip(margin - keep_cm, 0.0, max_bias_cm))
    if mode == "driver":
        return b
    if mode == "passenger":
        return -b
    raise ValueError(f"하차 모드는 {DOOR_MODES} 중 하나: {mode}")


@dataclass
class DetectionResult:
    bev: np.ndarray
    mask: np.ndarray
    lines: list[LineEstimate]
    slot: SlotEstimate | None
    alignment: Alignment | None
    end_mask: np.ndarray | None = None


def classify(offset: float, yaw: float, clears: list[float], thr: AlignmentThresholds) -> str:
    if min(clears) < 0:
        return "danger"
    if abs(offset) < thr.good_offset_cm and abs(yaw) < thr.good_yaw_deg:
        return "good"
    return "adjust"


def compute_alignment(
    slot: SlotEstimate, vehicle: VehicleSpec, thr: AlignmentThresholds | None = None
) -> Alignment:
    thr = thr or AlignmentThresholds()
    half_w = vehicle.width_cm / 2.0
    cos_t = float(np.cos(np.arctan(slot.slope)))

    def clearances(y: float) -> tuple[float, float]:
        left_inner = slot.left.x_at(y) + slot.left.width_cm / 2.0
        right_inner = slot.right.x_at(y) - slot.right.width_cm / 2.0
        return float((-half_w - left_inner) * cos_t), float((right_inner - half_w) * cos_t)

    lr, rr = clearances(0.0)
    lf, rf = clearances(-vehicle.length_cm)
    offset = -float(slot.target_x_at(0.0)) * cos_t  # 목표선 기준
    yaw = slot.yaw_deg
    status = classify(offset, yaw, [lr, rr, lf, rf], thr)
    return Alignment(offset, yaw, lr, rr, lf, rf, status, slot.end_dist_cm, slot.target_bias_cm)


def _row_centers(primary: np.ndarray, secondary: np.ndarray):
    """primary 값(행 또는 열)마다 secondary의 평균과 화소 수. 선 폭이 온전한 것만 표시."""
    order = np.argsort(primary, kind="stable")
    primary, secondary = primary[order], secondary[order]
    uniq, start, counts = np.unique(primary, return_index=True, return_counts=True)
    centers = np.add.reduceat(secondary, start) / counts
    full = counts >= 0.7 * np.median(counts)
    if full.sum() < 8:
        full = np.ones_like(full)
    return uniq, centers, counts, full


def _hough(mask: np.ndarray, votes: int, min_theta: float = 0.0, max_theta: float = np.pi):
    """허프 변환을 절반 크기 마스크에서 돌려 4배 빠르게 한다 (조각을 묶는 용도라 정밀도는 충분).
    결과의 rho는 원래 크기 기준으로 되돌린다."""
    small = cv2.resize(mask, (mask.shape[1] // 2, mask.shape[0] // 2), interpolation=cv2.INTER_AREA)
    small = (small > 60).astype(np.uint8) * 255
    hl = cv2.HoughLines(small, 1, np.radians(0.5), max(10, votes // 2),
                        min_theta=min_theta, max_theta=max_theta)
    if hl is None:
        return None
    hl = hl.copy()
    hl[:, 0, 0] = hl[:, 0, 0] * 2 + 0.5
    return hl


class SlotDetector:
    def __init__(
        self,
        camera: CameraModel | None = None,
        bev: BevConfig | None = None,
        vehicle: VehicleSpec | None = None,
        config: DetectorConfig | None = None,
        thresholds: AlignmentThresholds | None = None,
    ) -> None:
        self.cfg = config or DetectorConfig()
        self.proj = Projector(camera or CameraModel(), bev or BevConfig(), self.cfg.ignore_bottom_ratio)
        self.vehicle = vehicle or DEFAULT_VEHICLE
        self.thr = thresholds or AlignmentThresholds()
        self.door_mode = "center"

    @property
    def bev(self) -> BevConfig:
        return self.proj.bev

    # ------------------------------------------------------------------ 마스크
    def to_bev(self, img: np.ndarray) -> np.ndarray:
        return self.proj.to_bev(img)

    def _directional_mask(self, gray: np.ndarray, vertical: bool) -> np.ndarray:
        s, cfg = self.bev.px_per_cm, self.cfg
        k = max(3, int(cfg.tophat_kernel_cm * s) | 1)
        # 세로 선을 찾을 땐 가로 커널로, 가로 선을 찾을 땐 세로 커널로 배경을 뺀다
        kernel = np.ones((1, k), np.uint8) if vertical else np.ones((k, 1), np.uint8)
        tophat = cv2.morphologyEx(gray, cv2.MORPH_TOPHAT, kernel)
        valid = self.proj.valid
        tophat[valid == 0] = 0
        vals = tophat[valid > 0]
        otsu = cv2.threshold(vals.reshape(-1, 1), 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)[0] if vals.size else 255
        mask = (tophat > max(float(otsu), cfg.min_contrast)).astype(np.uint8) * 255
        mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
        ok = max(3, int(cfg.open_kernel_cm * s))
        open_k = np.ones((ok, 1), np.uint8) if vertical else np.ones((1, ok), np.uint8)
        return cv2.morphologyEx(mask, cv2.MORPH_OPEN, open_k)

    def line_mask(self, bev_img: np.ndarray) -> np.ndarray:
        gray = cv2.cvtColor(bev_img, cv2.COLOR_BGR2GRAY) if bev_img.ndim == 3 else bev_img
        return self._directional_mask(gray, vertical=True)

    def end_line_mask(self, bev_img: np.ndarray) -> np.ndarray:
        gray = cv2.cvtColor(bev_img, cv2.COLOR_BGR2GRAY) if bev_img.ndim == 3 else bev_img
        return self._directional_mask(gray, vertical=False)

    # ------------------------------------------------------------------ 좌우 주차선
    def _fit(self, pts: np.ndarray) -> LineEstimate | None:
        """pts: 조감도 픽셀 (col, row). 행마다 선의 중심을 구해 직선을 맞춘다.

        모든 픽셀로 바로 맞추면 화면 경계에서 비스듬히 잘린 선 끝 때문에 기울기가 틀어진다.
        그래서 선 폭이 온전히 보이는 행만 사용한다.
        """
        if len(pts) < 20:
            return None
        cols, rows = pts[:, 0], pts[:, 1]
        uniq, centers, counts, full = _row_centers(rows, cols)
        if len(uniq) < 10:
            return None
        gx, gy = self.bev.pixels_to_ground(centers[full], uniq[full].astype(np.float64))
        if np.ptp(gy) < 1.0:
            return None
        b, a = np.polyfit(gy, gx, 1)
        # 원근 때문에 먼 곳은 선이 번지므로 가까운 구간(아래쪽 행)으로 폭을 추정
        near_counts = counts[full][-max(8, int(full.sum()) // 3):]
        width = float(np.median(near_counts) / self.bev.px_per_cm * np.cos(np.arctan(b)))
        _, all_y = self.bev.pixels_to_ground(cols, rows)
        span_rows = (all_y.max() - all_y.min()) * self.bev.px_per_cm + 1
        coverage = float(np.clip(len(uniq) / span_rows, 0.0, 1.0))
        return LineEstimate(float(a), float(b), width, float(all_y.min()), float(all_y.max()),
                            int(full.sum()), pts, coverage)

    @staticmethod
    def _same_line(long: LineEstimate, short: LineEstimate, tol_cm: float) -> bool:
        """긴 선을 연장했을 때 짧은 선 조각의 양 끝을 지나면 같은 선으로 본다."""
        ys = np.array([short.y_min, short.y_max])
        return bool(np.all(np.abs(long.x_at(ys) - short.x_at(ys)) < tol_cm))

    def _components(self, mask: np.ndarray, min_area: float):
        n, labels, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
        for i in range(1, n):
            if stats[i, cv2.CC_STAT_AREA] < min_area:
                continue
            x, y, w, h = (stats[i, k] for k in (cv2.CC_STAT_LEFT, cv2.CC_STAT_TOP,
                                                 cv2.CC_STAT_WIDTH, cv2.CC_STAT_HEIGHT))
            rows, cols = np.nonzero(labels[y:y + h, x:x + w] == i)
            yield np.column_stack([cols + x, rows + y]).astype(np.float64)

    def detect_lines(self, mask: np.ndarray) -> list[LineEstimate]:
        """좌우 주차선 후보.

        1) 연결 요소(페인트 조각)를 구한다.
        2) 허프 변환으로 '한 직선 위에 놓인 조각들'을 묶는다. 닳아서 점선처럼 끊긴 선도 한 선이 된다.
           (허프 직선 주변 띠의 화소만 쓰면 번진 먼 쪽이 잘려 각도가 틀어지므로, 묶인 조각의 화소 전체로 맞춘다)
        3) 묶이지 않은 조각은 따로 맞추고, 같은 선 위에 있는 후보끼리 합친다.
        """
        cfg, s = self.cfg, self.bev.px_per_cm
        n, labels, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
        min_area = 10 * cfg.line_width_range_cm[0] * s * s  # 10cm 길이 조각까지
        areas = stats[:, cv2.CC_STAT_AREA]
        valid = {i for i in range(1, n) if areas[i] >= min_area}
        cache: dict[int, np.ndarray] = {}

        def comp_pts(i: int) -> np.ndarray:
            if i not in cache:
                x, y, w, h = (stats[i, k] for k in (cv2.CC_STAT_LEFT, cv2.CC_STAT_TOP,
                                                     cv2.CC_STAT_WIDTH, cv2.CC_STAT_HEIGHT))
                rr, cc = np.nonzero(labels[y:y + h, x:x + w] == i)
                cache[i] = np.column_stack([cc + x, rr + y]).astype(np.float64)
            return cache[i]

        def fit_group(group: frozenset) -> LineEstimate | None:
            line = self._fit(np.vstack([comp_pts(i) for i in group]))
            if line is None or abs(line.angle_deg) > cfg.max_line_angle_deg:
                return None
            return line

        groups: list[tuple[frozenset, LineEstimate]] = []
        claimed: set[int] = set()
        hl = _hough(mask, int(60 * s))
        if hl is not None:
            rows, cols = np.nonzero(mask)
            labs_px = labels[rows, cols]
            max_t = np.radians(cfg.max_line_angle_deg)
            for rho, theta in hl[:40, 0]:
                if not (theta < max_t or theta > np.pi - max_t):  # 세로에 가까운 선만
                    continue
                band = np.abs(cols * np.cos(theta) + rows * np.sin(theta) - rho) < 12 * s
                ids, cnt = np.unique(labs_px[band], return_counts=True)
                # 조각의 절반 이상이 띠 안에 있으면 이 직선 위의 조각으로 본다
                group = frozenset(int(i) for i, c in zip(ids, cnt)
                                  if i in valid and i not in claimed and c >= 0.5 * areas[i])
                if not group:
                    continue
                line = fit_group(group)
                if line is None or line.length_cm < cfg.min_line_length_cm:
                    continue
                groups.append((group, line))
                claimed |= group
        for i in sorted(valid - claimed, key=lambda i: -areas[i]):
            line = fit_group(frozenset([i]))
            if line is not None:
                groups.append((frozenset([i]), line))

        # 같은 선 위에 있는 후보 합치기 (긴 것부터 기준)
        groups.sort(key=lambda g: -g[1].length_cm)
        merged: list[tuple[frozenset, LineEstimate]] = []
        for group, line in groups:
            for k, (bg, base) in enumerate(merged):
                if self._same_line(base, line, cfg.merge_dx_cm):
                    refit = fit_group(bg | group)
                    if refit is not None:
                        merged[k] = (bg | group, refit)
                        break
            else:
                merged.append((group, line))

        lo, hi = cfg.line_width_range_cm
        out = []
        for _, l in merged:
            if l.length_cm < cfg.min_line_length_cm or not (lo <= l.width_cm <= hi * 1.5):
                continue
            l.width_cm = float(np.clip(l.width_cm, lo, hi))
            out.append(l)
        return out

    # ------------------------------------------------------------------ 끝선
    def _fit_end(self, pts: np.ndarray) -> EndLine | None:
        if len(pts) < 20:
            return None
        cols, rows = pts[:, 0], pts[:, 1]
        uniq, centers, counts, full = _row_centers(cols, rows)
        if full.sum() < 10:
            return None
        gx, gy = self.bev.pixels_to_ground(uniq[full].astype(np.float64), centers[full])
        if np.ptp(gx) < 1.0:
            return None
        d, c = np.polyfit(gx, gy, 1)
        width = float(np.median(counts[full]) / self.bev.px_per_cm * np.cos(np.arctan(d)))
        return EndLine(float(c), float(d), width, float(gx.min()), float(gx.max()))

    def _hough_end_lines(self, mask: np.ndarray) -> list[EndLine]:
        """닳아서 끊긴 끝선용: 가로에 가까운 직선을 허프 변환으로 찾아 띠 안의 화소로 다시 맞춘다."""
        s = self.bev.px_per_cm
        hl = _hough(mask, int(50 * s), np.pi / 2 - np.radians(self.cfg.max_line_angle_deg),
                    np.pi / 2 + np.radians(self.cfg.max_line_angle_deg))
        if hl is None:
            return []
        rows, cols = np.nonzero(mask)
        pts_all = np.column_stack([cols, rows]).astype(np.float64)
        max_t = np.radians(self.cfg.max_line_angle_deg)
        out, used = [], []
        for rho, theta in hl[:20, 0]:
            if abs(theta - np.pi / 2) > max_t:
                continue
            if any(abs(rho - r) < 30 * s for r in used):
                continue
            used.append(float(rho))
            dist = np.abs(pts_all[:, 0] * np.cos(theta) + pts_all[:, 1] * np.sin(theta) - rho)
            end = self._fit_end(pts_all[dist < 22 * s])  # 번진 먼 쪽이 잘리지 않도록 넉넉한 띠
            if end is not None:
                out.append(end)
        return out

    def detect_end_line(self, mask: np.ndarray, slot: SlotEstimate) -> EndLine | None:
        cfg, s = self.cfg, self.bev.px_per_cm
        min_area = cfg.min_end_line_length_cm * 0.5 * cfg.line_width_range_cm[0] * s * s
        best, best_err = None, np.inf
        cands = [self._fit_end(p) for p in self._components(mask, min_area)]
        cands += self._hough_end_lines(mask)
        for end in cands:
            if end is None or end.length_cm < cfg.min_end_line_length_cm:
                continue
            if not (cfg.line_width_range_cm[0] <= end.width_cm <= cfg.line_width_range_cm[1] * 1.5):
                continue
            # 좌우 선과 거의 수직이어야 한다
            if abs(np.degrees(np.arctan(end.d)) + slot.yaw_deg) > 8.0:
                continue
            xc = float(slot.center_x_at(end.y_at(0.0)))
            if not (end.x_min - 40 <= xc <= end.x_max + 40):
                continue
            # 좌우 주차선이 끝나는 곳 근처에 있어야 한다
            err = abs(float(end.y_at(xc)) - slot.far_end_y)
            if err < 80 and err < best_err:
                best, best_err = end, err
        if best is not None:
            best.width_cm = float(np.clip(best.width_cm, *cfg.line_width_range_cm))
        return best

    # ------------------------------------------------------------------ 주차칸 선택
    def select_slot(self, lines: list[LineEstimate]) -> SlotEstimate | None:
        cfg = self.cfg
        lines = sorted(lines, key=lambda l: l.x_at(cfg.y_ref_cm))
        best, best_score = None, np.inf
        lo, hi = cfg.slot_width_range_cm
        for i in range(len(lines)):
            for j in range(i + 1, len(lines)):
                left, right = lines[i], lines[j]
                if abs(left.angle_deg - right.angle_deg) > 4.0:
                    continue
                slot = SlotEstimate(left, right)
                if not (lo <= slot.width_cm <= hi):
                    continue
                # 사이에 평행한 긴 선이 있으면 두 칸에 걸친 쌍이다
                if any(abs(m.angle_deg - left.angle_deg) < 3.0 and m.length_cm > 100 for m in lines[i + 1:j]):
                    continue
                score = (abs(slot.center_x_at(0.0)) - 0.05 * min(left.length_cm, right.length_cm)
                         - 20.0 * min(left.coverage, right.coverage))
                if score < best_score:
                    best, best_score = slot, score
        if best is None:
            return None
        best = replace(best, confidence=slot_confidence(best))
        return self._make_parallel(best)

    @staticmethod
    def _make_parallel(slot: SlotEstimate, end: EndLine | None = None) -> SlotEstimate:
        """좌우 선은 평행하고 끝선과 수직이므로, 기울기를 보이는 길이로 가중 평균해 공유시킨다.
        주차칸 깊숙이 들어가 좌우 선이 짧게 보일 때 끝선이 각도를 크게 보정해 준다."""
        l, r = slot.left, slot.right
        num = l.b * l.length_cm + r.b * r.length_cm
        den = l.length_cm + r.length_cm
        if end is not None:
            num += -end.d * end.length_cm
            den += end.length_cm
        b = num / den

        def rebased(line: LineEstimate) -> LineEstimate:
            yc = 0.5 * (line.y_min + line.y_max)
            return replace(line, a=float(line.x_at(yc) - b * yc), b=float(b))

        if end is not None:
            xm = 0.5 * (end.x_min + end.x_max)
            end = EndLine(float(end.y_at(xm) + b * xm), float(-b), end.width_cm, end.x_min, end.x_max)
        return replace(slot, left=rebased(l), right=rebased(r), end=end)

    def analyze(self, img: np.ndarray) -> DetectionResult:
        bev_img = self.to_bev(img)
        gray = cv2.cvtColor(bev_img, cv2.COLOR_BGR2GRAY)
        mask = self._directional_mask(gray, vertical=True)
        end_mask = self._directional_mask(gray, vertical=False)
        # 멀리 있는 끝선은 원근 때문에 세로로 두꺼워져 세로선 마스크에 남을 수 있으므로 직접 뺀다
        mask[cv2.dilate(end_mask, np.ones((5, 5), np.uint8)) > 0] = 0
        lines = self.detect_lines(mask)
        slot = self.select_slot(lines)
        if slot is not None:
            end = self.detect_end_line(end_mask, slot)
            if end is not None:
                slot = self._make_parallel(replace(slot, end=None), end)
                slot = replace(slot, confidence=min(1.0, slot.confidence + 0.1))  # 끝선까지 맞으면 더 믿을 만함
            slot = slot.with_target_bias(door_bias_cm(self.door_mode, slot, self.vehicle))
        align = compute_alignment(slot, self.vehicle, self.thr) if slot else None
        return DetectionResult(bev_img, mask, lines, slot, align, end_mask)


class AlignmentSmoother:
    """영상 프레임 간 흔들림을 줄이는 지수 이동 평균. 몇 프레임 놓쳐도 마지막 값을 유지한다."""

    FIELDS = ("offset_cm", "yaw_deg", "clear_left_rear_cm", "clear_right_rear_cm",
              "clear_left_front_cm", "clear_right_front_cm")

    def __init__(self, alpha: float = 0.35, max_missed: int = 5,
                 thresholds: AlignmentThresholds | None = None) -> None:
        self.alpha, self.max_missed = alpha, max_missed
        self.thr = thresholds or AlignmentThresholds()
        self.state: dict[str, float] | None = None
        self.end: float | None = None
        self.bias = 0.0
        self.missed = 0
        self.missed_end = 0

    def advance(self, ds_cm: float) -> None:
        """차가 후진한 거리만큼 끝선까지 거리를 줄인다 (추측 항법).

        끝선이 범퍼 바로 뒤로 들어가면 카메라에 안 보이므로 마지막 구간은 이 값으로 안내한다.
        실제 앱에서는 차량 속도(또는 주행거리) 신호로 이동 거리를 구한다.
        """
        if self.end is not None:
            self.end -= ds_cm

    def update(self, a: Alignment | None) -> Alignment | None:
        if a is None:
            self.missed += 1
            if self.missed > self.max_missed:
                self.state, self.end = None, None
            return self._emit()
        self.missed = 0
        self.bias = a.target_bias_cm
        new = {f: getattr(a, f) for f in self.FIELDS}
        if self.state is None:
            self.state = new
        else:
            self.state = {f: (1 - self.alpha) * self.state[f] + self.alpha * new[f] for f in self.FIELDS}
        if a.end_dist_cm is None:
            # 끝선을 잠깐 놓치면 이전 값을 유지하고, 계속 안 보이면 지운다
            self.missed_end += 1
            if self.missed_end > self.max_missed:
                self.end = None
        elif self.end is None:
            self.missed_end = 0
            self.end = a.end_dist_cm
        else:
            self.missed_end = 0
            self.end = (1 - self.alpha) * self.end + self.alpha * a.end_dist_cm
        return self._emit()

    def _emit(self) -> Alignment | None:
        if self.state is None:
            return None
        s = self.state
        status = classify(s["offset_cm"], s["yaw_deg"], [s[f] for f in self.FIELDS[2:]], self.thr)
        return Alignment(status=status, end_dist_cm=self.end, target_bias_cm=self.bias, **s)
