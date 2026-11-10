"""후방카메라 화면 위에 주차 가이드를 그린다.

- 가상 벽 수직선: 주차칸 좌우 선이 끝나는 지점(벽 쪽)에 세로 기준선을 세운다.
  공주시처럼 벽에 칠하던 수직 주차선을 화면에서 대신하는 부분이다.
- 바닥 주차선 강조: 인식한 좌우 주차선을 좌우 여유에 따라 초록/노랑/빨강으로 표시한다.
- 차폭 가이드: 현재 차의 양옆이 직진 후진했을 때 지나갈 선과 거리 눈금.
- HUD: 좌우 여유 거리, 치우침/각도, 위에서 본 미니맵.
"""
from __future__ import annotations

import cv2
import numpy as np

from .camera import CameraModel
from .detector import Alignment, AlignmentThresholds, SlotEstimate
from .text import draw_texts, korean_available, text_width
from .vehicles import VehicleSpec

GREEN = (90, 220, 90)
YELLOW = (40, 210, 250)
RED = (60, 60, 240)
WHITE = (245, 245, 245)
CYAN = (230, 210, 60)
GRAY = (170, 170, 170)


def clearance_color(c: float, thr: AlignmentThresholds) -> tuple[int, int, int]:
    if c < 0:
        return RED
    if c < thr.warn_clearance_cm:
        return YELLOW
    return GREEN


def status_color(status: str) -> tuple[int, int, int]:
    return {"good": GREEN, "adjust": YELLOW, "danger": RED}.get(status, WHITE)


def _visible_y_min(camera: CameraModel) -> float:
    """영상 맨 아래 행에 찍히는 지면까지의 거리."""
    h = camera.mount_height_cm
    t = np.radians(camera.pitch_deg)
    k = (camera.height - camera.cy) / camera.focal_px
    return float(h * (np.cos(t) - k * np.sin(t)) / (np.sin(t) + k * np.cos(t)))


class GuideRenderer:
    def __init__(self, camera: CameraModel, vehicle: VehicleSpec,
                 thresholds: AlignmentThresholds | None = None, lang: str = "auto") -> None:
        self.camera, self.vehicle = camera, vehicle
        self.thr = thresholds or AlignmentThresholds()
        self.lang = ("ko" if korean_available() else "en") if lang == "auto" else lang
        self.y_near = _visible_y_min(camera) + 5.0
        self.wall_line_height_cm = 110.0

    # ------------------------------------------------------------------ 기본 도형
    def _ground_seg(self, layer, x0, y0, x1, y1, color, thick, dashed=False) -> None:
        y0, y1 = max(y0, self.y_near), max(y1, self.y_near)
        if abs(y1 - y0) < 1 and abs(x1 - x0) < 1:
            return
        if dashed:
            n = 12
            ts = np.linspace(0, 1, n * 2 + 1)
            for i in range(0, n * 2, 2):
                xa, ya = x0 + (x1 - x0) * ts[i], y0 + (y1 - y0) * ts[i]
                xb, yb = x0 + (x1 - x0) * ts[i + 1], y0 + (y1 - y0) * ts[i + 1]
                self._ground_seg(layer, xa, ya, xb, yb, color, thick)
            return
        uv, ok = self.camera.project_ground(np.array([[x0, y0], [x1, y1]]))
        if ok.all():
            p = np.round(uv).astype(int)
            cv2.line(layer, tuple(p[0]), tuple(p[1]), color, thick, cv2.LINE_AA)

    def _vertical_bar(self, layer, x, y, z0, z1, color, width_cm) -> None:
        pts = np.array([[x - width_cm / 2, y, z0], [x + width_cm / 2, y, z0],
                        [x + width_cm / 2, y, z1], [x - width_cm / 2, y, z1]])
        uv, ok = self.camera.project(pts)
        if ok.all():
            cv2.fillPoly(layer, [np.round(uv).astype(np.int32)], color, cv2.LINE_AA)

    # ------------------------------------------------------------------ 그리기
    def render(self, img: np.ndarray, slot: SlotEstimate | None, align: Alignment | None) -> np.ndarray:
        out = img.copy()
        layer = np.zeros_like(out)
        self._draw_vehicle_guides(layer, align)
        if slot is not None and align is not None:
            self._draw_slot(layer, slot, align)
        mask = layer.any(axis=2)
        blended = cv2.addWeighted(out, 0.35, layer, 0.85, 0)
        out[mask] = blended[mask]
        self._draw_hud(out, slot, align)
        return out

    def _draw_vehicle_guides(self, layer, align: Alignment | None) -> None:
        half = self.vehicle.width_cm / 2
        far = 400.0
        color = WHITE
        self._ground_seg(layer, -half, self.y_near, -half, far, color, 3)
        self._ground_seg(layer, half, self.y_near, half, far, color, 3)
        for d, c in ((100.0, RED), (200.0, YELLOW), (300.0, GREEN)):
            self._ground_seg(layer, -half, d, -half + 35, d, c, 3)
            self._ground_seg(layer, half - 35, d, half, d, c, 3)
        self._ground_seg(layer, 0, self.y_near, 0, 250.0, GRAY, 1, dashed=True)

    def _draw_slot(self, layer, slot: SlotEstimate, align: Alignment) -> None:
        y_end = slot.far_end_y
        y0 = self.y_near
        cl = clearance_color(align.clear_left_cm, self.thr)
        cr = clearance_color(align.clear_right_cm, self.thr)
        for line, color in ((slot.left, cl), (slot.right, cr)):
            self._ground_seg(layer, line.x_at(y0), y0, line.x_at(y_end), y_end, color, 6)
        # 주차칸 중심선
        self._ground_seg(layer, slot.center_x_at(y0), y0, slot.center_x_at(y_end), y_end, YELLOW, 2, dashed=True)
        # 가상 벽 수직선 (벽에 칠하던 선을 대신함)
        h = self.wall_line_height_cm
        for line, color in ((slot.left, cl), (slot.right, cr)):
            self._vertical_bar(layer, float(line.x_at(y_end)), y_end, 0.0, h, color, 12.0)
        self._vertical_bar(layer, float(slot.center_x_at(y_end)), y_end, 0.0, h * 0.75, YELLOW, 5.0)
        # 벽 위 가로 연결선
        uv, ok = self.camera.project(np.array([[slot.left.x_at(y_end), y_end, h],
                                               [slot.right.x_at(y_end), y_end, h]]))
        if ok.all():
            p = np.round(uv).astype(int)
            cv2.line(layer, tuple(p[0]), tuple(p[1]), WHITE, 2, cv2.LINE_AA)

    # ------------------------------------------------------------------ HUD
    def _t(self, ko: str, en: str) -> str:
        return ko if self.lang == "ko" else en

    def _draw_hud(self, out: np.ndarray, slot: SlotEstimate | None, align: Alignment | None) -> None:
        H, W = out.shape[:2]
        texts = []
        # 상단 정보 패널
        panel_w, panel_h = 440, 112
        roi = out[12:12 + panel_h, 12:12 + panel_w]
        roi[:] = (roi * 0.35).astype(np.uint8)
        texts.append((self._t("후방 주차 가이드", "Rear parking guide"), (26, 18), 22, WHITE))
        texts.append((f"{self.vehicle.name}  {self.vehicle.width_cm:.0f}cm", (300, 22), 16, GRAY))

        if align is None:
            texts.append((self._t("주차선을 찾는 중...", "Searching for slot lines..."), (26, 70), 30, GRAY))
        else:
            cl = clearance_color(align.clear_left_cm, self.thr)
            cr = clearance_color(align.clear_right_cm, self.thr)
            texts.append((self._t("왼쪽 여유", "Left"), (26, 52), 18, GRAY))
            texts.append((f"{align.clear_left_cm:+.0f} cm", (26, 74), 34, cl))
            texts.append((self._t("오른쪽 여유", "Right"), (250, 52), 18, GRAY))
            texts.append((f"{align.clear_right_cm:+.0f} cm", (250, 74), 34, cr))
            msgs = align.messages_ko() if self.lang == "ko" else align.messages_en()
            self._draw_banner(out, texts, "  ·  ".join(msgs), status_color(align.status))
            self._draw_minimap(out, slot, align)
        draw_texts(out, texts)

    @staticmethod
    def _draw_banner(out: np.ndarray, texts: list, msg: str, color) -> None:
        """화면 아래(범퍼 위) 가운데에 상태 문구."""
        H, W = out.shape[:2]
        size = 26
        bw = min(W - 40, text_width(msg, size) + 40)
        x0, y0 = (W - bw) // 2, H - 70
        roi = out[y0:y0 + 48, x0:x0 + bw]
        roi[:] = (roi * 0.3).astype(np.uint8)
        cv2.rectangle(out, (x0, y0), (x0 + bw, y0 + 48), color, 2, cv2.LINE_AA)
        texts.append((msg, (x0 + 20, y0 + 9), size, color))

    def _draw_minimap(self, out: np.ndarray, slot: SlotEstimate | None, align: Alignment) -> None:
        """위에서 내려다본 모습. 위쪽이 차 뒤쪽(벽 방향)."""
        H, W = out.shape[:2]
        k = 0.45  # px/cm
        L, Wv = self.vehicle.length_cm, self.vehicle.width_cm
        pw, ph = 200, int((L + 300) * k)
        x0, y0 = W - pw - 12, 12
        roi = out[y0:y0 + ph, x0:x0 + pw]
        roi[:] = (roi * 0.3).astype(np.uint8)
        ox, oy = x0 + pw // 2, y0 + int(270 * k)  # 뒷범퍼 위치

        def P(x, y):
            return int(round(ox + x * k)), int(round(oy - y * k))

        if slot is not None:
            ys = (-L - 25, 270)
            for line, c in ((slot.left, clearance_color(align.clear_left_cm, self.thr)),
                            (slot.right, clearance_color(align.clear_right_cm, self.thr))):
                cv2.line(out, P(line.x_at(ys[0]), ys[0]), P(line.x_at(ys[1]), ys[1]), c, 3, cv2.LINE_AA)
            yw = min(slot.far_end_y, 270)
            cv2.line(out, P(slot.left.x_at(yw), yw), P(slot.right.x_at(yw), yw), GRAY, 1, cv2.LINE_AA)
        car = np.array([P(-Wv / 2, 0), P(Wv / 2, 0), P(Wv / 2, -L), P(-Wv / 2, -L)], np.int32)
        cv2.fillPoly(out, [car], (80, 80, 80))
        cv2.polylines(out, [car], True, status_color(align.status), 2, cv2.LINE_AA)
        cv2.line(out, P(-Wv / 2 + 15, -10), P(Wv / 2 - 15, -10), (40, 40, 200), 3)  # 후미등
