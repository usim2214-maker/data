"""주차선 검출과 좌우 균형 판정.

처리 순서
1. 후방카메라 영상 → 조감도(BEV) 변환 (카메라 높이·각도 보정값 사용)
2. Top-hat + 임계값으로 밝은 페인트 선만 남김
3. 세로 방향 모폴로지로 가로선(주차칸 끝선 등) 제거
4. 연결 요소마다 직선(X = a + b·Y) 피팅 → 비슷한 선끼리 병합
5. 간격이 주차칸 폭과 맞고 차량 중심에 가장 가까운 선 한 쌍을 주차칸으로 선택
6. 차폭을 기준으로 좌우 여유 거리, 치우침, 각도를 계산
"""
from __future__ import annotations

from dataclasses import dataclass, field

import cv2
import numpy as np

from .camera import BevConfig, CameraModel, Projector
from .vehicles import VehicleSpec, DEFAULT_VEHICLE


@dataclass
class DetectorConfig:
    line_width_range_cm: tuple[float, float] = (6.0, 40.0)
    slot_width_range_cm: tuple[float, float] = (200.0, 340.0)
    max_line_angle_deg: float = 35.0
    min_line_length_cm: float = 80.0
    tophat_kernel_cm: float = 60.0
    min_contrast: float = 25.0
    vertical_open_cm: float = 30.0
    ignore_bottom_ratio: float = 0.06  # 범퍼가 보이는 영상 하단 비율
    merge_dx_cm: float = 25.0
    merge_angle_deg: float = 6.0
    y_ref_cm: float = 150.0  # 선을 정렬/비교할 기준 거리


@dataclass
class AlignmentThresholds:
    good_offset_cm: float = 8.0
    good_yaw_deg: float = 1.5
    warn_clearance_cm: float = 15.0  # 이보다 좁으면 주의


@dataclass
class LineEstimate:
    a: float  # X = a + b·Y (선 중심)
    b: float
    width_cm: float
    y_min: float
    y_max: float
    support: int
    pts: np.ndarray = field(repr=False, default_factory=lambda: np.zeros((0, 2)))

    def x_at(self, y: float | np.ndarray) -> float | np.ndarray:
        return self.a + self.b * np.asarray(y)

    @property
    def angle_deg(self) -> float:
        return float(np.degrees(np.arctan(self.b)))

    @property
    def length_cm(self) -> float:
        return float((self.y_max - self.y_min) * np.sqrt(1 + self.b ** 2))


@dataclass
class SlotEstimate:
    left: LineEstimate
    right: LineEstimate

    def center_x_at(self, y: float | np.ndarray) -> float | np.ndarray:
        return 0.5 * (self.left.x_at(y) + self.right.x_at(y))

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

    @property
    def far_end_y(self) -> float:
        return max(self.left.y_max, self.right.y_max)


@dataclass
class Alignment:
    offset_cm: float  # + = 차가 주차칸 중심보다 오른쪽
    yaw_deg: float  # + = 주차칸이 차 기준 시계방향으로 틀어짐
    clear_left_rear_cm: float
    clear_right_rear_cm: float
    clear_left_front_cm: float
    clear_right_front_cm: float
    status: str  # "good" | "adjust" | "danger"

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
            msgs.append(f"{side}으로 {abs(self.offset_cm):.0f}cm 치우침")
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


@dataclass
class DetectionResult:
    bev: np.ndarray
    mask: np.ndarray
    lines: list[LineEstimate]
    slot: SlotEstimate | None
    alignment: Alignment | None


def compute_alignment(
    slot: SlotEstimate, vehicle: VehicleSpec, thr: AlignmentThresholds | None = None
) -> Alignment:
    thr = thr or AlignmentThresholds()
    half_w = vehicle.width_cm / 2.0
    cos_t = float(np.cos(np.arctan(slot.slope)))

    def clearances(y: float) -> tuple[float, float]:
        left_inner = slot.left.x_at(y) + slot.left.width_cm / 2.0
        right_inner = slot.right.x_at(y) - slot.right.width_cm / 2.0
        return (-half_w - left_inner) * cos_t, (right_inner - half_w) * cos_t

    lr, rr = clearances(0.0)
    lf, rf = clearances(-vehicle.length_cm)
    offset = -float(slot.center_x_at(0.0)) * cos_t
    yaw = slot.yaw_deg

    if min(lr, rr, lf, rf) < 0:
        status = "danger"
    elif abs(offset) < thr.good_offset_cm and abs(yaw) < thr.good_yaw_deg:
        status = "good"
    else:
        status = "adjust"
    return Alignment(offset, yaw, float(lr), float(rr), float(lf), float(rf), status)


class SlotDetector:
    def __init__(
        self,
        camera: CameraModel | None = None,
        bev: BevConfig | None = None,
        vehicle: VehicleSpec | None = None,
        config: DetectorConfig | None = None,
        thresholds: AlignmentThresholds | None = None,
    ) -> None:
        self.proj = Projector(camera or CameraModel(), bev or BevConfig())
        self.vehicle = vehicle or DEFAULT_VEHICLE
        self.cfg = config or DetectorConfig()
        self.thr = thresholds or AlignmentThresholds()
        cam = self.proj.camera
        valid = np.full((cam.height, cam.width), 255, np.uint8)
        valid[int(cam.height * (1 - self.cfg.ignore_bottom_ratio)):] = 0
        valid[: int(max(0, cam.horizon_v + 5))] = 0
        self._bev_valid = cv2.warpPerspective(
            valid, self.proj.h_img_to_bev, self.proj.bev.size, flags=cv2.INTER_NEAREST
        )
        self._bev_valid = cv2.erode(self._bev_valid, np.ones((7, 7), np.uint8))

    @property
    def bev(self) -> BevConfig:
        return self.proj.bev

    # ------------------------------------------------------------------ 단계별 처리
    def to_bev(self, img: np.ndarray) -> np.ndarray:
        return cv2.warpPerspective(img, self.proj.h_img_to_bev, self.bev.size, flags=cv2.INTER_LINEAR)

    def line_mask(self, bev_img: np.ndarray) -> np.ndarray:
        s, cfg = self.bev.px_per_cm, self.cfg
        gray = cv2.cvtColor(bev_img, cv2.COLOR_BGR2GRAY) if bev_img.ndim == 3 else bev_img
        # 주차선은 세로에 가까우므로 가로 방향 직사각형 커널이면 충분하고 원형 커널보다 훨씬 빠르다
        k = max(3, int(cfg.tophat_kernel_cm * s) | 1)
        tophat = cv2.morphologyEx(gray, cv2.MORPH_TOPHAT, np.ones((1, k), np.uint8))
        tophat[self._bev_valid == 0] = 0
        vals = tophat[self._bev_valid > 0]
        otsu, _ = cv2.threshold(vals.reshape(-1, 1), 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU) if vals.size else (255, None)
        thr = max(float(otsu), cfg.min_contrast)
        mask = (tophat > thr).astype(np.uint8) * 255
        mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
        vk = max(3, int(cfg.vertical_open_cm * s))
        mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((vk, 1), np.uint8))
        return mask

    def _fit(self, pts: np.ndarray) -> LineEstimate | None:
        """pts: 조감도 픽셀 (col, row). 행마다 선의 중심을 구해 직선을 맞춘다.

        모든 픽셀로 바로 맞추면 화면 경계에서 비스듬히 잘린 선 끝 때문에 기울기가 틀어진다.
        그래서 선 폭이 온전히 보이는 행만 사용한다.
        """
        if len(pts) < 20:
            return None
        cols, rows = pts[:, 0], pts[:, 1]
        order = np.argsort(rows, kind="stable")
        cols, rows = cols[order], rows[order]
        uniq, start, counts = np.unique(rows, return_index=True, return_counts=True)
        if len(uniq) < 10:
            return None
        sums = np.add.reduceat(cols, start)
        centers = sums / counts
        full = counts >= 0.7 * np.median(counts)
        if full.sum() < 8:
            full = np.ones_like(full)
        gx, gy = self.bev.pixels_to_ground(centers[full], uniq[full].astype(np.float64))
        if np.ptp(gy) < 1.0:
            return None
        b, a = np.polyfit(gy, gx, 1)
        # 원근 때문에 먼 곳은 선이 번지므로 가까운 구간(아래쪽 행)으로 폭을 추정
        near_counts = counts[full][-max(8, full.sum() // 3):]
        width = float(np.median(near_counts) / self.bev.px_per_cm * np.cos(np.arctan(b)))
        _, all_y = self.bev.pixels_to_ground(cols, rows)
        return LineEstimate(float(a), float(b), width, float(all_y.min()), float(all_y.max()),
                            int(full.sum()), pts)

    @staticmethod
    def _same_line(long: LineEstimate, short: LineEstimate, tol_cm: float) -> bool:
        """긴 선을 연장했을 때 짧은 선 조각의 양 끝을 지나면 같은 선으로 본다."""
        ys = np.array([short.y_min, short.y_max])
        return bool(np.all(np.abs(long.x_at(ys) - short.x_at(ys)) < tol_cm))

    def detect_lines(self, mask: np.ndarray) -> list[LineEstimate]:
        cfg, s = self.cfg, self.bev.px_per_cm
        n, labels, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
        cands: list[LineEstimate] = []
        min_area = (cfg.min_line_length_cm * 0.4) * cfg.line_width_range_cm[0] * s * s
        for i in range(1, n):
            if stats[i, cv2.CC_STAT_AREA] < min_area:
                continue
            rows, cols = np.nonzero(labels == i)
            line = self._fit(np.column_stack([cols, rows]).astype(np.float64))
            if line is None or abs(line.angle_deg) > cfg.max_line_angle_deg:
                continue
            cands.append(line)

        # 끊긴 선 조각 병합: 긴 선부터 기준으로 삼고, 짧은 조각이 그 연장선 위에 있으면 합친다
        cands.sort(key=lambda l: -l.length_cm)
        merged: list[LineEstimate] = []
        for line in cands:
            for k, base in enumerate(merged):
                if self._same_line(base, line, cfg.merge_dx_cm * 0.6):
                    refit = self._fit(np.vstack([base.pts, line.pts]))
                    if refit is not None:
                        merged[k] = refit
                        break
            else:
                merged.append(line)

        lo, hi = cfg.line_width_range_cm
        out = []
        for l in merged:
            if l.length_cm < cfg.min_line_length_cm or not (lo <= l.width_cm <= hi * 1.5):
                continue
            l.width_cm = float(np.clip(l.width_cm, lo, hi))
            out.append(l)
        return out

    def select_slot(self, lines: list[LineEstimate]) -> SlotEstimate | None:
        cfg = self.cfg
        lines = sorted(lines, key=lambda l: l.x_at(cfg.y_ref_cm))
        best, best_score = None, np.inf
        lo, hi = cfg.slot_width_range_cm
        for left, right in zip(lines, lines[1:]):
            if abs(left.angle_deg - right.angle_deg) > 8.0:
                continue
            slot = SlotEstimate(left, right)
            if not (lo <= slot.width_cm <= hi):
                continue
            score = abs(slot.center_x_at(0.0)) - 0.05 * min(left.length_cm, right.length_cm)
            if score < best_score:
                best, best_score = slot, score
        return self._make_parallel(best) if best else None

    @staticmethod
    def _make_parallel(slot: SlotEstimate) -> SlotEstimate:
        """주차칸 좌우 선은 평행하므로 기울기를 지지 길이로 가중 평균해 공유시킨다."""
        l, r = slot.left, slot.right
        b = (l.b * l.support + r.b * r.support) / (l.support + r.support)

        def rebased(line: LineEstimate) -> LineEstimate:
            yc = 0.5 * (line.y_min + line.y_max)
            return LineEstimate(float(line.x_at(yc) - b * yc), float(b), line.width_cm,
                                line.y_min, line.y_max, line.support, line.pts)

        return SlotEstimate(rebased(l), rebased(r))

    def analyze(self, img: np.ndarray) -> DetectionResult:
        bev_img = self.to_bev(img)
        mask = self.line_mask(bev_img)
        lines = self.detect_lines(mask)
        slot = self.select_slot(lines)
        align = compute_alignment(slot, self.vehicle, self.thr) if slot else None
        return DetectionResult(bev_img, mask, lines, slot, align)


class AlignmentSmoother:
    """영상 프레임 간 흔들림을 줄이는 지수 이동 평균. 몇 프레임 놓쳐도 마지막 값을 유지한다."""

    FIELDS = ("offset_cm", "yaw_deg", "clear_left_rear_cm", "clear_right_rear_cm",
              "clear_left_front_cm", "clear_right_front_cm")

    def __init__(self, alpha: float = 0.35, max_missed: int = 5,
                 thresholds: AlignmentThresholds | None = None) -> None:
        self.alpha, self.max_missed = alpha, max_missed
        self.thr = thresholds or AlignmentThresholds()
        self.state: dict[str, float] | None = None
        self.missed = 0

    def update(self, a: Alignment | None) -> Alignment | None:
        if a is None:
            self.missed += 1
            if self.missed > self.max_missed:
                self.state = None
            return self._emit()
        self.missed = 0
        new = {f: getattr(a, f) for f in self.FIELDS}
        if self.state is None:
            self.state = new
        else:
            self.state = {f: (1 - self.alpha) * self.state[f] + self.alpha * new[f] for f in self.FIELDS}
        return self._emit()

    def _emit(self) -> Alignment | None:
        if self.state is None:
            return None
        s, thr = self.state, self.thr
        clears = [s[f] for f in self.FIELDS[2:]]
        if min(clears) < 0:
            status = "danger"
        elif abs(s["offset_cm"]) < thr.good_offset_cm and abs(s["yaw_deg"]) < thr.good_yaw_deg:
            status = "good"
        else:
            status = "adjust"
        return Alignment(status=status, **s)
