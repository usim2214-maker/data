"""후방카메라 핀홀 모델과 지면(바닥) ↔ 영상 좌표 변환.

좌표계 (차량 기준, 단위 cm)
- 원점: 뒷범퍼 중앙 바로 아래 지면
- X: 화면 오른쪽(+). 후방카메라 화면은 거울처럼 좌우가 뒤집혀 나오므로
     화면 오른쪽 = 운전자 기준 오른쪽이 되도록 정의했다.
- Y: 차량 뒤쪽(+). 차체는 Y ∈ [-차량길이, 0] 구간에 있다.
- Z: 위쪽(+)
"""
from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np


@dataclass
class CameraModel:
    width: int = 1280
    height: int = 720
    focal_px: float = 560.0  # 수평 화각 약 97°
    mount_height_cm: float = 100.0
    pitch_deg: float = 30.0  # 아래로 숙인 각도

    @property
    def cx(self) -> float:
        return self.width / 2.0

    @property
    def cy(self) -> float:
        return self.height / 2.0

    @property
    def horizon_v(self) -> float:
        """무한히 먼 지면이 찍히는 영상 행(row)."""
        return self.cy - self.focal_px * np.tan(np.radians(self.pitch_deg))

    def ground_to_image_h(self) -> np.ndarray:
        """지면 (X, Y, 1) → 영상 (u, v, 1) 호모그래피."""
        f, cx, cy, h = self.focal_px, self.cx, self.cy, self.mount_height_cm
        t = np.radians(self.pitch_deg)
        s, c = np.sin(t), np.cos(t)
        return np.array(
            [
                [f, cx * c, cx * h * s],
                [0.0, -f * s + cy * c, f * h * c + cy * h * s],
                [0.0, c, h * s],
            ],
            dtype=np.float64,
        )

    def project(self, pts: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        """3D 점 (N,3) [X, Y, Z] → 영상 좌표 (N,2), 카메라 앞에 있는지 여부 (N,)."""
        pts = np.asarray(pts, dtype=np.float64).reshape(-1, 3)
        t = np.radians(self.pitch_deg)
        s, c = np.sin(t), np.cos(t)
        x, y, z = pts[:, 0], pts[:, 1], pts[:, 2] - self.mount_height_cm
        xc = x
        yc = -y * s - z * c
        zc = y * c - z * s
        valid = zc > 1e-3
        zc_safe = np.where(valid, zc, 1e-3)
        u = self.cx + self.focal_px * xc / zc_safe
        v = self.cy + self.focal_px * yc / zc_safe
        return np.stack([u, v], axis=1), valid

    def project_ground(self, xy: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        xy = np.asarray(xy, dtype=np.float64).reshape(-1, 2)
        return self.project(np.column_stack([xy, np.zeros(len(xy))]))


@dataclass
class BevConfig:
    """조감도(Bird's-Eye View) 영역. 위쪽이 차량에서 먼 쪽이다."""

    x_min_cm: float = -320.0
    x_max_cm: float = 320.0
    y_min_cm: float = 50.0
    y_max_cm: float = 750.0
    px_per_cm: float = 2.0

    @property
    def size(self) -> tuple[int, int]:
        w = int(round((self.x_max_cm - self.x_min_cm) * self.px_per_cm))
        h = int(round((self.y_max_cm - self.y_min_cm) * self.px_per_cm))
        return w, h

    def bev_to_ground(self) -> np.ndarray:
        s = self.px_per_cm
        return np.array(
            [[1.0 / s, 0.0, self.x_min_cm], [0.0, -1.0 / s, self.y_max_cm], [0.0, 0.0, 1.0]]
        )

    def pixels_to_ground(self, cols: np.ndarray, rows: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
        s = self.px_per_cm
        return self.x_min_cm + cols / s, self.y_max_cm - rows / s


@dataclass
class Projector:
    """카메라 영상 ↔ 조감도 변환을 묶어 둔 객체."""

    camera: CameraModel = field(default_factory=CameraModel)
    bev: BevConfig = field(default_factory=BevConfig)

    def __post_init__(self) -> None:
        self.h_ground_to_img = self.camera.ground_to_image_h()
        self.h_bev_to_img = self.h_ground_to_img @ self.bev.bev_to_ground()
        self.h_img_to_bev = np.linalg.inv(self.h_bev_to_img)
