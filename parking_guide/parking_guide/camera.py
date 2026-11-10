"""후방카메라 모델(핀홀 / 어안)과 지면 ↔ 영상 ↔ 조감도 변환.

좌표계 (차량 기준, 단위 cm)
- 원점: 뒷범퍼 중앙 바로 아래 지면
- X: 화면 오른쪽(+). 후방카메라 화면은 거울처럼 좌우가 뒤집혀 나오므로
     화면 오른쪽 = 운전자 기준 오른쪽이 되도록 정의했다.
- Y: 차량 뒤쪽(+). 차체는 Y ∈ [-차량길이, 0] 구간에 있다.
- Z: 위쪽(+)

어안 렌즈는 OpenCV fisheye(등거리 + k1~k4) 모델과 같은 식을 쓴다.
실제 차량에서는 체커보드 보정(cv2.fisheye.calibrate)으로 focal/dist를 구해 넣으면 된다.
"""
from __future__ import annotations

from dataclasses import dataclass, field

import cv2
import numpy as np


@dataclass
class CameraModel:
    width: int = 1280
    height: int = 720
    focal_px: float = 560.0  # 핀홀: 수평 화각 약 97°
    mount_height_cm: float = 100.0
    pitch_deg: float = 30.0  # 아래로 숙인 각도
    fisheye: bool = False
    dist: tuple[float, float, float, float] = (0.0, 0.0, 0.0, 0.0)  # fisheye k1..k4

    @classmethod
    def wide_angle(cls, **kw) -> "CameraModel":
        """일반적인 후방카메라와 비슷한 광각(어안) 설정."""
        base = dict(focal_px=430.0, fisheye=True, dist=(0.02, -0.01, 0.0, 0.0))
        base.update(kw)
        return cls(**base)

    @property
    def cx(self) -> float:
        return self.width / 2.0

    @property
    def cy(self) -> float:
        return self.height / 2.0

    def _axes(self):
        t = np.radians(self.pitch_deg)
        s, c = np.sin(t), np.cos(t)
        right = np.array([1.0, 0.0, 0.0])
        down = np.array([0.0, -s, -c])
        fwd = np.array([0.0, c, -s])
        return right, down, fwd

    # ------------------------------------------------------------------ 렌즈 모델
    def _distort(self, xn: np.ndarray, yn: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        """정규화 좌표(z=1 평면) → 영상 픽셀."""
        if self.fisheye:
            r = np.hypot(xn, yn)
            th = np.arctan(r)
            k1, k2, k3, k4 = self.dist
            th2 = th * th
            thd = th * (1 + k1 * th2 + k2 * th2 ** 2 + k3 * th2 ** 3 + k4 * th2 ** 4)
            scale = np.where(r > 1e-9, thd / np.maximum(r, 1e-9), 1.0)
            xn, yn = xn * scale, yn * scale
        return self.cx + self.focal_px * xn, self.cy + self.focal_px * yn

    def pixel_to_ray(self, u: np.ndarray, v: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
        """영상 픽셀 → 정규화 좌표 (xn, yn)와 유효 여부."""
        xd = (np.asarray(u, np.float64) - self.cx) / self.focal_px
        yd = (np.asarray(v, np.float64) - self.cy) / self.focal_px
        if not self.fisheye:
            return xd, yd, np.ones_like(xd, bool)
        thd = np.hypot(xd, yd)
        k1, k2, k3, k4 = self.dist
        th = thd.copy()
        for _ in range(8):  # 뉴턴법으로 θd → θ
            th2 = th * th
            f = th * (1 + k1 * th2 + k2 * th2 ** 2 + k3 * th2 ** 3 + k4 * th2 ** 4) - thd
            df = 1 + 3 * k1 * th2 + 5 * k2 * th2 ** 2 + 7 * k3 * th2 ** 3 + 9 * k4 * th2 ** 4
            th = th - f / df
        valid = (th > -1e-6) & (th < np.radians(88))
        r = np.tan(np.clip(th, 0, np.radians(88)))
        scale = np.where(thd > 1e-9, r / np.maximum(thd, 1e-9), 1.0)
        return xd * scale, yd * scale, valid

    # ------------------------------------------------------------------ 투영
    def project(self, pts: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        """3D 점 (N,3) [X, Y, Z] → 영상 좌표 (N,2), 카메라 앞에 있는지 여부 (N,)."""
        pts = np.asarray(pts, dtype=np.float64).reshape(-1, 3)
        right, down, fwd = self._axes()
        d = pts - np.array([0.0, 0.0, self.mount_height_cm])
        xc, yc, zc = d @ right, d @ down, d @ fwd
        valid = zc > 1e-3
        zc = np.where(valid, zc, 1e-3)
        u, v = self._distort(xc / zc, yc / zc)
        return np.stack([u, v], axis=1), valid

    def project_ground(self, xy: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        xy = np.asarray(xy, dtype=np.float64).reshape(-1, 2)
        return self.project(np.column_stack([xy, np.zeros(len(xy))]))

    def pixel_rays_world(self) -> tuple[np.ndarray, np.ndarray]:
        """영상의 모든 픽셀에 대한 월드 방향 벡터 (H,W,3)와 유효 여부."""
        vv, uu = np.mgrid[0:self.height, 0:self.width].astype(np.float64)
        xn, yn, valid = self.pixel_to_ray(uu, vv)
        right, down, fwd = self._axes()
        dirs = xn[..., None] * right + yn[..., None] * down + fwd
        return dirs, valid

    def visible_y_min(self) -> float:
        """영상 맨 아래 가운데 픽셀에 찍히는 지면까지의 거리."""
        xn, yn, _ = self.pixel_to_ray(np.array([self.cx]), np.array([self.height - 1.0]))
        right, down, fwd = self._axes()
        d = xn[0] * right + yn[0] * down + fwd
        t = self.mount_height_cm / -d[2]
        return float(t * d[1])


@dataclass
class BevConfig:
    """조감도(Bird's-Eye View) 영역. 위쪽이 차량에서 먼 쪽이다."""

    x_min_cm: float = -320.0
    x_max_cm: float = 320.0
    y_min_cm: float = 40.0
    y_max_cm: float = 750.0
    px_per_cm: float = 2.0

    @property
    def size(self) -> tuple[int, int]:
        w = int(round((self.x_max_cm - self.x_min_cm) * self.px_per_cm))
        h = int(round((self.y_max_cm - self.y_min_cm) * self.px_per_cm))
        return w, h

    def pixels_to_ground(self, cols: np.ndarray, rows: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        s = self.px_per_cm
        return self.x_min_cm + cols / s, self.y_max_cm - rows / s

    def ground_to_pixels(self, x: np.ndarray, y: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        s = self.px_per_cm
        return (np.asarray(x) - self.x_min_cm) * s, (self.y_max_cm - np.asarray(y)) * s


@dataclass
class Projector:
    """카메라 영상 → 조감도 변환. 렌즈 왜곡 보정과 원근 보정을 remap 한 번으로 처리한다."""

    camera: CameraModel = field(default_factory=CameraModel)
    bev: BevConfig = field(default_factory=BevConfig)
    ignore_bottom_ratio: float = 0.06

    def __post_init__(self) -> None:
        w, h = self.bev.size
        rows, cols = np.mgrid[0:h, 0:w].astype(np.float64)
        gx, gy = self.bev.pixels_to_ground(cols, rows)
        uv, ok = self.camera.project_ground(np.column_stack([gx.ravel(), gy.ravel()]))
        self.map_u = uv[:, 0].reshape(h, w).astype(np.float32)
        self.map_v = uv[:, 1].reshape(h, w).astype(np.float32)
        cam = self.camera
        inside = (
            ok.reshape(h, w)
            & (self.map_u >= 0) & (self.map_u <= cam.width - 1)
            & (self.map_v >= 0) & (self.map_v <= cam.height * (1 - self.ignore_bottom_ratio))
        )
        self.valid = cv2.erode(inside.astype(np.uint8) * 255, np.ones((7, 7), np.uint8))

    def to_bev(self, img: np.ndarray) -> np.ndarray:
        return cv2.remap(img, self.map_u, self.map_v, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT)
