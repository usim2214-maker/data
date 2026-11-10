import numpy as np
import pytest

from parking_guide.camera import CameraModel
from parking_guide.detector import (Alignment, AlignmentSmoother, LineEstimate, SlotDetector,
                                    SlotEstimate, compute_alignment)
from parking_guide.overlay import GuideRenderer
from parking_guide.synth import CarPose, LotLayout, ParkingLotWorld, Photometric, pose_from_bumper
from parking_guide.vehicles import PRESETS, get_vehicle

CAMERAS = {"pinhole": CameraModel(), "wide": CameraModel.wide_angle()}


@pytest.fixture(scope="module")
def world():
    return ParkingLotWorld(LotLayout(line_wear=0.3), seed=11)


@pytest.fixture(scope="module", params=list(CAMERAS))
def cam_det(request):
    cam = CAMERAS[request.param]
    return cam, SlotDetector(cam, vehicle=PRESETS["sonata"])


# 이 테스트의 바닥은 주차선 길이의 30%가 지워진 '닳은 주차장'이다. 닳은 선에서는 2~3cm 오차를 허용한다.
@pytest.mark.parametrize(
    "pose, tol_cm, tol_deg",
    [
        (CarPose(0, 0, 150), 2.0, 0.5),
        (CarPose(25, 0, 100), 3.0, 0.5),
        (CarPose(-30, -6, 200), 4.0, 0.5),
        (CarPose(15, 8, 50), 3.0, 0.5),
        (CarPose(-60, 12, 300), 4.0, 0.7),
        (CarPose(5, -2, -150), 3.0, 0.5),
        # 주차칸 깊숙이 들어가 좌우 선이 짧게 보여도 끝선으로 각도를 보정한다
        (CarPose(8, 2, -330), 3.0, 0.6),
    ],
)
def test_offset_and_yaw(world, cam_det, pose, tol_cm, tol_deg):
    cam, det = cam_det
    img, gt = world.render(pose, cam, rng=np.random.default_rng(0))
    res = det.analyze(img)
    assert res.alignment is not None
    assert res.alignment.offset_cm == pytest.approx(gt.perp_offset_cm, abs=tol_cm)
    assert res.alignment.yaw_deg == pytest.approx(gt.yaw_deg, abs=tol_deg)
    assert res.slot.width_cm == pytest.approx(gt.slot_width_cm, abs=6.0)


@pytest.mark.parametrize("entry", [150.0, -100.0, -300.0])
def test_end_line_distance(world, cam_det, entry):
    cam, det = cam_det
    img, gt = world.render(CarPose(5, 2, entry), cam, rng=np.random.default_rng(0))
    a = det.analyze(img).alignment
    assert a is not None and a.end_dist_cm is not None
    assert a.end_dist_cm == pytest.approx(gt.end_line_dist_cm, abs=8.0)


def test_no_end_line_when_out_of_range(world):
    det = SlotDetector(CAMERAS["wide"])
    img, _ = world.render(CarPose(0, 0, 400), CAMERAS["wide"], rng=np.random.default_rng(0))
    a = det.analyze(img).alignment
    assert a is not None and a.end_dist_cm is None


def test_dark_noisy_worn():
    world = ParkingLotWorld(LotLayout(line_wear=0.6, shadow=True), seed=5)
    det = SlotDetector(CAMERAS["wide"])
    img, gt = world.render(CarPose(-10, 3, 120), CAMERAS["wide"],
                           Photometric(brightness=0.6, noise_sigma=8), np.random.default_rng(1))
    a = det.analyze(img).alignment
    assert a is not None
    assert a.offset_cm == pytest.approx(gt.perp_offset_cm, abs=4)
    assert a.yaw_deg == pytest.approx(3, abs=0.8)


def test_picks_slot_nearest_car_center(world):
    det = SlotDetector(CAMERAS["pinhole"])
    img, gt = world.render(CarPose(100, 0, 150), CAMERAS["pinhole"], rng=np.random.default_rng(0))
    a = det.analyze(img).alignment
    assert a is not None and a.offset_cm == pytest.approx(100, abs=3)


def test_no_lines_returns_none():
    det = SlotDetector(CAMERAS["pinhole"])
    img = np.clip(np.random.default_rng(0).normal(80, 8, (720, 1280, 3)), 0, 255).astype(np.uint8)
    res = det.analyze(img)
    assert res.slot is None and res.alignment is None


# ---------------------------------------------------------------------- 카메라/좌표
@pytest.mark.parametrize("cam", list(CAMERAS.values()), ids=list(CAMERAS))
def test_pixel_ray_roundtrip(cam):
    pts = np.array([[-150.0, 120.0, 0.0], [80.0, 300.0, 0.0], [200.0, 600.0, 50.0]])
    uv, ok = cam.project(pts)
    assert ok.all()
    xn, yn, valid = cam.pixel_to_ray(uv[:, 0], uv[:, 1])
    assert valid.all()
    # 광선을 지면과 다시 교차시켜 원래 점이 나오는지 확인
    right, down, fwd = cam._axes()
    dirs = xn[:, None] * right + yn[:, None] * down + fwd
    t = (pts[:, 2] - cam.mount_height_cm) / dirs[:, 2]
    back = np.array([0, 0, cam.mount_height_cm]) + dirs * t[:, None]
    np.testing.assert_allclose(back, pts, atol=0.5)


def test_pose_from_bumper_roundtrip():
    pose = pose_from_bumper(40.0, -200.0, -10.0)
    # 뒷범퍼 원점이 주차칸 좌표 (40, -200)에 있어야 한다
    x, y = ParkingLotWorld.slot_to_car(np.array([40.0]), np.array([-200.0]), pose)
    assert x[0] == pytest.approx(0, abs=1e-6) and y[0] == pytest.approx(0, abs=1e-6)
    assert pose.yaw_deg == pytest.approx(10.0)


# ---------------------------------------------------------------------- 균형 계산
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
    a = compute_alignment(_slot(center_x=-30), get_vehicle("186x490"))
    assert a.offset_cm == pytest.approx(30)
    assert a.clear_right_rear_cm == pytest.approx(24.5 - 30)
    assert a.status == "danger"
    assert "오른쪽" in a.messages_ko()[0]


def test_slot_shifted_moves_toward_car():
    s = _slot(center_x=-10, slope=0.05)
    s2 = s.shifted(50)
    assert s2.center_x_at(0) == pytest.approx(s.center_x_at(50))


def test_smoother_holds_then_drops_and_advances():
    s = AlignmentSmoother(alpha=0.5, max_missed=2)
    a = Alignment(10, 1, 20, 20, 20, 20, "adjust", end_dist_cm=200)
    assert s.update(a).offset_cm == 10
    assert s.update(Alignment(0, 0, 20, 20, 20, 20, "good", 180)).offset_cm == pytest.approx(5)
    s.advance(40)
    assert s.update(None).end_dist_cm == pytest.approx(190 - 40)
    assert s.update(None) is not None
    assert s.update(None) is None


# ---------------------------------------------------------------------- 조향 안내
from parking_guide.guidance import (GuidanceStabilizer, build_guidance, desired_steering,  # noqa: E402
                                    predict_path)


def test_predict_path_turns_toward_steering():
    v = PRESETS["sonata"]
    straight = predict_path(v, 0, 300)
    right = predict_path(v, 270, 300)
    left = predict_path(v, -270, 300)
    assert straight["center"][-1, 0] == pytest.approx(0)
    assert right["center"][-1, 0] > 30 and left["center"][-1, 0] < -30
    # 이동 거리만큼 뒤로 간다
    assert np.hypot(*np.diff(right["center"], axis=0).T).sum() == pytest.approx(300, rel=0.02)


def test_desired_steering_sign():
    v = PRESETS["sonata"]
    # 주차칸이 오른쪽에 있으면(차가 왼쪽으로 치우침) 핸들을 오른쪽으로
    assert desired_steering(_slot(center_x=30), v) > 0
    assert desired_steering(_slot(center_x=-30), v) < 0
    assert desired_steering(_slot(), v) == pytest.approx(0)


def test_guidance_messages():
    v = PRESETS["sonata"]
    slot = _slot(center_x=40)
    a = compute_alignment(slot, v)
    assert build_guidance(a, slot, v, steer_deg=0).text_ko.startswith("핸들을 오른쪽")
    want = desired_steering(slot, v)
    assert build_guidance(a, slot, v, steer_deg=want).level == "ok"
    a.end_dist_cm = 20
    assert build_guidance(a, slot, v, steer_deg=0).level == "stop"
    assert build_guidance(None, None, v).level == "info"


def test_stabilizer_needs_repeats():
    v = PRESETS["sonata"]
    st = GuidanceStabilizer(hold_frames=3)
    slot = _slot()
    a = compute_alignment(slot, v)
    ok = build_guidance(a, slot, v, steer_deg=0)
    warn = build_guidance(a, slot, v, steer_deg=300)
    assert st.update(ok).key == ok.key
    assert st.update(warn).key == ok.key
    assert st.update(warn).key == ok.key
    assert st.update(warn).key == warn.key


def test_renderer_runs(world):
    for cam in CAMERAS.values():
        det = SlotDetector(cam)
        img, _ = world.render(CarPose(10, 2, 100), cam, rng=np.random.default_rng(0))
        res = det.analyze(img)
        ren = GuideRenderer(cam, det.vehicle)
        g = build_guidance(res.alignment, res.slot, det.vehicle, 50.0)
        out = ren.render(img, res.slot, res.alignment, g, 50.0)
        assert out.shape == img.shape and not np.array_equal(out, img)
        assert ren.render(img, None, None, build_guidance(None, None, det.vehicle)).shape == img.shape
