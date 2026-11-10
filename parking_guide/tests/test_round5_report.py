import json

import numpy as np
import pytest

from parking_guide.camera import CameraModel
from parking_guide.detector import Alignment
from parking_guide.guidance import Guidance
from parking_guide.overlay import render_report
from parking_guide.pipeline import ParkingAssistant
from parking_guide.report import (History, LineCondition, ParkingSession, grade_ko, make_tips,
                                  municipal_payload, score_parking, trend_text)
from parking_guide.synth import CarPose, LotLayout, ParkingLotWorld, ground_truth_slot
from parking_guide.vehicles import PRESETS

V = PRESETS["sonata"]


def test_score_is_monotonic_and_bounded():
    perfect = score_parking(0, 0, 20, 2, 0)
    assert perfect == 100
    assert score_parking(10, 0, 20, 2, 0) < perfect
    assert score_parking(10, 2, 20, 2, 0) < score_parking(10, 0, 20, 2, 0)
    assert score_parking(0, 0, -3, 2, 0) < score_parking(0, 0, 3, 2, 0) < perfect
    assert score_parking(80, 10, -30, 30, 5) == 0
    assert grade_ko(95) == "완벽해요" and grade_ko(40) == "연습해 봐요"


def test_tips_target_the_main_problem():
    assert "반듯" in make_tips(0, 3, 2, 0, 0, 0)[0]
    assert "왼쪽" in make_tips(-12, 0, 2, 0, 0, 0)[0]
    assert any("좋은 판단" in t for t in make_tips(0, 0, 2, 1, 0, 0))
    assert make_tips(0, 0, 2, 0, 0, 0) == ["반듯하고 가운데예요. 이 감각을 기억해 두세요"]


def _align(off=0.0, yaw=0.0):
    return Alignment(off, yaw, 20, 20, 20, 20, "good", 30.0)


def test_session_counts_corrections_retries_and_time():
    s = ParkingSession()
    slot = ground_truth_slot(CarPose(0, 0, 100), LotLayout())
    steer = [0, 100, 200, 150, 50, -60, -40, 80]  # 오른쪽 → 왼쪽 → 오른쪽: 방향 전환 2번
    keys = ["turn1", "retry", "retry", "keep", "retry", "keep", "keep", "keep"]
    for i, (w, k) in enumerate(zip(steer, keys)):
        s.add(_align(), slot, Guidance("ok", k, "", ""), w, t=i * 0.5, measured=slot)
    rep = s.finish()
    assert rep.corrections == 2
    assert rep.retries == 2
    assert rep.duration_s == pytest.approx(3.5)
    assert rep.line is not None and rep.line.samples == 8


def test_history_trend(tmp_path):
    assert trend_text([70]) == "첫 기록이에요"
    assert "최고 기록" in trend_text([60, 70, 80])
    assert "올랐어요" in trend_text([50] * 5 + [70] * 5)
    h = History(tmp_path / "h.json")
    s = ParkingSession()
    slot = ground_truth_slot(CarPose(0, 0, 100), LotLayout())
    s.add(_align(), slot, None, 0.0, 0.0)
    r1 = h.add(s.finish())
    assert r1.trend_ko == "첫 기록이에요"
    assert len(json.loads((tmp_path / "h.json").read_text(encoding="utf-8"))) == 1


def test_municipal_payload_is_anonymous():
    s = ParkingSession()
    slot = ground_truth_slot(CarPose(0, 0, 100), LotLayout())
    s.add(_align(), slot, None, 0.0, 0.0, measured=slot)
    rep = s.finish()
    p = municipal_payload(rep, 36.446612, 127.119034, slot_heading_deg=93.0)
    assert p["location"] == {"lat": 36.447, "lon": 127.119}
    assert p["slot_heading_deg"] == 90
    text = json.dumps(p)
    for forbidden in ("vin", "image", "VIN", "user"):
        assert forbidden not in text
    assert set(p) == {"schema", "location", "slot_heading_deg", "line", "difficulty"}


def test_line_condition_labels():
    assert LineCondition(0.9, 0.85, 0.9, 10).label_ko == "양호"
    assert LineCondition(0.7, 0.9, 0.9, 10).label_ko == "약간 닳음"
    worn = LineCondition(0.5, 0.9, 0.6, 10)
    assert worn.label_ko == "재도색 권장" and worn.needs_repaint


def test_report_card_renders():
    s = ParkingSession()
    slot = ground_truth_slot(CarPose(0, 0, 100), LotLayout())
    s.add(_align(-12, 2.0), slot, None, 0.0, 0.0, measured=slot)
    rep = s.finish()
    img = render_report(rep, 1280, 720)
    assert img.shape == (720, 1280, 3) and img.std() > 5


def test_pipeline_produces_report():
    cam = CameraModel.wide_angle()
    pa = ParkingAssistant(cam, V)
    world = ParkingLotWorld(LotLayout(), seed=1)
    for i, entry in enumerate([200.0, 190.0, 180.0]):
        img, _ = world.render(CarPose(5, 1, entry), cam, rng=np.random.default_rng(i))
        pa.step(img, 0.0, 10.0 if i else 0.0, t=i * 0.1)
    rep = pa.finish()
    assert rep is not None and 0 <= rep.score <= 100
    assert rep.offset_cm == pytest.approx(5, abs=3)
