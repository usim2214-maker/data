"""합성 후방카메라 영상 생성기.

주차장 바닥(아스팔트 + 주차선)을 '주차칸 좌표계'에 한 번 그려 두고,
차량 자세(좌우 치우침, 각도, 주차칸 입구까지 거리)에 따라 후방카메라 시점으로 투영한다.
주차칸 끝에는 아무 선도 칠하지 않은 벽을 세운다. 공주시처럼 벽에 칠하던 수직선을
화면에 대신 그려 주는 것이 이 프로젝트의 목표이기 때문이다.

주차칸 좌표계 (단위 cm)
- s_x: 주차칸 중심선 기준 좌우 (+ = 화면 오른쪽)
- s_y: 주차칸 입구 기준 안쪽 방향 (+ = 벽 쪽)
"""
from __future__ import annotations

from dataclasses import dataclass, field

import cv2
import numpy as np

from .camera import CameraModel


@dataclass
class LotLayout:
    slot_width_cm: float = 250.0  # 주차선 중심 간 거리
    slot_length_cm: float = 500.0
    line_width_cm: float = 15.0
    n_adjacent: int = 2  # 좌우로 더 그릴 이웃 주차칸 수
    end_line: bool = True  # 주차칸 끝 가로선
    wall_gap_cm: float = 40.0  # 끝선과 벽 사이 거리
    wall: bool = True
    line_wear: float = 0.0  # 0~1, 주차선이 닳아 끊긴 정도
    stains: int = 12  # 기름 얼룩 개수
    shadow: bool = False  # 옆 기둥 그림자


@dataclass
class CarPose:
    lateral_offset_cm: float = 0.0  # 뒷범퍼 위치에서 차 중심이 주차칸 중심보다 오른쪽이면 +
    yaw_deg: float = 0.0  # 차량 축 대비 주차칸 축이 시계방향(화면 기준)으로 돌아간 각
    entry_dist_cm: float = 150.0  # 뒷범퍼에서 주차칸 입구까지 거리 (음수면 이미 들어감)


@dataclass
class Photometric:
    brightness: float = 1.0
    noise_sigma: float = 4.0
    blur: float = 0.8
    vignette: float = 0.35


@dataclass
class GroundTruth:
    lateral_offset_cm: float
    yaw_deg: float
    slot_width_cm: float
    line_width_cm: float
    entry_dist_cm: float
    wall_dist_cm: float  # 주차칸 축을 따라 뒷범퍼에서 벽까지 거리


class ParkingLotWorld:
    """주차장 바닥 텍스처를 한 번만 만들고 여러 자세로 렌더링한다 (영상 프레임용)."""

    PX_PER_CM = 2.0

    def __init__(self, layout: LotLayout | None = None, seed: int = 0) -> None:
        self.layout = layout or LotLayout()
        self.rng = np.random.default_rng(seed)
        lay = self.layout
        self.sx_min = -(lay.n_adjacent + 1.5) * lay.slot_width_cm
        self.sx_max = -self.sx_min
        self.sy_min = -900.0
        self.sy_max = lay.slot_length_cm + lay.wall_gap_cm + 60.0
        self.canvas = self._build_canvas()

    # ------------------------------------------------------------------ 바닥 텍스처
    def _to_canvas(self, sx: np.ndarray, sy: np.ndarray) -> np.ndarray:
        s = self.PX_PER_CM
        return np.stack([(sx - self.sx_min) * s, (sy - self.sy_min) * s], axis=-1)

    def _build_canvas(self) -> np.ndarray:
        lay, rng, s = self.layout, self.rng, self.PX_PER_CM
        w = int((self.sx_max - self.sx_min) * s)
        h = int((self.sy_max - self.sy_min) * s)

        # 아스팔트: 저주파 + 고주파 노이즈
        base = rng.normal(0, 1, (h // 16 + 1, w // 16 + 1)).astype(np.float32)
        low = cv2.resize(base, (w, h), interpolation=cv2.INTER_CUBIC) * 6.0
        grain = cv2.GaussianBlur(rng.normal(0, 9, (h, w)).astype(np.float32), (0, 0), 0.8)
        gray = 78.0 + low + grain

        for _ in range(lay.stains):
            cx, cy = rng.uniform(0, w), rng.uniform(0, h)
            ax, ay = rng.uniform(15, 70) * s, rng.uniform(10, 45) * s
            stain = np.zeros((h, w), np.float32)
            cv2.ellipse(stain, (int(cx), int(cy)), (int(ax), int(ay)), rng.uniform(0, 180), 0, 360, 1.0, -1)
            gray -= cv2.GaussianBlur(stain, (0, 0), 6 * s) * rng.uniform(10, 25)

        lines = np.zeros((h, w), np.float32)
        half = lay.line_width_cm / 2.0
        top = lay.slot_length_cm
        for k in range(-lay.n_adjacent - 1, lay.n_adjacent + 1):
            x_c = (k + 0.5) * lay.slot_width_cm
            poly = self._to_canvas(
                np.array([x_c - half, x_c + half, x_c + half, x_c - half]),
                np.array([0.0, 0.0, top, top]),
            )
            cv2.fillPoly(lines, [np.round(poly).astype(np.int32)], 1.0, lineType=cv2.LINE_AA)
        if lay.end_line:
            x0 = -(lay.n_adjacent + 0.5) * lay.slot_width_cm - half
            poly = self._to_canvas(
                np.array([x0, -x0, -x0, x0]), np.array([top - lay.line_width_cm, top - lay.line_width_cm, top, top])
            )
            cv2.fillPoly(lines, [np.round(poly).astype(np.int32)], 1.0, lineType=cv2.LINE_AA)

        if lay.line_wear > 0:
            wear = np.ones((h, w), np.float32)
            n = int(lay.line_wear * 400)
            for _ in range(n):
                cv2.circle(wear, (int(rng.uniform(0, w)), int(rng.uniform(0, h))),
                           int(rng.uniform(4, 22) * s), 0.0, -1)
            wear = cv2.GaussianBlur(wear, (0, 0), 2 * s)
            lines *= np.clip(wear * 1.2, 0, 1)

        paint = 205.0 + rng.normal(0, 6, (h, w)).astype(np.float32)
        gray = gray * (1 - lines) + paint * lines

        if lay.shadow:
            shadow = np.zeros((h, w), np.float32)
            poly = self._to_canvas(np.array([-420.0, -60.0, 40.0, -320.0]), np.array([-200.0, -200.0, 600.0, 600.0]))
            cv2.fillPoly(shadow, [np.round(poly).astype(np.int32)], 1.0)
            gray *= 1 - 0.45 * cv2.GaussianBlur(shadow, (0, 0), 10 * s)

        gray = np.clip(gray, 0, 255)
        tint = np.array([1.0, 1.0, 0.97], np.float32)  # BGR, 약간 푸르스름
        return np.clip(gray[..., None] * tint, 0, 255).astype(np.uint8)

    # ------------------------------------------------------------------ 좌표 변환
    @staticmethod
    def slot_to_car(sx: np.ndarray, sy: np.ndarray, pose: CarPose) -> tuple[np.ndarray, np.ndarray]:
        psi = np.radians(pose.yaw_deg)
        c, s = np.cos(psi), np.sin(psi)
        along = np.asarray(sy) + pose.entry_dist_cm
        x = -pose.lateral_offset_cm + np.asarray(sx) * c + along * s
        y = -np.asarray(sx) * s + along * c
        return x, y

    def _canvas_to_car_h(self, pose: CarPose) -> np.ndarray:
        s = self.PX_PER_CM
        canvas_to_slot = np.array([[1 / s, 0, self.sx_min], [0, 1 / s, self.sy_min], [0, 0, 1]])
        psi = np.radians(pose.yaw_deg)
        c, sn = np.cos(psi), np.sin(psi)
        slot_to_car = np.array(
            [
                [c, sn, -pose.lateral_offset_cm + pose.entry_dist_cm * sn],
                [-sn, c, pose.entry_dist_cm * c],
                [0, 0, 1],
            ]
        )
        return slot_to_car @ canvas_to_slot

    # ------------------------------------------------------------------ 렌더링
    def render(
        self,
        pose: CarPose,
        camera: CameraModel | None = None,
        photo: Photometric | None = None,
        rng: np.random.Generator | None = None,
    ) -> tuple[np.ndarray, GroundTruth]:
        camera = camera or CameraModel()
        photo = photo or Photometric()
        rng = rng or self.rng
        lay = self.layout
        W, H = camera.width, camera.height

        h_canvas_to_img = camera.ground_to_image_h() @ self._canvas_to_car_h(pose)
        img = cv2.warpPerspective(
            self.canvas, h_canvas_to_img, (W, H), flags=cv2.INTER_LINEAR,
            borderMode=cv2.BORDER_CONSTANT, borderValue=(74, 76, 78),
        ).astype(np.float32)

        # 지평선 위쪽은 어두운 천장/배경
        v_h = int(np.ceil(camera.horizon_v)) + 2
        if v_h > 0:
            img[: min(v_h, H)] = (55, 58, 60)

        wall_sy = lay.slot_length_cm + lay.wall_gap_cm
        if lay.wall:
            self._draw_wall(img, pose, camera, wall_sy)

        self._draw_bumper(img, camera)

        # 조명/센서 효과
        img *= photo.brightness
        if photo.vignette > 0:
            yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
            r = np.sqrt(((xx - W / 2) / (W / 2)) ** 2 + ((yy - H / 2) / (H / 2)) ** 2) / np.sqrt(2)
            img *= (1 - photo.vignette * r ** 2)[..., None]
        if photo.blur > 0:
            img = cv2.GaussianBlur(img, (0, 0), photo.blur)
        if photo.noise_sigma > 0:
            img += rng.normal(0, photo.noise_sigma, img.shape).astype(np.float32)
        img = np.clip(img, 0, 255).astype(np.uint8)

        gt = GroundTruth(
            lateral_offset_cm=pose.lateral_offset_cm,
            yaw_deg=pose.yaw_deg,
            slot_width_cm=lay.slot_width_cm,
            line_width_cm=lay.line_width_cm,
            entry_dist_cm=pose.entry_dist_cm,
            wall_dist_cm=pose.entry_dist_cm + wall_sy,
        )
        return img, gt

    def _draw_wall(self, img: np.ndarray, pose: CarPose, camera: CameraModel, wall_sy: float) -> None:
        sx = np.array([self.sx_min * 2, self.sx_max * 2, self.sx_max * 2, self.sx_min * 2])
        sy = np.full(4, wall_sy)
        x, y = self.slot_to_car(sx, sy, pose)
        z = np.array([0.0, 0.0, 400.0, 400.0])
        uv, valid = camera.project(np.column_stack([x, y, z]))
        if not valid.all():
            return
        H, W = img.shape[:2]
        mask = np.zeros((H, W), np.uint8)
        cv2.fillPoly(mask, [np.round(uv).astype(np.int32)], 255)
        # 콘크리트 벽: 아래로 갈수록 살짝 어두워지고 바닥 쪽에 띠(걸레받이)
        yy = np.arange(H, dtype=np.float32)[:, None]
        wall = np.zeros_like(img)
        wall[:] = (150, 152, 150)
        wall *= (1.0 - 0.15 * (yy / H))[..., None]
        base_uv, _ = camera.project(np.column_stack([x[:2], y[:2], np.array([25.0, 25.0])]))
        band = np.zeros((H, W), np.uint8)
        cv2.fillPoly(band, [np.round(np.vstack([uv[:2], base_uv[::-1]])).astype(np.int32)], 255)
        wall[band > 0] = (92, 96, 104)
        sel = mask > 0
        img[sel] = wall[sel]

    @staticmethod
    def _draw_bumper(img: np.ndarray, camera: CameraModel) -> None:
        H, W = img.shape[:2]
        mask = np.zeros((H, W), np.uint8)
        cv2.ellipse(mask, (W // 2, H + int(H * 0.9)), (int(W * 0.75), int(H * 0.97)), 0, 180, 360, 255, -1)
        img[mask > 0] = (30, 30, 34)


def render_scene(
    pose: CarPose | None = None,
    layout: LotLayout | None = None,
    photo: Photometric | None = None,
    camera: CameraModel | None = None,
    seed: int = 0,
) -> tuple[np.ndarray, GroundTruth]:
    world = ParkingLotWorld(layout, seed=seed)
    return world.render(pose or CarPose(), camera, photo)
