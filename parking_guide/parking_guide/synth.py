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
    end_line_dist_cm: float | None = None  # 뒷범퍼에서 끝선 안쪽 가장자리까지

    @property
    def perp_offset_cm(self) -> float:
        """뒷범퍼 중앙에서 주차칸 중심선까지의 수직 거리 (검출기 offset과 같은 정의)."""
        return self.lateral_offset_cm * float(np.cos(np.radians(self.yaw_deg)))


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

    # ------------------------------------------------------------------ 렌더링
    def _rays(self, camera: CameraModel):
        key = (camera.width, camera.height, camera.focal_px, camera.mount_height_cm,
               camera.pitch_deg, camera.fisheye, tuple(camera.dist))
        if getattr(self, "_ray_key", None) != key:
            dirs, valid = camera.pixel_rays_world()
            h = camera.mount_height_cm
            dz = dirs[..., 2]
            hit = valid & (dz < -1e-6)
            t = np.where(hit, h / np.where(hit, -dz, 1.0), np.inf)
            self._ray_key = key
            self._ray_cache = (dirs, valid, hit, t, dirs[..., 0] * np.where(hit, t, 0), dirs[..., 1] * np.where(hit, t, 0))
        return self._ray_cache

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
        dirs, valid, hit, t_g, gx, gy = self._rays(camera)

        # 지면: 차량 좌표 → 주차칸 좌표 → 캔버스 픽셀
        psi = np.radians(pose.yaw_deg)
        c, sn = np.cos(psi), np.sin(psi)
        x = gx + pose.lateral_offset_cm
        y = gy
        sx = c * x - sn * y
        sy = sn * x + c * y - pose.entry_dist_cm
        s = self.PX_PER_CM
        map_x = ((sx - self.sx_min) * s).astype(np.float32)
        map_y = ((sy - self.sy_min) * s).astype(np.float32)
        img = cv2.remap(self.canvas, map_x, map_y, cv2.INTER_LINEAR,
                        borderMode=cv2.BORDER_CONSTANT, borderValue=(74, 76, 78)).astype(np.float32)
        img[~hit] = (55, 58, 60)  # 지평선 위: 어두운 배경

        wall_sy = lay.slot_length_cm + lay.wall_gap_cm
        if lay.wall:
            # 벽 평면: 주차칸 축 방향 n = (sinψ, cosψ, 0), n·P = (entry + wall_sy) - offset·sinψ
            n = np.array([sn, c, 0.0])
            d = (pose.entry_dist_cm + wall_sy) - pose.lateral_offset_cm * sn
            nd = dirs @ n
            t_w = np.where(np.abs(nd) > 1e-9, d / np.where(np.abs(nd) > 1e-9, nd, 1.0), np.inf)
            z = camera.mount_height_cm + t_w * dirs[..., 2]
            on_wall = valid & (t_w > 0) & (z >= 0) & (z <= 400) & (t_w < t_g)
            vv = np.arange(H, dtype=np.float32)[:, None] * np.ones((1, W), np.float32)
            shade = (1.0 - 0.15 * vv / H)[..., None]
            wall = np.array([150, 152, 150], np.float32) * shade
            band = z < 25
            wall = np.where(band[..., None], np.array([92, 96, 104], np.float32), wall)
            img = np.where(on_wall[..., None], wall, img)

        img[~valid] = 0
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
            end_line_dist_cm=pose.entry_dist_cm + lay.slot_length_cm - lay.line_width_cm if lay.end_line else None,
        )
        return img, gt

    @staticmethod
    def _draw_bumper(img: np.ndarray, camera: CameraModel) -> None:
        H, W = img.shape[:2]
        mask = np.zeros((H, W), np.uint8)
        cv2.ellipse(mask, (W // 2, H + int(H * 0.9)), (int(W * 0.75), int(H * 0.97)), 0, 180, 360, 255, -1)
        img[mask > 0] = (30, 30, 34)


def pose_from_bumper(bx: float, by: float, heading_deg: float) -> CarPose:
    """주차칸 좌표계에서 뒷범퍼 중앙 위치 (bx, by)와 차의 후진 방향 각도로 CarPose를 만든다.

    heading_deg: 후진 방향이 주차칸 축(+s_y)에서 +s_x 쪽으로 돌아간 각도.
    """
    psi = -np.radians(heading_deg)
    return CarPose(lateral_offset_cm=float(bx / np.cos(psi)), yaw_deg=float(np.degrees(psi)),
                   entry_dist_cm=float(bx * np.tan(psi) - by))


def render_scene(
    pose: CarPose | None = None,
    layout: LotLayout | None = None,
    photo: Photometric | None = None,
    camera: CameraModel | None = None,
    seed: int = 0,
) -> tuple[np.ndarray, GroundTruth]:
    world = ParkingLotWorld(layout, seed=seed)
    return world.render(pose or CarPose(), camera, photo)
