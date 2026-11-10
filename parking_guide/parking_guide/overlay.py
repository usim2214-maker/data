"""후방카메라 화면 위에 주차 가이드를 그린다.

화면 구성 (운전자가 한눈에 볼 수 있게 정보를 세 곳에만 둔다)
- 위 가운데 '안내 배지': 지금 해야 할 행동 한 가지 (핸들 방향 / 그대로 / 멈추세요) + 끝선까지 거리
- 아래 가운데 '균형 게이지': 주차칸 안에서 차가 어디쯤 있는지 수평계처럼 보여 주고 좌우 여유(cm) 표시
- 오른쪽 위 '미니맵': 위에서 본 주차칸, 차, 예상 경로

카메라 영상 위에는
- 가상 벽 수직선: 주차선이 끝나는 벽 쪽에 세로 기준선 (공주시 벽 페인트 선을 대신함)
- 좌우 주차선 / 끝선 강조, 주차칸 중심선
- 차폭 통로: 핸들 각도를 알면 휘어진 예상 경로, 모르면 직선. 0.5m / 1m / 2m 눈금
"""
from __future__ import annotations

import cv2
import numpy as np

from .camera import CameraModel
from .detector import Alignment, AlignmentThresholds, SlotEstimate
from .guidance import FINAL_ZONE_CM, GHOST_CONFIDENCE, LOW_CONFIDENCE, FinalPrediction, Guidance, predict_final, predict_path, turns_phrase_en, turns_phrase_ko
from .text import draw_texts, korean_available, text_width
from .vehicles import VehicleSpec

# BGR. 색만으로 구분하지 않도록 문구·아이콘을 항상 함께 쓴다.
GREEN = (96, 200, 72)
AMBER = (20, 176, 255)
RED = (64, 64, 232)
BLUE = (235, 170, 60)
WHITE = (250, 250, 250)
SOFT = (200, 200, 200)
DARK = (28, 28, 30)
LEVEL_COLOR = {"ok": GREEN, "warn": AMBER, "stop": RED, "info": (120, 120, 120)}
STATUS_COLOR = {"good": GREEN, "adjust": AMBER, "danger": RED}


def clearance_color(c: float, thr: AlignmentThresholds) -> tuple[int, int, int]:
    if c < 0:
        return RED
    if c < thr.warn_clearance_cm:
        return AMBER
    return GREEN


def display_clearances(a: Alignment, final_zone_cm: float = 250.0) -> tuple[float, float]:
    """화면에 보여 줄 좌우 여유.

    접근 중에는 지금 뒷범퍼 위치의 여유를 보여 준다. 비스듬히 들어오는 중에 앞부분까지 직선으로
    연장해서 계산하면 운전자가 곧 바로잡을 각도 때문에 과하게 겁을 주기 때문이다.
    주차칸에 거의 들어오면(끝선까지 final_zone_cm 이내) 앞부분까지 포함한 최소 여유를 쓴다.
    """
    if a.end_dist_cm is not None and a.end_dist_cm <= final_zone_cm:
        return a.clear_left_cm, a.clear_right_cm
    return a.clear_left_rear_cm, a.clear_right_rear_cm


def display_status(a: Alignment, thr: AlignmentThresholds) -> str:
    """화면에 보여 주는 여유값과 같은 기준으로 매긴 상태."""
    dl, dr = display_clearances(a)
    if min(dl, dr) < 0:
        return "danger"
    dev = (dl - dr) / 2 - a.target_bias_cm  # 목표 위치에서 벗어난 정도
    if abs(a.offset_cm) < thr.good_offset_cm and abs(a.yaw_deg) < thr.good_yaw_deg and abs(dev) < thr.good_offset_cm:
        return "good"
    return "adjust"


# ---------------------------------------------------------------------- 그리기 도구
def _nonzero(layer: np.ndarray) -> np.ndarray:
    """그림이 그려진 픽셀 마스크 (numpy any보다 훨씬 빠름)."""
    b, g, r = cv2.split(layer)
    return cv2.max(cv2.max(b, g), r) > 0


def rounded_rect(img, x0, y0, x1, y1, r, color, alpha=1.0) -> None:
    """반투명 둥근 사각형. 사각형 영역만 잘라서 처리해 빠르다."""
    H, W = img.shape[:2]
    x0, y0, x1, y1 = int(x0), int(y0), int(x1), int(y1)
    r = int(max(1, min(r, (x1 - x0) // 2, (y1 - y0) // 2)))
    cx0, cy0, cx1, cy1 = max(0, x0), max(0, y0), min(W, x1 + 1), min(H, y1 + 1)
    if cx1 <= cx0 or cy1 <= cy0:
        return
    mask = np.zeros((y1 - y0 + 1, x1 - x0 + 1), np.uint8)
    w, h = x1 - x0, y1 - y0
    cv2.rectangle(mask, (r, 0), (w - r, h), 255, -1)
    cv2.rectangle(mask, (0, r), (w, h - r), 255, -1)
    for cx, cy in ((r, r), (w - r, r), (r, h - r), (w - r, h - r)):
        cv2.circle(mask, (cx, cy), r, 255, -1, cv2.LINE_AA)
    mask = mask[cy0 - y0:cy1 - y0, cx0 - x0:cx1 - x0]
    roi = img[cy0:cy1, cx0:cx1].astype(np.float32)
    a = (mask.astype(np.float32) / 255.0 * alpha)[..., None]
    img[cy0:cy1, cx0:cx1] = (roi * (1 - a) + np.array(color, np.float32) * a).astype(np.uint8)


def draw_steering_icon(img, cx, cy, r, color, arrow: int) -> None:
    """핸들 모양 아이콘 + 돌릴 방향 화살표."""
    t = max(2, r // 5)
    cv2.circle(img, (cx, cy), r, color, t, cv2.LINE_AA)
    cv2.circle(img, (cx, cy), max(2, r // 4), color, -1, cv2.LINE_AA)
    for ang in (180, 0, 90):
        a = np.radians(ang)
        cv2.line(img, (cx, cy), (int(cx + r * np.cos(a)), int(cy + r * np.sin(a))), color, t, cv2.LINE_AA)
    if arrow:
        R = int(r * 1.55)
        start, end = -150, -30
        cv2.ellipse(img, (cx, cy), (R, R), 0, start, end, color, t, cv2.LINE_AA)
        tip_deg = end if arrow > 0 else start
        a = np.radians(tip_deg)
        tip = np.array([cx + R * np.cos(a), cy + R * np.sin(a)])
        # 화살 진행 방향 (시계방향이면 각도 증가 방향)
        tangent = np.array([-np.sin(a), np.cos(a)]) * (1 if arrow > 0 else -1)
        normal = np.array([np.cos(a), np.sin(a)])
        L = r * 0.7
        head = tip + tangent * L * 0.6
        p1, p2 = tip + normal * L * 0.55, tip - normal * L * 0.55
        cv2.fillPoly(img, [np.round([head, p1, p2]).astype(np.int32)], color, cv2.LINE_AA)


def draw_retry_icon(img, cx, cy, r, color) -> None:
    """다시 하기(↻) 아이콘."""
    t = max(3, r // 4)
    cv2.ellipse(img, (cx, cy), (r, r), 0, -60, 230, color, t, cv2.LINE_AA)
    a = np.radians(-60)
    tip = np.array([cx + r * np.cos(a), cy + r * np.sin(a)])
    tangent = np.array([np.sin(a), -np.cos(a)])  # 반시계 방향 끝
    normal = np.array([np.cos(a), np.sin(a)])
    head = tip + tangent * r * 0.55
    pts = np.round([head, tip + normal * r * 0.45, tip - normal * r * 0.45]).astype(np.int32)
    cv2.fillPoly(img, [pts], color, cv2.LINE_AA)


def draw_check_icon(img, cx, cy, r, color) -> None:
    pts = np.array([[cx - r * 0.6, cy], [cx - r * 0.15, cy + r * 0.45], [cx + r * 0.65, cy - r * 0.45]])
    cv2.polylines(img, [np.round(pts).astype(np.int32)], False, color, max(3, r // 4), cv2.LINE_AA)


def draw_stop_icon(img, cx, cy, r, fg) -> None:
    ang = np.radians(np.arange(8) * 45 + 22.5)
    pts = np.round(np.column_stack([cx + r * np.cos(ang), cy + r * np.sin(ang)])).astype(np.int32)
    cv2.polylines(img, [pts], True, fg, max(2, r // 6), cv2.LINE_AA)
    cv2.line(img, (cx - r // 2, cy), (cx + r // 2, cy), fg, max(3, r // 4), cv2.LINE_AA)


# ---------------------------------------------------------------------- 렌더러
class GuideRenderer:
    def __init__(self, camera: CameraModel, vehicle: VehicleSpec,
                 thresholds: AlignmentThresholds | None = None, lang: str = "auto",
                 show_minimap: bool = True) -> None:
        self.camera, self.vehicle = camera, vehicle
        self.thr = thresholds or AlignmentThresholds()
        self.lang = ("ko" if korean_available() else "en") if lang == "auto" else lang
        self.y_near = camera.visible_y_min() + 5.0
        self.wall_line_height_cm = 110.0
        self.show_minimap = show_minimap
        self.k = camera.width / 1280.0  # UI 배율

    def _t(self, ko: str, en: str) -> str:
        return ko if self.lang == "ko" else en

    # ------------------------------------------------------------------ 투영 도구
    def _poly(self, pts3: np.ndarray) -> np.ndarray | None:
        uv, ok = self.camera.project(pts3)
        if not ok.any():
            return None
        return np.round(uv[ok]).astype(np.int32)

    def _ground_line(self, layer, x0, y0, x1, y1, color, thick, n=24) -> None:
        y0, y1 = max(y0, self.y_near), max(y1, self.y_near)
        t = np.linspace(0, 1, n)
        pts = np.column_stack([x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, np.zeros(n)])
        p = self._poly(pts)
        if p is not None and len(p) > 1:
            cv2.polylines(layer, [p], False, color, thick, cv2.LINE_AA)

    def _ground_dashed(self, layer, x0, y0, x1, y1, color, thick, dash_cm=30.0) -> None:
        y0 = max(y0, self.y_near)
        n = max(1, int(np.hypot(x1 - x0, y1 - y0) / dash_cm))
        for i in range(0, n, 2):
            a, b = i / n, min(1.0, (i + 1) / n)
            self._ground_line(layer, x0 + (x1 - x0) * a, y0 + (y1 - y0) * a,
                              x0 + (x1 - x0) * b, y0 + (y1 - y0) * b, color, thick, n=4)

    def _vertical_bar(self, layer, x, y, z1, color, width_cm, nx=1.0, ny=0.0) -> None:
        """벽에 세운 세로 막대. (nx, ny)는 벽을 따라가는 가로 방향."""
        hw = width_cm / 2
        zs = np.linspace(0, z1, 12)
        left = np.column_stack([np.full(12, x - hw * nx), np.full(12, y - hw * ny), zs])
        right = np.column_stack([np.full(12, x + hw * nx), np.full(12, y + hw * ny), zs])
        p = self._poly(np.vstack([left, right[::-1]]))
        if p is not None and len(p) > 2:
            cv2.fillPoly(layer, [p], color, cv2.LINE_AA)

    def _ground_pts(self, xy: np.ndarray) -> np.ndarray:
        xy = xy[xy[:, 1] >= self.y_near]
        return np.column_stack([xy, np.zeros(len(xy))])

    # ------------------------------------------------------------------ 진입점
    def render(self, img: np.ndarray, slot: SlotEstimate | None, align: Alignment | None,
               guidance: Guidance | None = None, steer_deg: float | None = None) -> np.ndarray:
        out = img.copy()
        path = self._path(align, steer_deg)
        final = None
        # 고스트카는 마무리 구간에서만: 멀리서는 '핸들 고정' 가정이 맞지 않아 오해를 부른다
        if (slot is not None and align is not None and steer_deg is not None
                and slot.confidence >= GHOST_CONFIDENCE
                and align.end_dist_cm is not None and align.end_dist_cm <= FINAL_ZONE_CM):
            final = predict_final(slot, self.vehicle, steer_deg, align.end_dist_cm)
        # 1) 바닥 통로 (옅은 면)
        corridor = np.zeros_like(out)
        self._draw_corridor(corridor, path, guidance)
        m = _nonzero(corridor)
        out[m] = cv2.addWeighted(out, 0.82, corridor, 0.18, 0)[m]
        # 2) 선
        layer = np.zeros_like(out)
        self._draw_corridor_edges(layer, path)
        if slot is not None and align is not None:
            self._draw_slot(layer, slot, align)
        if final is not None:
            self._draw_ghost(layer, final)
        m = _nonzero(layer)
        out[m] = cv2.addWeighted(out, 0.15, layer, 0.95, 0)[m]
        # 3) UI
        texts: list = []
        self._draw_badge(out, texts, guidance)
        weak = slot is not None and slot.confidence < LOW_CONFIDENCE
        if align is not None:
            self._draw_balance(out, texts, align, final, weak)
            if self.show_minimap:
                self._draw_minimap(out, slot, align, path, weak)
        self._draw_steering_dial(out, texts, guidance)
        if slot is not None:
            self._draw_confidence(out, texts, slot.confidence)
        self._draw_footer(texts)
        draw_texts(out, texts)
        return out

    # ------------------------------------------------------------------ 바닥 그래픽
    def _path(self, align: Alignment | None, steer_deg: float | None) -> dict[str, np.ndarray]:
        dist = 350.0
        if align is not None and align.end_dist_cm is not None:
            dist = float(np.clip(align.end_dist_cm, 60.0, 350.0))
        return predict_path(self.vehicle, steer_deg or 0.0, dist)

    def _draw_corridor(self, layer, path, guidance: Guidance | None) -> None:
        lp, rp = self._ground_pts(path["left"]), self._ground_pts(path["right"])
        if len(lp) < 2 or len(rp) < 2:
            return
        p = self._poly(np.vstack([lp, rp[::-1]]))
        color = LEVEL_COLOR.get(guidance.level, BLUE) if guidance else BLUE
        if p is not None and len(p) > 2:
            cv2.fillPoly(layer, [p], color, cv2.LINE_AA)

    def _draw_corridor_edges(self, layer, path) -> None:
        t = max(2, int(3 * self.k))
        for key in ("left", "right"):
            p = self._poly(self._ground_pts(path[key]))
            if p is not None and len(p) > 1:
                cv2.polylines(layer, [p], False, WHITE, t, cv2.LINE_AA)
        # 거리 눈금: 경로 위 0.5m / 1m / 2m
        s_total = np.concatenate([[0.0], np.hypot(*np.diff(path["center"], axis=0).T).cumsum()])
        for d, c in ((50.0, RED), (100.0, AMBER), (200.0, GREEN)):
            if s_total[-1] < d:
                continue
            i = int(np.searchsorted(s_total, d))
            a, b = path["left"][i], path["right"][i]
            if min(a[1], b[1]) < self.y_near:
                continue
            for p0, p1 in ((a, a + (b - a) * 0.18), (b, b + (a - b) * 0.18)):
                self._ground_line(layer, p0[0], p0[1], p1[0], p1[1], c, max(3, int(5 * self.k)), n=4)

    def _draw_slot(self, layer, slot: SlotEstimate, align: Alignment) -> None:
        y0 = self.y_near
        dl, dr = display_clearances(align)
        cl, cr = clearance_color(dl, self.thr), clearance_color(dr, self.thr)
        tk = max(4, int(7 * self.k))
        ends = {}
        weak = slot.confidence < LOW_CONFIDENCE
        if weak:
            cl = cr = SOFT  # 확실하지 않으면 색으로 판정을 주지 않는다
        for name, line, color in (("l", slot.left, cl), ("r", slot.right, cr)):
            ye = slot.end_y_for(line)
            ends[name] = (float(line.x_at(ye)), ye)
            if weak:
                self._ground_dashed(layer, line.x_at(y0), y0, line.x_at(ye), ye, color, tk, dash_cm=25.0)
            else:
                self._ground_line(layer, line.x_at(y0), y0, line.x_at(ye), ye, color, tk)
        # 끝선: 가까워질수록 노랑 → 빨강
        if slot.end is not None:
            d = align.end_dist_cm if align.end_dist_cm is not None else 999.0
            ec = RED if d <= 30 else AMBER if d <= 100 else WHITE
            (xl, yl), (xr, yr) = ends["l"], ends["r"]
            self._ground_line(layer, xl, yl, xr, yr, ec, tk)
        # 주차칸 중심선
        ye = slot.far_end_y
        self._ground_dashed(layer, slot.target_x_at(y0), y0, slot.target_x_at(ye), ye, AMBER,
                            max(2, int(3 * self.k)))
        # 가상 벽 수직선: 끝선과 평행한 벽 위에 세운다
        b = slot.slope
        nx, ny = 1 / np.hypot(1, b), -b / np.hypot(1, b)
        # 벽이 아주 가까우면 실제 벽이 화면에 크게 보이고 막대가 렌즈 왜곡으로 휘므로 낮추거나 생략
        h = float(min(self.wall_line_height_cm, 0.45 * ye))
        if h < 30:
            return
        for (x, y), color in ((ends["l"], cl), (ends["r"], cr)):
            self._vertical_bar(layer, x, y, h, color, 14.0, nx, ny)
        xc, yc = float(slot.target_x_at(slot.far_end_y)), 0.5 * (ends["l"][1] + ends["r"][1])
        self._vertical_bar(layer, xc, yc, h * 0.7, AMBER, 6.0, nx, ny)
        p = self._poly(np.array([[ends["l"][0], ends["l"][1], h], [ends["r"][0], ends["r"][1], h]]))
        if p is not None and len(p) == 2:
            cv2.line(layer, tuple(p[0]), tuple(p[1]), WHITE, max(2, int(3 * self.k)), cv2.LINE_AA)

    # ------------------------------------------------------------------ UI
    def _draw_badge(self, out, texts, g: Guidance | None) -> None:
        if g is None:
            return
        W, k = out.shape[1], self.k
        text = self._t(g.text_ko, g.text_en)
        size, icon, pad, bh = int(34 * k), int(56 * k), int(22 * k), int(74 * k)
        bw = icon + pad * 3 + text_width(text, size)
        x0, y0 = (W - bw) // 2, int(16 * k)
        color = LEVEL_COLOR[g.level]
        rounded_rect(out, x0 + 3, y0 + 5, x0 + bw + 3, y0 + bh + 5, bh // 2, (0, 0, 0), 0.35)  # 그림자
        rounded_rect(out, x0, y0, x0 + bw, y0 + bh, bh // 2, color, 0.93)
        cx, cy = x0 + pad + icon // 2, y0 + bh // 2
        if g.level == "stop":
            draw_stop_icon(out, cx, cy, int(icon * 0.45), WHITE)
        elif g.level == "ok":
            draw_check_icon(out, cx, cy, int(icon * 0.42), WHITE)
        elif g.key == "retry":
            draw_retry_icon(out, cx, cy, int(icon * 0.34), WHITE)
        elif g.arrow:
            draw_steering_icon(out, cx, cy + int(7 * k), int(icon * 0.28), WHITE, g.arrow)
        else:
            cv2.circle(out, (cx, cy), int(icon * 0.12), WHITE, -1, cv2.LINE_AA)
        texts.append((text, (x0 + pad * 2 + icon, cy - int(size * 0.62)), size, WHITE))
        sub = self._t(g.sub_ko, g.sub_en)
        if sub:
            ss = int(22 * k)
            sw = text_width(sub, ss)
            sx, sy = (W - sw) // 2, y0 + bh + int(12 * k)
            rounded_rect(out, sx - int(16 * k), sy - int(5 * k), sx + sw + int(16 * k), sy + int(32 * k),
                         int(18 * k), DARK, 0.62)
            texts.append((sub, (sx, sy), ss, WHITE))

    def _draw_ghost(self, layer, f: FinalPrediction) -> None:
        """고스트카: 지금 핸들 그대로 가면 멈추게 될 자리. 차 뒷면과 바닥 윤곽만 점선 느낌으로."""
        c = STATUS_COLOR[f.status]
        rl, rr, fr, fl = f.corners
        h = 95.0  # 차 뒷면 높이 (트렁크 정도)
        if min(rl[1], rr[1]) > self.y_near + 20:
            face = np.array([[rl[0], rl[1], 0], [rr[0], rr[1], 0], [rr[0], rr[1], h], [rl[0], rl[1], h]])
            ts = np.linspace(0, 1, 10)[:, None]
            edges = np.vstack([face[i] + (face[(i + 1) % 4] - face[i]) * ts for i in range(4)])
            p = self._poly(edges)
            if p is not None and len(p) > 2:
                cv2.polylines(layer, [p], True, c, max(2, int(3 * self.k)), cv2.LINE_AA)
                # 뒷유리/후미등 느낌의 가로선
                q = self._poly(np.array([[rl[0], rl[1], h * 0.55], [rr[0], rr[1], h * 0.55]]))
                if q is not None and len(q) == 2:
                    cv2.line(layer, tuple(q[0]), tuple(q[1]), c, max(1, int(2 * self.k)), cv2.LINE_AA)
        for a, b in ((rl, fl), (rr, fr)):
            self._ground_dashed(layer, a[0], a[1], b[0], b[1], c, max(2, int(3 * self.k)), dash_cm=20.0)

    def _draw_balance(self, out, texts, a: Alignment, final: FinalPrediction | None = None,
                      weak: bool = False) -> None:
        """수평계 모양 균형 게이지: 주차칸 안쪽 폭 안에서 차가 어디 있는지."""
        H, W = out.shape[:2]
        k = self.k
        pw, ph = int(660 * k), int(104 * k)
        x0, y0 = (W - pw) // 2, H - ph - int(56 * k)
        rounded_rect(out, x0, y0, x0 + pw, y0 + ph, int(24 * k), DARK, 0.65)

        dl, dr = display_clearances(a)
        cl, cr = clearance_color(dl, self.thr), clearance_color(dr, self.thr)
        if weak:  # 인식이 불확실하면 색으로 판정을 주지 않는다
            cl = cr = SOFT
        lab, num = int(17 * k), int(34 * k)
        pad = int(24 * k)
        # 후방카메라 화면은 거울처럼 보이므로 화면 왼쪽 = 운전석 쪽 (국내 차량 기준)
        texts.append((self._t("운전석 쪽", "Driver side"), (x0 + pad, y0 + int(14 * k)), lab, SOFT))
        texts.append((f"{dl:.0f}cm", (x0 + pad, y0 + int(40 * k)), num, cl))
        rl, rv = self._t("동승석 쪽", "Passenger side"), f"{dr:.0f}cm"
        texts.append((rl, (x0 + pw - pad - text_width(rl, lab), y0 + int(14 * k)), lab, SOFT))
        texts.append((rv, (x0 + pw - pad - text_width(rv, num), y0 + int(40 * k)), num, cr))

        # 트랙: 회색 = 선 바깥, 밝은 회색 = 주차칸 안쪽
        tx0, tx1 = x0 + int(175 * k), x0 + pw - int(175 * k)
        ty, th = y0 + int(28 * k), int(24 * k)
        half = self.vehicle.width_cm / 2
        inner = self.vehicle.width_cm + max(a.clear_left_rear_cm, -60) + max(a.clear_right_rear_cm, -60)
        inner = max(inner, self.vehicle.width_cm + 1)
        scale = (tx1 - tx0) / (inner + 60.0)
        mid = (tx0 + tx1) / 2  # 주차칸 중심
        car_c = (a.clear_left_rear_cm - a.clear_right_rear_cm) / 2  # 뒷범퍼 위치의 차 중심

        def X(cm):
            return int(round(mid + cm * scale))

        rounded_rect(out, tx0, ty, tx1, ty + th, th // 2, (75, 75, 75), 0.95)
        cv2.rectangle(out, (X(-inner / 2), ty), (X(inner / 2), ty + th), (120, 120, 120), -1)
        lw = max(4, int(7 * k))
        for xs, c in ((X(-inner / 2), cl), (X(inner / 2), cr)):
            cv2.rectangle(out, (xs - lw // 2, ty - int(9 * k)), (xs + lw // 2, ty + th + int(9 * k)), c, -1)
        cv2.line(out, (int(mid), ty - int(12 * k)), (int(mid), ty + th + int(12 * k)), WHITE,
                 max(1, int(2 * k)), cv2.LINE_AA)
        if a.target_bias_cm:
            # 하차 모드 목표: 노란 삼각형
            tx = X(a.target_bias_cm)
            tri = np.array([[tx, ty - int(4 * k)], [tx - int(9 * k), ty - int(18 * k)], [tx + int(9 * k), ty - int(18 * k)]])
            cv2.fillPoly(out, [tri.astype(np.int32)], AMBER, cv2.LINE_AA)
            side = self._t("운전석", "driver") if a.target_bias_cm > 0 else self._t("동승석", "passenger")
            chip = self._t(f"문콕 방지 · {side} 쪽 여유 +{abs(a.target_bias_cm):.0f}cm",
                           f"Door space · {side} side +{abs(a.target_bias_cm):.0f}cm")
            cs = int(16 * k)
            cwid = text_width(chip, cs)
            rounded_rect(out, mid - cwid / 2 - int(12 * k), y0 - int(34 * k), mid + cwid / 2 + int(12 * k),
                         y0 - int(6 * k), int(14 * k), AMBER, 0.9)
            texts.append((chip, (int(mid - cwid / 2), y0 - int(31 * k)), cs, DARK))
        # 차 (각도는 보기 쉽게 3배 과장)
        cx = (X(car_c - half) + X(car_c + half)) / 2
        cw = X(car_c + half) - X(car_c - half)
        rect = ((cx, ty + th / 2), (cw, th + int(10 * k)), float(np.clip(a.yaw_deg * 2, -10, 10)))
        box = cv2.boxPoints(rect).astype(np.int32)
        status = display_status(a, self.thr)
        sc = SOFT if weak else STATUS_COLOR[status]
        if final is not None:
            # 예상 최종 위치: 속이 빈 윤곽 (지금 위치는 채운 상자)
            fc = (final.clear_left_cm - final.clear_right_cm) / 2
            gx = (X(fc - half) + X(fc + half)) / 2
            grect = ((gx, ty + th / 2), (cw, th + int(10 * k)), float(np.clip(final.yaw_deg * 2, -10, 10)))
            gbox = cv2.boxPoints(grect).astype(np.int32)
            cv2.polylines(out, [gbox], True, STATUS_COLOR[final.status], max(1, int(2 * k)), cv2.LINE_AA)
        cv2.fillPoly(out, [box], (62, 62, 64), cv2.LINE_AA)
        cv2.polylines(out, [box], True, sc, max(2, int(3 * k)), cv2.LINE_AA)
        cv2.circle(out, (int(cx), int(ty + th / 2)), max(3, int(5 * k)), sc, -1, cv2.LINE_AA)
        if final is not None:
            # 상자가 겹치면 지금 위치가 위에 보이도록 예상 위치는 한 번 더 얇게
            cv2.polylines(out, [gbox], True, STATUS_COLOR[final.status], 1, cv2.LINE_AA)

        parts = []
        if abs(a.offset_cm) >= self.thr.good_offset_cm:
            side = self._t("오른쪽", "right") if a.offset_cm > 0 else self._t("왼쪽", "left")
            parts.append(self._t(f"{side}으로 {abs(a.offset_cm):.0f}cm 치우침",
                                 f"{abs(a.offset_cm):.0f}cm to the {side}"))
        if abs(a.yaw_deg) >= self.thr.good_yaw_deg or (status != "good" and abs(a.yaw_deg) >= 0.8):
            parts.append(self._t(f"각도 {abs(a.yaw_deg):.1f}°", f"angle {abs(a.yaw_deg):.1f}°"))
        if weak:
            info = self._t("인식이 불확실해요 · 참고만 하세요", "Uncertain · for reference only")
        elif final is not None:
            fl_, fr_ = final.clear_left_cm, final.clear_right_cm
            info = self._t(f"이대로 가면  운전석 쪽 {fl_:.0f}cm · 동승석 쪽 {fr_:.0f}cm",
                           f"If you keep this:  driver {fl_:.0f}cm · passenger {fr_:.0f}cm")
        elif parts:
            info = "  ·  ".join(parts)
        elif status == "good":
            info = self._t("목표 위치에 맞음", "On target") if a.target_bias_cm else self._t("가운데 정렬됨", "Centered")
        else:
            info = self._t("앞부분 여유를 확인하세요", "Check front clearance")
        isz = int(17 * k)
        texts.append((info, (int(mid - text_width(info, isz) / 2), ty + th + int(22 * k)), isz, SOFT))

    def _draw_minimap(self, out, slot: SlotEstimate | None, a: Alignment, path, weak: bool = False) -> None:
        """위에서 내려다본 모습. 위쪽이 차 뒤쪽(벽 방향)."""
        W = out.shape[1]
        k = self.k
        L, Wv = self.vehicle.length_cm, self.vehicle.width_cm
        sc, back = 0.36 * k, 330.0
        pw, ph = int(190 * k), int((L + back + 40) * 0.36 * k)
        x0, y0 = W - pw - int(16 * k), int(16 * k)
        rounded_rect(out, x0, y0, x0 + pw, y0 + ph, int(18 * k), DARK, 0.62)
        ox, oy = x0 + pw / 2, y0 + back * sc + int(10 * k)
        view = out[y0 + 4:y0 + ph - 4, x0 + 4:x0 + pw - 4]  # 패널 밖으로 안 나가게 잘라서 그림
        off = np.array([x0 + 4, y0 + 4])

        def P(x, y):
            return np.array([ox + x * sc, oy - y * sc]) - off

        def seg(p, q, c, t):
            cv2.line(view, tuple(np.round(p).astype(int)), tuple(np.round(q).astype(int)), c, t, cv2.LINE_AA)

        t = max(2, int(3 * k))
        if slot is not None:
            yb = -L - 40
            dl, dr = display_clearances(a)
            cols = (SOFT, SOFT) if weak else (clearance_color(dl, self.thr), clearance_color(dr, self.thr))
            for ln, c in zip((slot.left, slot.right), cols):
                ye = min(slot.end_y_for(ln), back)
                seg(P(ln.x_at(yb), yb), P(ln.x_at(ye), ye), c, t)
            if slot.end is not None and slot.far_end_y <= back:
                yl, yr = slot.end_y_for(slot.left), slot.end_y_for(slot.right)
                seg(P(slot.left.x_at(yl), yl), P(slot.right.x_at(yr), yr), WHITE, t)
        for key in ("left", "right"):
            pts = np.array([P(x, y) for x, y in path[key] if y <= back])
            if len(pts) > 1:
                cv2.polylines(view, [np.round(pts).astype(np.int32)], False, BLUE, max(1, int(2 * k)), cv2.LINE_AA)
        car = np.round([P(-Wv / 2, 0), P(Wv / 2, 0), P(Wv / 2, -L), P(-Wv / 2, -L)]).astype(np.int32)
        cv2.fillPoly(view, [car], (88, 88, 92), cv2.LINE_AA)
        car_c = SOFT if weak else STATUS_COLOR[display_status(a, self.thr)]
        cv2.polylines(view, [car], True, car_c, max(2, int(2 * k)), cv2.LINE_AA)
        seg(P(-Wv / 2 + 18, -8), P(Wv / 2 - 18, -8), (60, 60, 225), max(2, int(4 * k)))  # 후미등
        seg(P(-Wv / 2 + 22, -L * 0.62), P(Wv / 2 - 22, -L * 0.62), (150, 150, 150), max(1, int(2 * k)))

    def _draw_steering_dial(self, out, texts, g: Guidance | None, tol_deg: float = 30.0) -> None:
        """핸들 맞추기 다이얼: 흰 바늘(지금 핸들)을 초록 구간(목표)에 넣으면 된다.

        '오른쪽으로'만으로는 얼마나 돌릴지 알기 어려워서, 목표 위치를 눈으로 보여 준다.
        """
        if (g is None or g.current_steer_deg is None or g.desired_steer_deg is None
                or g.level == "stop" or g.key in ("retry", "lowconf")):
            return
        H = out.shape[0]
        k = self.k
        pw, ph = int(200 * k), int(214 * k)
        x0, y0 = int(16 * k), int(H * 0.40)
        rounded_rect(out, x0, y0, x0 + pw, y0 + ph, int(20 * k), DARK, 0.62)
        cx, cy, R = x0 + pw // 2, y0 + int(118 * k), int(66 * k)
        full = self.vehicle.max_steer_wheel_deg

        def ang(w):  # 핸들 각도 → 화면 각도 (위쪽 = -90°)
            return -90.0 + float(np.clip(w, -full, full)) / full * 135.0

        t = max(6, int(10 * k))
        cv2.ellipse(out, (cx, cy), (R, R), 0, -225, 45, (90, 90, 90), t, cv2.LINE_AA)
        d, cur = g.desired_steer_deg, g.current_steer_deg
        cv2.ellipse(out, (cx, cy), (R, R), 0, ang(d - tol_deg), ang(d + tol_deg), GREEN, t + 2, cv2.LINE_AA)
        # 가운데(핸들 똑바로) 눈금
        cv2.line(out, (cx, cy - R - t), (cx, cy - R + t), SOFT, max(1, int(2 * k)), cv2.LINE_AA)
        # 지금 핸들 모양을 실제 회전각대로 그려서 직관적으로
        self._wheel_icon(out, cx, cy, int(R * 0.55), cur)
        a = np.radians(ang(cur))
        tip = (int(cx + (R + t) * np.cos(a)), int(cy + (R + t) * np.sin(a)))
        base = (int(cx + (R - t * 1.6) * np.cos(a)), int(cy + (R - t * 1.6) * np.sin(a)))
        cv2.line(out, base, tip, WHITE, max(3, int(5 * k)), cv2.LINE_AA)

        texts.append((self._t("핸들 맞추기", "Wheel target"), (x0 + int(18 * k), y0 + int(12 * k)), int(17 * k), SOFT))
        diff = d - cur
        if abs(diff) < tol_deg:
            msg, col = self._t("딱 맞아요", "On target"), GREEN
        else:
            side = self._t("오른쪽", "right") if diff > 0 else self._t("왼쪽", "left")
            msg = self._t(f"{side} {turns_phrase_ko(diff)}", f"{turns_phrase_en(diff)} {side}")
            col = AMBER
        ms = int(20 * k)
        texts.append((msg, (cx - text_width(msg, ms) // 2, y0 + ph - int(36 * k)), ms, col))

    def _wheel_icon(self, out, cx, cy, r, rot_deg) -> None:
        t = max(2, r // 6)
        cv2.circle(out, (cx, cy), r, SOFT, t, cv2.LINE_AA)
        for base in (180.0, 0.0, 90.0):  # 9시·3시·6시 방향 살
            a = np.radians(base + rot_deg)
            cv2.line(out, (cx, cy), (int(cx + r * np.cos(a)), int(cy + r * np.sin(a))), SOFT, t, cv2.LINE_AA)
        a = np.radians(-90 + rot_deg)  # 12시 표시점: 몇 바퀴 돌았는지 보이게
        cv2.circle(out, (int(cx + r * np.cos(a)), int(cy + r * np.sin(a))), max(3, t), AMBER, -1, cv2.LINE_AA)
        cv2.circle(out, (cx, cy), max(3, r // 4), SOFT, -1, cv2.LINE_AA)

    def _draw_confidence(self, out, texts, conf: float) -> None:
        """오른쪽 아래: 인식 신뢰도 막대 4칸 (휴대폰 신호 표시처럼)."""
        H, W = out.shape[:2]
        k = self.k
        n = int(np.clip(np.ceil(conf * 4 - 1e-6), 0, 4))
        col = GREEN if n >= 3 else AMBER if n == 2 else RED
        label = self._t("인식", "Lines")
        ls = int(15 * k)
        bx = W - int(16 * k) - int(4 * 11 * k)
        by = H - int(16 * k)
        for i in range(4):
            h = int((6 + i * 4) * k)
            x = bx + int(i * 11 * k)
            c = col if i < n else (90, 90, 90)
            cv2.rectangle(out, (x, by - h), (x + int(7 * k), by), c, -1)
        texts.append((label, (bx - text_width(label, ls) - int(8 * k), by - int(18 * k)), ls, SOFT))

    def _draw_footer(self, texts) -> None:
        k, H = self.k, self.camera.height
        txt = self._t(f"{self.vehicle.name} · 폭 {self.vehicle.width_cm:.0f}cm   ·   주변을 직접 확인하세요",
                      f"{self.vehicle.name} · {self.vehicle.width_cm:.0f}cm wide   ·   Always check surroundings")
        texts.append((txt, (int(16 * k), H - int(30 * k)), int(15 * k), SOFT))


# ---------------------------------------------------------------------- 주차 리포트 카드
def render_report(report, width: int = 1280, height: int = 720, background: np.ndarray | None = None) -> np.ndarray:
    """주차가 끝나면 보여 주는 리포트 카드. report: report.ParkingReport"""
    k = width / 1280.0
    if background is not None:
        out = cv2.GaussianBlur(cv2.resize(background, (width, height)), (0, 0), 12)
        out = (out * 0.35).astype(np.uint8)
    else:
        out = np.full((height, width, 3), (24, 24, 26), np.uint8)
    texts: list = []
    cw, ch = int(1040 * k), int(560 * k)
    x0, y0 = (width - cw) // 2, (height - ch) // 2
    rounded_rect(out, x0, y0, x0 + cw, y0 + ch, int(28 * k), (40, 40, 44), 0.96)

    # 왼쪽: 점수 링
    cx, cy, R = x0 + int(200 * k), y0 + int(230 * k), int(120 * k)
    color = GREEN if report.score >= 75 else AMBER if report.score >= 55 else RED
    cv2.circle(out, (cx, cy), R, (70, 70, 74), int(18 * k), cv2.LINE_AA)
    cv2.ellipse(out, (cx, cy), (R, R), -90, 0, 360 * report.score / 100, color, int(18 * k), cv2.LINE_AA)
    sc = str(report.score)
    ss = int(84 * k)
    texts.append((sc, (cx - text_width(sc, ss) // 2, cy - int(60 * k)), ss, WHITE))
    texts.append(("점", (cx + text_width(sc, ss) // 2 + int(4 * k), cy + int(2 * k)), int(24 * k), SOFT))
    gs = int(34 * k)
    texts.append((report.grade_ko, (cx - text_width(report.grade_ko, gs) // 2, cy + R + int(26 * k)), gs, color))
    if report.trend_ko:
        ts = int(18 * k)
        texts.append((report.trend_ko, (cx - text_width(report.trend_ko, ts) // 2, cy + R + int(74 * k)), ts, SOFT))

    # 오른쪽: 항목
    mx = x0 + int(420 * k)
    texts.append(("이번 주차", (mx, y0 + int(36 * k)), int(26 * k), WHITE))
    side = "오른쪽" if report.offset_cm > 0 else "왼쪽"
    ref = "목표 " if report.target_bias_cm else "가운데 "
    rows = [
        (f"{ref}치우침", "딱 맞음" if abs(report.offset_cm) < 3 else f"{side} {abs(report.offset_cm):.0f}cm",
         abs(report.offset_cm) < 8),
        ("각도", f"{abs(report.yaw_deg):.1f}°", abs(report.yaw_deg) < 1.5),
        ("운전석 쪽 / 동승석 쪽", f"{report.clear_driver_cm:.0f}cm / {report.clear_passenger_cm:.0f}cm",
         min(report.clear_driver_cm, report.clear_passenger_cm) >= 5),
        ("걸린 시간 · 핸들 수정", f"{report.duration_s:.0f}초 · {report.corrections}번", report.corrections <= 6),
    ]
    ry = y0 + int(92 * k)
    for label, value, ok in rows:
        texts.append((label, (mx, ry), int(19 * k), SOFT))
        vs = int(24 * k)
        texts.append((value, (x0 + cw - int(48 * k) - text_width(value, vs), ry - int(3 * k)), vs, GREEN if ok else AMBER))
        cv2.line(out, (mx, ry + int(40 * k)), (x0 + cw - int(48 * k), ry + int(40 * k)), (64, 64, 68), 1)
        ry += int(56 * k)

    # 조언 한 줄
    tip = report.tips_ko[0] if report.tips_ko else ""
    ty = ry + int(14 * k)
    rounded_rect(out, mx, ty, x0 + cw - int(48 * k), ty + int(88 * k), int(16 * k), (60, 74, 54), 0.95)
    texts.append(("TIP", (mx + int(18 * k), ty + int(10 * k)), int(16 * k), GREEN))
    tsz = int(19 * k)
    # 길면 두 줄로
    max_w = cw - int(48 * k) - (mx - x0) - int(36 * k)
    line1, line2 = tip, ""
    if text_width(tip, tsz) > max_w:
        words = tip.split(" ")
        line1 = ""
        for i, w_ in enumerate(words):
            if text_width((line1 + " " + w_).strip(), tsz) > max_w:
                line2 = " ".join(words[i:])
                break
            line1 = (line1 + " " + w_).strip()
    texts.append((line1, (mx + int(18 * k), ty + int(32 * k)), tsz, WHITE))
    if line2:
        texts.append((line2, (mx + int(18 * k), ty + int(57 * k)), tsz, WHITE))

    # 아래: 주차선 상태 (지자체 공유)
    if report.line is not None:
        ln = report.line
        lc = GREEN if ln.label_ko == "양호" else AMBER if ln.label_ko == "약간 닳음" else RED
        chip = f"이 주차칸의 선 상태: {ln.label_ko} (페인트 {ln.worst_coverage * 100:.0f}%)"
        cs = int(17 * k)
        by = y0 + ch - int(56 * k)
        rounded_rect(out, x0 + int(40 * k), by, x0 + int(40 * k) + text_width(chip, cs) + int(36 * k),
                     by + int(34 * k), int(16 * k), lc, 0.9)
        texts.append((chip, (x0 + int(58 * k), by + int(6 * k)), cs, DARK))
        note = "익명으로 지자체 주차선 관리에 보탤 수 있어요"
        texts.append((note, (x0 + int(64 * k) + text_width(chip, cs) + int(36 * k), by + int(8 * k)), int(15 * k), SOFT))
    draw_texts(out, texts)
    return out
