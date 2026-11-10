import numpy as np
import pytest

from parking_guide.camera import CameraModel
from parking_guide.detector import (Alignment, AlignmentSmoother, LineEstimate, SlotDetector,
                                    SlotEstimate, compute_alignment)
from parking_guide.overlay import GuideRenderer
from parking_guide.synth import CarPose, LotLayout, ParkingLotWorld, Photometric
from parking_guide.vehicles import PRESETS, get_vehicle


@pytest.fixture(scope="module")
def world():
    return ParkingLotWorld(LotLayout(line_wear=0.3), seed=11)


@pytest.fixture(scope="module")
def detector():
    return SlotDetector(CameraModel(), vehicle=PRESETS["sonata"])


@pytest.mark.parametrize(
    "pose, tol_cm, tol_deg",
    [
        (CarPose(0, 0, 150), 2.0, 0.5),
        (CarPose(25, 0, 100), 2.0, 0.5),
        (CarPose(-30, -6, 200), 3.0, 0.5),
        (CarPose(15, 8, 50), 3.0, 0.5),
        (CarPose(-60, 12, 300), 4.0, 0.7),
        (CarPose(5, -2, -150), 3.0, 0.7),
        # 주차칸 깊숙이 들어가 선이 짧게 보이는 경우는 오차가 커진다
        (CarPose(8, 2, -330), 5.0, 1.5),
    ],
)
def test_offset_and_yaw(world, detector, pose, tol_cm, tol_deg):
    img, gt = world.render(pose, rng=np.random.default_rng(0))
    res = detector.analyze(img)
    assert res.alignment is not None
    assert res.alignment.offset_cm == pytest.approx(gt.lateral_offset_cm, abs=tol_cm)
    assert res.alignment.yaw_deg == pytest.approx(gt.yaw_deg, abs=tol_deg)
    assert res.slot.width_cm == pytest.approx(gt.slot_width_cm, abs=6.0)


def test_dark_noisy_worn(detector):
    world = ParkingLotWorld(LotLayout(line_wear=0.6, shadow=True), seed=5)
    img, gt = world.render(CarPose(-10, 3, 120), photo=Photometric(brightness=0.6, noise_sigma=8),
                           rng=np.random.default_rng(1))
    a = detector.analyze(img).alignment
    assert a is not None
    assert a.offset_cm == pytest.approx(-10, abs=4)
    assert a.yaw_deg == pytest.approx(3, abs=0.8)


def test_picks_slot_nearest_car_center(world, detector):
    # 차가 오른쪽 이웃 칸과 반쯤 걸친 상태라도 차 중심에 가장 가까운 칸을 고른다
    img, gt = world.render(CarPose(100, 0, 150), rng=np.random.default_rng(0))
    a = detector.analyze(img).alignment
    assert a is not None and a.offset_cm == pytest.approx(100, abs=3)


def test_no_lines_returns_none(detector):
    rng = np.random.default_rng(0)
    img = np.clip(rng.normal(80, 8, (720, 1280, 3)), 0, 255).astype(np.uint8)
    res = detector.analyze(img)
    assert res.slot is None and res.alignment is None


def _slot(center_x=0.0, slope=0.0, width=250.0, lw=15.0):
    mk = lambda x: LineEstimate(x, slope, lw, 60, 500, 100)
    return SlotEstimate(mk(center_x - width / 2), mk(center_x + width / 2))


def test_clearance_math_centered():
    a = compute_alignment(_slot(), get_vehicle("186x490"))
    expected = (250 - 15 - 186) / 2
    for c in (a.clear_left_rear_cm, a.clear_right_rear_cm, a.clear_left_front_cm, a.clear_right_front_cm):
        assert c == pytest.approx(expected)
    assert a.status == "good" and a.offset_cm == pytest.approx(0)


def test_clearance_math_shifted_and_danger():
    # 주차칸 중심이 차 기준 왼쪽 30cm → 차가 오른쪽으로 30cm 치우침
    a = compute_alignment(_slot(center_x=-30), get_vehicle("186x490"))
    assert a.offset_cm == pytest.approx(30)
    assert a.clear_right_rear_cm == pytest.approx(24.5 - 30)
    assert a.status == "danger"
    assert "오른쪽" in a.messages_ko()[0]


def test_smoother_holds_then_drops():
    s = AlignmentSmoother(alpha=0.5, max_missed=2)
    a = Alignment(10, 1, 20, 20, 20, 20, "adjust")
    assert s.update(a).offset_cm == 10
    assert s.update(Alignment(0, 0, 20, 20, 20, 20, "good")).offset_cm == pytest.approx(5)
    assert s.update(None) is not None
    assert s.update(None) is not None
    assert s.update(None) is None


def test_renderer_runs(world, detector):
    img, _ = world.render(CarPose(10, 2, 100), rng=np.random.default_rng(0))
    res = detector.analyze(img)
    ren = GuideRenderer(detector.proj.camera, detector.vehicle)
    out = ren.render(img, res.slot, res.alignment)
    assert out.shape == img.shape and not np.array_equal(out, img)
    out2 = ren.render(img, None, None)
    assert out2.shape == img.shape
