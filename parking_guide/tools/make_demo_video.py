"""공모전 시연 영상 만들기.

Pleos SDK에 후방카메라 영상 API가 없어서, 후방카메라 화면은 합성 영상으로 시뮬레이션한다.
안내 화면은 앱(Android)과 같은 디자인의 Python 렌더러로 그리고, 화면 아래 자막 띠에
기능 설명과 음성 안내 문구를 넣는다. 소리는 거리 비프음과 음성 안내 알림음만 넣는다
(내레이션은 따로 녹음해 얹을 수 있게 비워 둠).

  cd parking_guide
  python tools/make_demo_video.py                    # → output/pleos_parking_guide_demo.mp4
  python tools/make_demo_video.py --fps 10 --quick   # 빠른 미리보기
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import wave
from dataclasses import dataclass, field
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from demo import DriverSim  # noqa: E402
from parking_guide.camera import CameraModel  # noqa: E402
from parking_guide.overlay import GuideRenderer, render_report, rounded_rect  # noqa: E402
from parking_guide.pipeline import ParkingAssistant  # noqa: E402
from parking_guide.report import municipal_payload  # noqa: E402
from parking_guide.synth import LotLayout, ParkingLotWorld, Photometric  # noqa: E402
from parking_guide.text import draw_texts, text_width  # noqa: E402
from parking_guide.vehicles import get_vehicle  # noqa: E402

W, H_CAM, H_CAP = 1280, 720, 92
H = H_CAM + H_CAP
GREEN, AMBER, WHITE, SOFT, DARK = (96, 200, 72), (20, 176, 255), (250, 250, 250), (190, 190, 190), (28, 28, 30)
SR = 44100


@dataclass
class Timeline:
    fps: int
    frames: list = field(default_factory=list)  # BGR (H, W)
    beeps: list = field(default_factory=list)  # 프레임별 비프 간격 (None/0/초)
    chimes: list = field(default_factory=list)  # 음성 안내가 나온 프레임 번호

    def add(self, img: np.ndarray, beep=None, chime=False) -> None:
        if chime:
            self.chimes.append(len(self.frames))
        self.frames.append(img)
        self.beeps.append(beep)

    def hold(self, img: np.ndarray, seconds: float) -> None:
        for _ in range(int(seconds * self.fps)):
            self.add(img)

    def crossfade(self, a: np.ndarray, b: np.ndarray, seconds: float = 0.4) -> None:
        n = max(1, int(seconds * self.fps))
        for i in range(n):
            t = (i + 1) / (n + 1)
            self.add(cv2.addWeighted(a, 1 - t, b, t, 0))


# ---------------------------------------------------------------------- 카드 화면
def card(title: str, lines: list[tuple[str, tuple]] | None = None, kicker: str = "", foot: str = "") -> np.ndarray:
    yy = np.linspace(0, 1, H)[:, None, None]
    img = (np.array([34, 30, 28]) * (1 - yy) + np.array([18, 16, 16]) * yy).astype(np.uint8)
    img = np.repeat(img, W, axis=1).copy()
    texts = []
    y = 170
    if kicker:
        texts.append((kicker, (110, y - 56), 26, AMBER))
    texts.append((title, (110, y), 54, WHITE))
    y += 110
    for text, color in (lines or []):
        size = 30
        if text.startswith("· "):
            cv2.circle(img, (122, y + 20), 6, color, -1, cv2.LINE_AA)
            texts.append((text[2:], (142, y), size, color))
        else:
            texts.append((text, (110, y), size, color))
        y += 58
    if foot:
        texts.append((foot, (110, H - 80), 20, SOFT))
    draw_texts(img, texts)
    return img


def title_card() -> np.ndarray:
    img = card("후방 주차 가이드", [
        ("벽에 칠하던 수직 주차선을, 후방카메라 화면 속으로", SOFT),
    ], kicker="Pleos Playground 차량 앱", foot="합성 영상 기반 시뮬레이션 시연 · 실제 차량 카메라 연동 전")
    # 아이콘: 주차칸 + 세로 기준선
    cx, cy = 1030, 420
    cv2.rectangle(img, (cx - 110, cy - 150), (cx - 90, cy + 150), GREEN, -1)
    cv2.rectangle(img, (cx + 90, cy - 150), (cx + 110, cy + 150), GREEN, -1)
    cv2.rectangle(img, (cx - 110, cy - 170), (cx + 110, cy - 150), WHITE, -1)
    cv2.rectangle(img, (cx - 5, cy - 130), (cx + 5, cy + 130), AMBER, -1)
    return img


# ---------------------------------------------------------------------- 자막 띠
def _fit(text: str, size: int, max_w: int) -> int:
    """한 줄에 들어가도록 글자 크기를 줄인다."""
    while size > 14 and text_width(text, size) > max_w:
        size -= 1
    return size


def with_caption(frame: np.ndarray, caption: str, voice: str | None) -> np.ndarray:
    """카메라 화면 아래 자막 띠: 윗줄 = 기능 설명, 아랫줄 = 음성 안내 문구."""
    out = np.zeros((H, W, 3), np.uint8)
    out[:H_CAM] = frame
    out[H_CAM:] = (22, 22, 24)
    texts = []
    tag = "합성 영상 시뮬레이션"
    tag_w = text_width(tag, 15) + 36
    if caption:
        size = _fit(caption, 24, W - 56 - tag_w)
        texts.append((caption, (28, H_CAM + 12), size, WHITE))
    if voice:
        x, cy = 30, H_CAM + 66
        pts = np.array([[x, cy - 7], [x + 8, cy - 7], [x + 17, cy - 15], [x + 17, cy + 15], [x + 8, cy + 7], [x, cy + 7]])
        cv2.fillPoly(out, [pts], GREEN, cv2.LINE_AA)
        for r in (22, 30):
            cv2.ellipse(out, (x + 11, cy), (r, r), 0, -40, 40, GREEN, 2, cv2.LINE_AA)
        q = f"음성 안내  “{voice}”"
        texts.append((q, (x + 52, H_CAM + 52), _fit(q, 24, W - 120 - tag_w), GREEN))
    texts.append((tag, (W - tag_w + 18, H_CAM + H_CAP - 28), 15, SOFT))
    draw_texts(out, texts)
    return out


class CaptionRule:
    """상황(안내 종류, 끝선 거리)에 따라 기능 설명 자막을 고른다. 한 번 뜬 자막은 최소 min_s 초 유지."""

    def __init__(self, rules, fps: int, min_s: float = 3.0):
        self.rules = rules  # [(조건 함수, 문구)], 한 번씩만
        self.used: set[int] = set()
        self.current, self.age, self.min_frames = "", 10 ** 9, int(min_s * fps)

    def update(self, fo) -> str:
        self.age += 1
        if self.age >= self.min_frames:
            for i, (cond, text) in enumerate(self.rules):
                if i not in self.used and cond(fo):
                    self.used.add(i)
                    self.current, self.age = text, 0
                    break
        return self.current


# ---------------------------------------------------------------------- 주행 장면
def run_scene(tl: Timeline, cam, vehicle, *, door="center", layout=LotLayout(), seed=3, start=(70.0, -330.0, -16.0),
              speed=3.0, gain=0.55, lag=0.12, follow=True, steer_fixed=None, max_s=30.0, rules=(), end_hold=1.2,
              stop_extra_cm=25.0):
    pa = ParkingAssistant(cam, vehicle, door)
    ren = GuideRenderer(cam, vehicle)
    world = ParkingLotWorld(layout, seed=seed)
    rng = np.random.default_rng(seed)
    sim = DriverSim(vehicle, bx=start[0], by=start[1], heading_deg=start[2], gain=gain, lag=lag)
    if steer_fixed is not None:
        sim.steer_deg = steer_fixed
    stop_by = layout.slot_length_cm - layout.line_width_cm - stop_extra_cm
    cap = CaptionRule(list(rules), tl.fps)
    voice, voice_left = None, 0
    fo = img = None
    hold_frames, ds = 0, 0.0
    for n in range(int(max_s * tl.fps)):
        img, _ = world.render(sim.pose(), cam, Photometric(), rng)
        fo = pa.step(img, sim.steer_deg, ds, t=n / tl.fps)
        if fo.speech:
            voice, voice_left = fo.speech, int(2.6 * tl.fps)
        voice_left -= 1
        vis = ren.render(img, fo.slot, fo.alignment, fo.guidance, sim.steer_deg)
        tl.add(with_caption(vis, cap.update(fo), voice if voice_left > 0 else None), fo.beep_interval_s, bool(fo.speech))
        if sim.by >= stop_by:
            ds = 0.0
            hold_frames += 1
            if hold_frames > end_hold * tl.fps:
                break
        else:
            g = fo.guidance
            if steer_fixed is None:
                sim.step(speed, g.desired_steer_deg if (follow and g.level == "warn") else None)
            else:
                sim.step(speed, steer_fixed)
            ds = speed
    return pa, fo, img, sim


# ---------------------------------------------------------------------- 오디오
def tone(freq: float, dur: float, vol: float = 0.35) -> np.ndarray:
    t = np.arange(int(SR * dur)) / SR
    env = np.minimum(1, np.minimum(t / 0.005, (dur - t) / 0.01))
    return vol * env * np.sin(2 * np.pi * freq * t)


def build_audio(tl: Timeline, path: Path) -> None:
    total = len(tl.frames) / tl.fps
    audio = np.zeros(int(SR * total) + SR)
    next_beep = 0.0
    for i, b in enumerate(tl.beeps):
        t = i / tl.fps
        if b is None:
            next_beep = t
            continue
        if t >= next_beep:
            dur = 1 / tl.fps if b == 0 else 0.07
            s = int(t * SR)
            seg = tone(1400, dur + (0.002 if b == 0 else 0), 0.22)
            audio[s:s + len(seg)] += seg
            next_beep = t + (1 / tl.fps if b == 0 else b)
    for i in tl.chimes:  # 음성 안내 알림음 (딩동)
        s = int(i / tl.fps * SR)
        for k, f in enumerate((880, 660)):
            seg = tone(f, 0.12, 0.25)
            audio[s + k * int(0.13 * SR): s + k * int(0.13 * SR) + len(seg)] += seg
    audio = np.clip(audio, -1, 1)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((audio * 32767).astype(np.int16).tobytes())


def encode(tl: Timeline, wav: Path, out: Path) -> None:
    cmd = ["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", f"{W}x{H}",
           "-r", str(tl.fps), "-i", "-", "-i", str(wav), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "21",
           "-preset", "medium", "-c:a", "aac", "-b:a", "128k", "-shortest", "-movflags", "+faststart", str(out)]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    for f in tl.frames:
        p.stdin.write(f.tobytes())
    p.stdin.close()
    p.wait()
    if p.returncode != 0:
        raise SystemExit("ffmpeg 인코딩 실패")


# ---------------------------------------------------------------------- 대본
def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ROOT / "output/pleos_parking_guide_demo.mp4"))
    ap.add_argument("--fps", type=int, default=15)
    ap.add_argument("--quick", action="store_true", help="카드 시간을 줄인 빠른 미리보기")
    args = ap.parse_args()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    tl = Timeline(args.fps)
    q = 0.4 if args.quick else 1.0
    cam = CameraModel.wide_angle()
    v = get_vehicle("sonata")

    # 1. 타이틀
    t1 = title_card()
    tl.hold(t1, 4 * q)
    # 2. 문제
    t2 = card("왜 만들었나요?", [
        ("· 공주시는 후진 주차를 돕기 위해 주차칸 끝 벽에 수직선을 직접 칠했어요", WHITE),
        ("· 하지만 모든 벽에 칠할 수는 없고, 칠한 선도 닳아요", WHITE),
        ("· 초보 운전자는 '얼마나 돌려야 하는지', '어디에 멈출지' 몰라 여러 번 다시 넣어요", WHITE),
        ("→ 그 수직선을 후방카메라 화면에 그려 주고, 핸들까지 안내하면 어떨까?", AMBER),
    ], kicker="문제")
    tl.crossfade(t1, t2)
    tl.hold(t2, 6.5 * q)

    # 3. 메인 시연
    t3 = card("후진 주차 시연", [
        ("오른쪽으로 70cm, 16° 비스듬히 시작 · 운전자는 안내를 따라 핸들을 조작", SOFT),
    ], kicker="시연 1")
    tl.crossfade(t2, t3)
    tl.hold(t3, 2.5 * q)
    rules = [
        (lambda fo: fo.slot is not None, "바닥 주차선을 인식해 벽 쪽에 가상 수직선을 세워요 (공주시 벽 페인트 선 대신)"),
        (lambda fo: fo.guidance.level == "warn", "얼마나 돌릴지 '바퀴 수'로 말하고, 왼쪽 다이얼의 초록 구간에 맞추면 돼요"),
        (lambda fo: fo.alignment is not None, "아래 게이지: 운전석 쪽·동승석 쪽 여유를 수평계처럼 보여 줘요"),
        (lambda fo: fo.alignment is not None and fo.alignment.end_dist_cm is not None and fo.alignment.end_dist_cm < 240,
         "끝선 2.5m 앞부터: 지금 핸들 그대로 가면 멈출 자리(고스트카)를 미리 보여 줘요"),
        (lambda fo: fo.beep_interval_s is not None, "끝선이 가까울수록 비프음이 빨라져요"),
        (lambda fo: fo.guidance.level == "stop", "끝선 30cm 앞: 멈추세요"),
    ]
    pa, fo, last_img, sim = run_scene(tl, cam, v, rules=rules, max_s=40, lag=0.35, gain=1.0)
    last = tl.frames[-1]

    # 4. 리포트
    report = pa.finish()
    rep = render_report(report, W, H_CAM, last_img)
    rep_frame = with_caption(rep, "R단에서 나오면 주차 리포트: 점수, 최종 위치, 맞춤 조언 한 줄 · 쓸수록 실력이 늘어요", None)
    tl.crossfade(last, rep_frame, 0.6)
    tl.hold(rep_frame, 6 * q)

    # 5. 문콕 방지
    t5 = card("문콕 방지 하차 모드", [
        ("가운데가 아니라 '내릴 공간'을 목표로: 운전석 여유 모드", SOFT),
    ], kicker="시연 2")
    tl.crossfade(rep_frame, t5)
    tl.hold(t5, 2.2 * q)
    run_scene(tl, cam, v, door="driver", seed=7, start=(10.0, -60.0, -3.0), gain=0.25, max_s=16, rules=[
        (lambda fo: fo.alignment is not None, "목표선을 동승석 쪽으로 15cm 옮겨 운전석 문 열 공간을 만들어요 (반대쪽 8cm 이상 유지)"),
    ])

    # 6. 선이 희미할 때
    t6 = card("선이 희미할 때", [("확실하지 않으면 확실하지 않다고 말해요", SOFT)], kicker="시연 3")
    tl.crossfade(tl.frames[-1], t6)
    tl.hold(t6, 2.0 * q)
    run_scene(tl, cam, v, layout=LotLayout(line_wear=0.72), seed=2, start=(5.0, -80.0, 2.0), speed=2.0,
              steer_fixed=30.0, max_s=5.5, rules=[
                  (lambda fo: True, "인식 신뢰도가 낮으면 핸들 안내를 멈추고 직접 확인을 요청해요 (오른쪽 아래 신뢰도 막대)"),
              ])

    # 7. 다시 넣기
    t7 = card("이미 늦었다면", [("억지로 밀어 넣지 않게", SOFT)], kicker="시연 4")
    tl.crossfade(tl.frames[-1], t7)
    tl.hold(t7, 2.0 * q)
    run_scene(tl, cam, v, seed=7, start=(55.0, 190.0, 1.0), speed=2.0, steer_fixed=0.0, max_s=5.5, rules=[
        (lambda fo: True, "어떤 핸들 각도로도 선 안에 못 들어가면: '앞으로 나갔다가 다시 넣으세요'"),
    ], end_hold=0.5)

    # 8. 지자체 데이터
    payload = municipal_payload(report, 36.4466, 127.1190)
    t8 = card("주차할 때마다, 주차선 관리 데이터가 돼요", [
        ("· 페인트가 남은 비율로 선 상태 판정: 양호 / 약간 닳음 / 재도색 권장", WHITE),
        ("· 다시 넣기·소요 시간으로 '어려운 주차칸'을 찾아 벽 수직선이 필요한 곳을 알려요", WHITE),
        ("· 운전자 동의 시에만, 영상·차량번호 없이 위치는 약 100m 단위로 익명 전송", WHITE),
        ("  " + json.dumps(payload["line"], ensure_ascii=False), SOFT),
    ], kicker="공공 가치 · 공주시 사례와 연결")
    tl.crossfade(tl.frames[-1], t8)
    tl.hold(t8, 7 * q)

    # 9. 기술
    t9 = card("Pleos Playground로 이렇게 만들었어요", [
        ("· 조향각: Pleos Vehicle SDK SteeringWheel (앞바퀴 각도 × 조향비 → 핸들 각도)", WHITE),
        ("· 음성 안내: Pleos TextToSpeech · 위치: Pleos FusedLocation · 기어/차속: AAOS Car API", WHITE),
        ("· Kotlin 앱 빌드·Lint 통과 · Kotlin 인식기 = Python 프로토타입 (치우침 차이 0.1cm 이내)", WHITE),
        ("· 현재 SDK엔 후방카메라 API가 없어 합성 영상으로 시연 → 허용되면 영상 입력만 연결", AMBER),
    ], kicker="기술")
    tl.crossfade(t8, t9)
    tl.hold(t9, 7 * q)

    # 10. 마무리
    t10 = card("후방 주차 가이드", [
        ("가상 벽 수직선 · 핸들 다이얼 · 고스트카 · 문콕 방지 · 신뢰도 · 주차 리포트", SOFT),
        ("모든 벽에 칠하지 않아도, 모두가 반듯하게", AMBER),
    ], kicker="Pleos Playground 차량 앱")
    tl.crossfade(t9, t10)
    tl.hold(t10, 4 * q)

    wav = out.with_suffix(".wav")
    build_audio(tl, wav)
    encode(tl, wav, out)
    wav.unlink(missing_ok=True)
    print(f"저장: {out} ({len(tl.frames) / tl.fps:.1f}초, {len(tl.frames)}프레임)")
    print(f"리포트: {report.score}점 {report.grade_ko} · 최종 치우침 {report.offset_cm}cm · 각도 {report.yaw_deg}°")


if __name__ == "__main__":
    main()
