"""주차 리포트 카드(코칭)와 주차선 상태 리포트(지자체 공유용).

1) 운전자: 주차를 마치면 점수, 최종 위치, 핸들 수정 횟수, 맞춤 조언 한 줄을 보여 준다.
   지난 기록과 비교해 실력이 느는 것을 보여 줘서, 초보가 반복해서 쓰고 싶게 만든다.
2) 지자체: 주차할 때마다 측정되는 주차선 상태(페인트가 남은 비율, 인식 신뢰도)와 그 칸의
   주차 난이도(다시 넣기, 소요 시간)를 익명으로 모으면, 재도색이 필요한 주차장과
   벽 수직선 같은 보조 표시가 필요한 '어려운 칸'을 데이터로 찾을 수 있다.
   공주시가 벽에 수직선을 칠하던 일을 '어디에 칠할지 아는 일'로 바꿔 준다.

개인정보: 영상, VIN, 정확한 위치는 보내지 않는다. 위치는 약 100m 단위로 반올림한다.
"""
from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path

import numpy as np

from .detector import Alignment, SlotEstimate
from .guidance import Guidance


@dataclass
class LineCondition:
    coverage_left: float  # 페인트가 남은 비율 (중앙값)
    coverage_right: float
    confidence: float  # 인식 신뢰도 (중앙값)
    samples: int

    @property
    def worst_coverage(self) -> float:
        return min(self.coverage_left, self.coverage_right)

    @property
    def needs_repaint(self) -> bool:
        return self.worst_coverage < 0.6

    @property
    def label_ko(self) -> str:
        if self.worst_coverage >= 0.8:
            return "양호"
        if self.worst_coverage >= 0.6:
            return "약간 닳음"
        return "재도색 권장"


@dataclass
class ParkingReport:
    score: int
    grade_ko: str
    offset_cm: float  # 최종 치우침 (목표 기준, + = 오른쪽)
    yaw_deg: float
    clear_driver_cm: float
    clear_passenger_cm: float
    duration_s: float
    corrections: int  # 핸들 방향을 바꾼 횟수
    retries: int  # "다시 넣으세요" 안내 횟수
    low_conf_ratio: float  # 인식이 불확실했던 시간 비율
    tips_ko: list[str]
    line: LineCondition | None
    target_bias_cm: float = 0.0
    trend_ko: str = ""

    def to_dict(self) -> dict:
        d = asdict(self)
        if self.line is not None:
            d["line"]["label_ko"] = self.line.label_ko
            d["line"]["needs_repaint"] = self.line.needs_repaint
        return d


def score_parking(offset_cm: float, yaw_deg: float, min_clear_cm: float, corrections: int, retries: int) -> int:
    s = 100.0
    s -= min(40.0, 2.0 * abs(offset_cm))
    s -= min(30.0, 6.0 * abs(yaw_deg))
    if min_clear_cm < 0:
        s -= 25.0
    elif min_clear_cm < 5:
        s -= 10.0
    s -= min(15.0, 2.0 * max(0, corrections - 4))
    s -= 5.0 * retries
    return int(round(float(np.clip(s, 0, 100))))


def grade_ko(score: int) -> str:
    if score >= 90:
        return "완벽해요"
    if score >= 75:
        return "잘했어요"
    if score >= 55:
        return "괜찮아요"
    return "연습해 봐요"


def make_tips(offset_cm: float, yaw_deg: float, corrections: int, retries: int,
              low_conf_ratio: float, target_bias_cm: float) -> list[str]:
    """가장 도움이 될 조언부터. 화면에는 첫 번째만 크게 보여 준다."""
    tips: list[str] = []
    if abs(yaw_deg) >= 1.5:
        tips.append("끝선 1m 전부터는 핸들을 똑바로 풀어 두면 차가 반듯하게 들어가요")
    if abs(offset_cm) >= 8:
        side = "오른쪽" if offset_cm > 0 else "왼쪽"
        tips.append(f"{side}으로 치우쳤어요. 후진을 시작할 때 차 뒤를 주차칸 가운데에 더 가깝게 두세요")
    if corrections > 6:
        tips.append("핸들을 자주 바꾸기보다 다이얼의 초록 구간에 맞춘 뒤 잠깐 유지해 보세요")
    if retries:
        tips.append("무리하지 않고 다시 넣은 건 좋은 판단이에요")
    if low_conf_ratio > 0.3:
        tips.append("이 주차장은 선이 희미해서 인식이 어려웠어요. 주변을 더 꼼꼼히 확인하세요")
    if target_bias_cm and abs(offset_cm) < 8:
        tips.append("문 열 공간까지 생각한 주차예요")
    if not tips:
        tips.append("반듯하고 가운데예요. 이 감각을 기억해 두세요")
    return tips


@dataclass
class ParkingSession:
    """한 번의 후진 주차를 기록한다. 매 프레임 add()를 부르고 끝나면 finish()."""

    t0: float | None = None
    t_last: float = 0.0
    steer_hist: list[float] = field(default_factory=list)
    retries: int = 0
    frames: int = 0
    low_conf_frames: int = 0
    coverages_l: list[float] = field(default_factory=list)
    coverages_r: list[float] = field(default_factory=list)
    confs: list[float] = field(default_factory=list)
    last_align: Alignment | None = None
    last_key: str | None = None

    def add(self, align: Alignment | None, slot: SlotEstimate | None, guidance: Guidance | None,
            steer_deg: float | None, t: float, measured: SlotEstimate | None = None) -> None:
        """measured: 이번 프레임에 실제로 검출된 주차칸 (추적 예측이 아닌 것). 선 상태 측정에 쓴다."""
        if slot is None:
            return
        if self.t0 is None:
            self.t0 = t
        self.t_last = t
        self.frames += 1
        if steer_deg is not None:
            self.steer_hist.append(float(steer_deg))
        if slot.confidence < 0.45:
            self.low_conf_frames += 1
        if guidance is not None and guidance.key == "retry" and self.last_key != "retry":
            self.retries += 1
        self.last_key = guidance.key if guidance is not None else None
        if measured is not None:
            self.coverages_l.append(measured.left.coverage)
            self.coverages_r.append(measured.right.coverage)
            self.confs.append(measured.confidence)
        if align is not None:
            self.last_align = align

    def corrections(self, deadband_deg: float = 20.0) -> int:
        """핸들을 돌리는 방향이 바뀐 횟수 (작은 떨림은 무시)."""
        if len(self.steer_hist) < 3:
            return 0
        n, direction, anchor = 0, 0, self.steer_hist[0]
        for w in self.steer_hist[1:]:
            d = w - anchor
            if abs(d) < deadband_deg:
                continue
            new_dir = 1 if d > 0 else -1
            if direction and new_dir != direction:
                n += 1
            direction, anchor = new_dir, w
        return n

    def line_condition(self) -> LineCondition | None:
        if not self.coverages_l:
            return None
        return LineCondition(float(np.median(self.coverages_l)), float(np.median(self.coverages_r)),
                             float(np.median(self.confs)), len(self.coverages_l))

    def finish(self) -> ParkingReport | None:
        a = self.last_align
        if a is None:
            return None
        corr = self.corrections()
        low = self.low_conf_frames / max(1, self.frames)
        min_clear = min(a.clear_left_cm, a.clear_right_cm)
        score = score_parking(a.offset_cm, a.yaw_deg, min_clear, corr, self.retries)
        return ParkingReport(
            score=score, grade_ko=grade_ko(score), offset_cm=round(a.offset_cm, 1), yaw_deg=round(a.yaw_deg, 2),
            clear_driver_cm=round(a.clear_left_cm, 1), clear_passenger_cm=round(a.clear_right_cm, 1),
            duration_s=round(self.t_last - (self.t_last if self.t0 is None else self.t0), 1), corrections=corr, retries=self.retries,
            low_conf_ratio=round(low, 2), tips_ko=make_tips(a.offset_cm, a.yaw_deg, corr, self.retries, low,
                                                            a.target_bias_cm),
            line=self.line_condition(), target_bias_cm=a.target_bias_cm)


class History:
    """리포트 기록 (기기 안에만 저장). 최근 5회와 그 전 5회 평균을 비교해 성장을 보여 준다."""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)

    def load(self) -> list[dict]:
        try:
            return json.loads(self.path.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError):
            return []

    def add(self, report: ParkingReport) -> ParkingReport:
        items = self.load()
        scores = [it["score"] for it in items] + [report.score]
        report.trend_ko = trend_text(scores)
        items.append(report.to_dict())
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(items, ensure_ascii=False, indent=1), encoding="utf-8")
        return report


def trend_text(scores: list[int]) -> str:
    if len(scores) < 2:
        return "첫 기록이에요"
    if len(scores) < 6:
        best = max(scores[:-1])
        if scores[-1] > best:
            return f"최고 기록이에요 (+{scores[-1] - best}점)"
        return f"지금까지 {len(scores)}번 연습했어요"
    recent, prev = np.mean(scores[-5:]), np.mean(scores[-10:-5])
    diff = int(round(recent - prev))
    if diff > 0:
        return f"최근 5번 평균이 그 전보다 {diff}점 올랐어요"
    if diff < 0:
        return f"최근 5번 평균이 {-diff}점 내려갔어요. 천천히 해 봐요"
    return "실력이 꾸준해요"


def municipal_payload(report: ParkingReport, lat: float | None, lon: float | None,
                      slot_heading_deg: float | None = None) -> dict:
    """지자체 공유용 익명 데이터. 영상·차량 식별정보 없이, 위치는 소수 셋째 자리(약 100m)로 반올림한다.

    위치는 Pleos Connect SDK의 FusedLocation에서 받는다.
    """
    line = report.line
    return {
        "schema": "parking-line-condition/v1",
        "location": None if lat is None or lon is None else {"lat": round(lat, 3), "lon": round(lon, 3)},
        "slot_heading_deg": None if slot_heading_deg is None else int(round(slot_heading_deg / 10) * 10),
        "line": None if line is None else {
            "coverage_min": round(line.worst_coverage, 2),
            "confidence": round(line.confidence, 2),
            "status": line.label_ko,
            "needs_repaint": line.needs_repaint,
        },
        "difficulty": {
            "retries": report.retries,
            "duration_s": report.duration_s,
            "corrections": report.corrections,
        },
    }
