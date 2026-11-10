import pytest

from parking_guide.detector import LineEstimate, SlotEstimate, compute_alignment
from parking_guide.guidance import build_guidance, desired_steering, turns_phrase_ko
from parking_guide.vehicles import PRESETS
from parking_guide.voice import VoicePrompter, beep_interval_s

V = PRESETS["sonata"]


def _slot(cx=0.0):
    mk = lambda x: LineEstimate(x, 0.0, 15, 60, 500, 100)
    return SlotEstimate(mk(cx - 125), mk(cx + 125))


@pytest.mark.parametrize("deg, phrase", [(30, "살짝"), (100, "1/4바퀴"), (-180, "반 바퀴"),
                                         (300, "3/4바퀴"), (400, "한 바퀴"), (600, "한 바퀴 반")])
def test_turns_phrase(deg, phrase):
    assert turns_phrase_ko(deg) == phrase


def test_guidance_says_how_much():
    slot = _slot(60)
    g = build_guidance(compute_alignment(slot, V), slot, V, steer_deg=0)
    assert "바퀴" in g.text_ko or "살짝" in g.text_ko
    assert g.text_ko.startswith("핸들을 오른쪽으로")


def test_beep_interval_gets_faster():
    assert beep_interval_s(None) is None and beep_interval_s(200) is None
    assert beep_interval_s(140) > beep_interval_s(80) > beep_interval_s(40)
    assert beep_interval_s(25) == 0.0


def test_voice_rate_limit_and_stop():
    vp = VoicePrompter()
    slot = _slot(60)
    a = compute_alignment(slot, V)
    turn = build_guidance(a, slot, V, steer_deg=0)
    ok = build_guidance(a, slot, V, steer_deg=desired_steering(slot, V, 250))
    assert vp.update(turn, None, 0.0) == turn.text_ko
    assert vp.update(turn, None, 0.5) is None  # 같은 안내는 반복하지 않음
    assert vp.update(ok, None, 1.0) is None  # 너무 빨리 바뀌면 말하지 않음
    assert vp.update(ok, None, 3.0) == ok.text_ko
    assert vp.update(ok, 95, 3.2) == "1미터 남았어요"
    assert vp.update(ok, 90, 3.4) is None
    a.end_dist_cm = 20
    stop = build_guidance(a, slot, V, steer_deg=0)
    assert vp.update(stop, 20, 3.5) == "멈추세요"  # 멈추세요는 즉시
    assert vp.update(stop, 20, 4.0) is None
    assert vp.update(stop, 20, 5.6) == "멈추세요"  # 2초마다 반복
