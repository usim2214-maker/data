from dataclasses import replace

import numpy as np
import pytest

from parking_guide.camera import CameraModel
from parking_guide.detector import SlotDetector, SlotTracker, compute_alignment
from parking_guide.guidance import LOW_CONFIDENCE, build_guidance, wheel_to_curvature
from parking_guide.overlay import GuideRenderer
from parking_guide.pipeline import ParkingAssistant
from parking_guide.synth import CarPose, LotLayout, ParkingLotWorld, ground_truth_slot, pose_from_bumper
from parking_guide.vehicles import PRESETS

V = PRESETS["sonata"]
LAY = LotLayout()
CAM = CameraModel.wide_angle()


def _step(bx, by, h_deg, steer, ds, n=None):
    """자전거 모델 후진. 원호를 정확히 따라가도록 1cm 단위로 적분한다."""
    ov = V.rear_overhang_cm
    k = wheel_to_curvature(steer, V)
    h = np.radians(h_deg)
    ax, ay = bx - ov * np.sin(h), by - ov * np.cos(h)
    n = n or max(1, int(abs(ds)))
    for _ in range(n):
        ax, ay = ax + ds / n * np.sin(h), ay + ds / n * np.cos(h)
        h += ds / n * k
    return ax + ov * np.sin(h), ay + ov * np.cos(h), float(np.degrees(h))


@pytest.mark.parametrize("steer", [0.0, 250.0, -400.0])
def test_moved_matches_geometry(steer):
    bx, by, h = 30.0, -250.0, -5.0
    before = ground_truth_slot(pose_from_bumper(bx, by, h), LAY)
    bx2, by2, h2 = _step(bx, by, h, steer, 60.0)
    after = ground_truth_slot(pose_from_bumper(bx2, by2, h2), LAY)
    pred = before.moved(60.0, wheel_to_curvature(steer, V), -V.rear_overhang_cm)
    for y in (50.0, 300.0):
        assert pred.center_x_at(y) == pytest.approx(after.center_x_at(y), abs=0.5)
    assert pred.yaw_deg == pytest.approx(after.yaw_deg, abs=0.05)
    assert pred.end_dist_cm == pytest.approx(after.end_dist_cm, abs=0.5)


def test_confidence_drops_with_wear():
    det = SlotDetector(CAM)
    pose = CarPose(5, 2, 100)
    clean = det.analyze(ParkingLotWorld(LotLayout(), seed=1).render(pose, CAM, rng=np.random.default_rng(0))[0]).slot
    worn = det.analyze(ParkingLotWorld(LotLayout(line_wear=0.5), seed=3).render(
        pose, CAM, rng=np.random.default_rng(0))[0]).slot
    assert clean.confidence > 0.9
    assert worn is None or worn.confidence < clean.confidence - 0.2


def test_low_confidence_guidance_and_ui():
    slot = replace(ground_truth_slot(CarPose(20, 3, 100), LAY), confidence=LOW_CONFIDENCE - 0.1)
    g = build_guidance(compute_alignment(slot, V), slot, V, 0.0)
    assert g.key == "lowconf" and g.level == "info"
    img = np.full((CAM.height, CAM.width, 3), 80, np.uint8)
    out = GuideRenderer(CAM, V).render(img, slot, compute_alignment(slot, V), g, 0.0)
    assert out.shape == img.shape


def test_tracker_rejects_outlier_then_switches():
    tr = SlotTracker(switch_after=3)
    a = ground_truth_slot(CarPose(0, 0, 100), LAY)
    other = ground_truth_slot(CarPose(250, 0, 100), LAY)  # 옆 칸
    tr.update(a)
    assert tr.update(other).center_x_at(150) == pytest.approx(a.center_x_at(150), abs=1)
    assert tr.last_rejected
    tr.update(other)
    assert tr.update(other).center_x_at(150) == pytest.approx(other.center_x_at(150), abs=1)


def test_tracker_coasts_with_motion_and_decays():
    tr = SlotTracker(max_coast=5)
    a = ground_truth_slot(CarPose(0, 0, 100), LAY)
    tr.update(a)
    s1 = tr.update(None, ds_cm=40)
    assert s1.end_dist_cm == pytest.approx(a.end_dist_cm - 40, abs=0.5)
    assert s1.confidence < a.confidence
    for _ in range(5):
        tr.update(None)
    assert tr.slot is None


def test_pipeline_survives_occlusion():
    world = ParkingLotWorld(LotLayout(line_wear=0.2), seed=5)
    pa = ParkingAssistant(CAM, V)
    bx, by, h, steer = 20.0, -250.0, -4.0, 0.0
    outs = []
    for i in range(12):
        img, _ = world.render(pose_from_bumper(bx, by, h), CAM, rng=np.random.default_rng(i))
        if i in (5, 6, 7):  # 사람이 지나가 화면이 가려진 상황
            img[:] = 40
        outs.append(pa.step(img, steer, ds_cm=5.0 if i else 0.0, t=i * 0.1))
        bx, by, h = _step(bx, by, h, steer, 5.0)
    assert all(o.slot is not None for o in outs)
    assert outs[6].detection.slot is None
    assert outs[6].guidance.key != "search"
