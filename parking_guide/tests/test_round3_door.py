import numpy as np
import pytest

from parking_guide.camera import CameraModel
from parking_guide.detector import SlotDetector, compute_alignment, door_bias_cm
from parking_guide.guidance import build_guidance, wheel_to_curvature
from parking_guide.synth import CarPose, LotLayout, ParkingLotWorld, ground_truth_slot, pose_from_bumper
from parking_guide.vehicles import PRESETS

V = PRESETS["sonata"]
LAY = LotLayout()


def test_door_bias_direction_and_limits():
    slot = ground_truth_slot(CarPose(0, 0, 100), LAY)
    assert door_bias_cm("center", slot, V) == 0
    b = door_bias_cm("driver", slot, V)
    assert 0 < b <= 15
    assert door_bias_cm("passenger", slot, V) == -b
    # 큰 차는 여유가 적어서 덜 옮긴다 (반대쪽 8cm는 남김)
    big = PRESETS["carnival"]
    margin = slot.half_inner_width_cm - big.width_cm / 2
    assert door_bias_cm("driver", slot, big) == pytest.approx(max(0.0, min(15.0, margin - 8)))
    with pytest.raises(ValueError):
        door_bias_cm("roof", slot, V)


def test_offset_is_relative_to_target():
    slot = ground_truth_slot(CarPose(0, 0, 100), LAY).with_target_bias(15)
    a = compute_alignment(slot, V)
    assert a.offset_cm == pytest.approx(-15, abs=0.1)  # 가운데 있으면 목표보다 15cm 왼쪽
    assert a.clear_left_rear_cm == pytest.approx(a.clear_right_rear_cm)  # 여유는 실제 선 기준
    assert build_guidance(a, slot, V, 0.0).arrow == 1  # 오른쪽으로


def test_detector_applies_door_mode():
    cam = CameraModel.wide_angle()
    img, _ = ParkingLotWorld(LAY, seed=4).render(CarPose(0, 0, 120), cam, rng=np.random.default_rng(0))
    det = SlotDetector(cam, vehicle=V)
    det.door_mode = "passenger"
    a = det.analyze(img).alignment
    assert a.target_bias_cm < 0 and a.offset_cm > 8


@pytest.mark.parametrize("mode", ["driver", "passenger"])
def test_following_guidance_reaches_door_target(mode):
    bx, by, heading, steer = 40.0, -300.0, -8.0, 0.0
    stop_by = LAY.slot_length_cm - LAY.line_width_cm - 25.0
    ov = V.rear_overhang_cm
    for _ in range(1000):
        if by >= stop_by:
            break
        pose = pose_from_bumper(bx, by, heading)
        slot = ground_truth_slot(pose, LAY)
        slot = slot.with_target_bias(door_bias_cm(mode, slot, V))
        g = build_guidance(compute_alignment(slot, V), slot, V, steer)
        if g.level == "warn":
            steer += (g.desired_steer_deg - steer) * 0.25
        h = np.radians(heading)
        ax, ay = bx - ov * np.sin(h) + 4 * np.sin(h), by - ov * np.cos(h) + 4 * np.cos(h)
        h += 4 * wheel_to_curvature(steer, V)
        heading = float(np.degrees(h))
        bx, by = ax + ov * np.sin(h), ay + ov * np.cos(h)
    target = door_bias_cm(mode, ground_truth_slot(CarPose(0, 0, 100), LAY), V)
    assert bx == pytest.approx(target, abs=8) and abs(heading) < 3
