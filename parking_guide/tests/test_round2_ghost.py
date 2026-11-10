import numpy as np
import pytest

from parking_guide.camera import CameraModel
from parking_guide.detector import SlotDetector, compute_alignment
from parking_guide.guidance import build_guidance, predict_final, wheel_to_curvature
from parking_guide.synth import CarPose, LotLayout, ParkingLotWorld, ground_truth_slot, pose_from_bumper
from parking_guide.vehicles import PRESETS

V = PRESETS["sonata"]
LAY = LotLayout()


def test_ground_truth_slot_matches_detector():
    cam = CameraModel.wide_angle()
    pose = CarPose(-20, 4, 60)
    img, _ = ParkingLotWorld(LAY, seed=2).render(pose, cam, rng=np.random.default_rng(0))
    est = SlotDetector(cam).analyze(img).slot
    gt = ground_truth_slot(pose, LAY)
    for y in (100.0, 300.0):
        assert est.center_x_at(y) == pytest.approx(gt.center_x_at(y), abs=3)
    assert est.end_dist_cm == pytest.approx(gt.end_dist_cm, abs=8)


def test_final_prediction_straight_centered():
    pose = CarPose(0, 0, 100)
    slot = ground_truth_slot(pose, LAY)
    f = predict_final(slot, V, 0.0, slot.end_dist_cm)
    assert f.offset_cm == pytest.approx(0, abs=0.5) and f.yaw_deg == pytest.approx(0, abs=0.1)
    expected = (LAY.slot_width_cm - LAY.line_width_cm - V.width_cm) / 2
    assert f.clear_left_cm == pytest.approx(expected, abs=0.5)
    assert f.status == "good"


def test_final_prediction_follows_steering():
    slot = ground_truth_slot(CarPose(0, 0, 100), LAY)
    right = predict_final(slot, V, 200.0, slot.end_dist_cm)
    assert right.offset_cm > 20 and right.clear_right_cm < right.clear_left_cm


def _simulate(bx, by, heading, steer=0.0, ds=4.0, lag=0.25):
    """운전자가 화면 안내(목표 핸들 각도)를 늦게 따라가며 후진한다. 최종 (치우침, 각도) 반환."""
    stop_by = LAY.slot_length_cm - LAY.line_width_cm - 25.0
    ov = V.rear_overhang_cm
    for _ in range(1000):
        if by >= stop_by:
            break
        pose = pose_from_bumper(bx, by, heading)
        slot = ground_truth_slot(pose, LAY)
        a = compute_alignment(slot, V)
        g = build_guidance(a, slot, V, steer)
        if g.level == "warn":
            steer += (g.desired_steer_deg - steer) * lag
        h = np.radians(heading)
        ax, ay = bx - ov * np.sin(h), by - ov * np.cos(h)
        ax, ay = ax + ds * np.sin(h), ay + ds * np.cos(h)
        h += ds * wheel_to_curvature(steer, V)
        heading = float(np.degrees(h))
        bx, by = ax + ov * np.sin(h), ay + ov * np.cos(h)
    return bx, heading


@pytest.mark.parametrize("bx, by, heading", [(60, -300, -12), (-50, -250, 10), (30, -150, 0), (-25, -350, -5)])
def test_following_guidance_parks_centered(bx, by, heading):
    off, yaw = _simulate(bx, by, heading)
    assert abs(off) < 10 and abs(yaw) < 3


def test_retry_when_cannot_fit():
    # 끝선 1.5m 앞인데 60cm나 치우쳐 있으면 어떤 핸들로도 선 안에 못 들어간다
    pose = CarPose(60, 0, -300)
    slot = ground_truth_slot(pose, LAY)
    g = build_guidance(compute_alignment(slot, V), slot, V, 0.0)
    assert g.key == "retry" and "다시" in g.text_ko


def test_far_away_uses_s_curve_guidance():
    # 멀리서 30cm 치우침: 고정 핸들로는 못 고치지만 pure pursuit는 방향을 알려 준다
    pose = CarPose(30, 0, 200)
    slot = ground_truth_slot(pose, LAY)
    g = build_guidance(compute_alignment(slot, V), slot, V, 0.0)
    assert g.level == "warn" and g.arrow == -1
