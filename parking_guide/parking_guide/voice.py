"""음성 안내와 거리 비프음 로직.

후진할 때 운전자는 사이드미러와 뒤를 함께 봐야 하므로 화면만 볼 수 없다.
화면 안내와 같은 내용을 짧은 음성으로 알려 주고, 끝선까지 거리는 주차 센서처럼
비프음 간격으로 알려 준다.

이 모듈은 '언제 무엇을 말할지'만 정한다. 실제 재생은 Pleos Connect SDK의
TextToSpeech(음성)와 오디오 출력에 연결한다. 시간(초)을 인자로 받으므로 테스트하기 쉽다.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from .guidance import Guidance


def beep_interval_s(end_dist_cm: float | None) -> float | None:
    """끝선까지 거리에 따른 비프음 간격. None이면 울리지 않음, 0이면 연속음."""
    if end_dist_cm is None or end_dist_cm > 150:
        return None
    if end_dist_cm <= 30:
        return 0.0
    # 150cm → 0.8초, 30cm → 0.12초 사이를 선형으로
    return round(0.12 + (end_dist_cm - 30) / 120 * 0.68, 2)


@dataclass
class VoicePrompter:
    min_gap_s: float = 2.5  # 일반 안내 사이 최소 간격
    stop_repeat_s: float = 2.0  # '멈추세요' 반복 간격
    callouts_cm: tuple[float, ...] = (100.0, 50.0)  # 거리 알림 지점
    last_t: float = -1e9
    last_key: str | None = None
    said_callouts: set = field(default_factory=set)

    def update(self, g: Guidance | None, end_dist_cm: float | None, t: float) -> str | None:
        """이번 시점에 말할 문장 (없으면 None)."""
        if g is None:
            return None
        if g.level == "stop":
            if self.last_key != "stop" or t - self.last_t >= self.stop_repeat_s:
                return self._say("stop", "멈추세요", t)
            return None
        # 거리 알림은 한 번씩만
        if end_dist_cm is not None:
            for c in self.callouts_cm:
                if end_dist_cm <= c and c not in self.said_callouts:
                    self.said_callouts.add(c)
                    phrase = "1미터 남았어요" if c >= 100 else f"{int(c)}센티 남았어요"
                    return self._say(f"dist{c}", phrase, t)
        if g.key == self.last_key or g.level == "info":
            return None
        if t - self.last_t < self.min_gap_s:
            return None
        return self._say(g.key, g.text_ko, t)

    def reset(self) -> None:
        """새 주차 시도를 시작할 때 (예: R단에 다시 들어갈 때)."""
        self.last_t, self.last_key = -1e9, None
        self.said_callouts.clear()

    def _say(self, key: str, text: str, t: float) -> str:
        self.last_key, self.last_t = key, t
        return text
