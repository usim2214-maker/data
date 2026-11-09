"""아이용 차 맞히기 퀴즈 — YOLOv8 결과만으로 문제를 낸다 (차종 판별 AI 불필요).

- 종류: YOLO 클래스 (car/bus/truck/motorcycle) -> 승용차/버스/트럭/오토바이
- 색깔: 잘라낸 차량 이미지 가운데(차체) 영역의 대표 색 (HSV)
"""
import colorsys
import random
import uuid
from collections import Counter

from PIL import Image

from app.schemas import Detection, QuizQuestion

KIND_NAMES = {"car": "승용차", "bus": "버스", "truck": "트럭", "motorcycle": "오토바이"}
KIND_EMOJI = {"승용차": "🚗", "버스": "🚌", "트럭": "🚚", "오토바이": "🏍️"}
COLOR_HEX = {
    "빨간색": "#E53935", "주황색": "#FB8C00", "노란색": "#FDD835", "초록색": "#43A047", "파란색": "#1E88E5",
    "보라색": "#8E24AA", "흰색": "#FAFAFA", "검은색": "#212121", "회색": "#9E9E9E",
}


def dominant_color(crop: Image.Image) -> str:
    """차체가 있을 가운데 아래쪽 영역에서 가장 많은 색 (배경·창문 영향 줄이기)"""
    w, h = crop.size
    body = crop.convert("RGB").crop((int(w * 0.2), int(h * 0.45), int(w * 0.8), int(h * 0.8))).resize((40, 20))
    votes: Counter[str] = Counter()
    raw = body.tobytes()
    for i in range(0, len(raw), 3):
        r, g, b = raw[i], raw[i + 1], raw[i + 2]
        hh, ss, vv = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        if vv < 0.22:
            votes["검은색"] += 1
        elif ss < 0.18:
            votes["흰색" if vv > 0.8 else "회색"] += 1
        else:
            deg = hh * 360
            name = ("빨간색" if deg < 15 or deg >= 340 else "주황색" if deg < 40 else "노란색" if deg < 70
                    else "초록색" if deg < 170 else "파란색" if deg < 255 else "보라색")
            votes[name] += 2  # 채도 있는 색을 우선 (회색 도로·유리보다)
    return votes.most_common(1)[0][0] if votes else "회색"


def make_question(detection: Detection, crop: Image.Image, kind_or_color: str | None = None, rng: random.Random | None = None) -> QuizQuestion:
    rng = rng or random.Random()
    qtype = kind_or_color or rng.choice(["kind", "color"])
    if qtype == "kind":
        answer = KIND_NAMES.get(detection.label, "승용차")
        others = [k for k in KIND_NAMES.values() if k != answer]
        choices = [answer] + rng.sample(others, 2)
        prompt = "방금 지나간 차는 어떤 차일까요?"
    else:
        answer = dominant_color(crop)
        others = [c for c in COLOR_HEX if c != answer]
        choices = [answer] + rng.sample(others, 2)
        prompt = "방금 지나간 차는 무슨 색일까요?"
    rng.shuffle(choices)
    return QuizQuestion(
        id=uuid.uuid4().hex[:8], type=qtype, prompt=prompt, choices=choices, answer=answer,
        choice_icons=[KIND_EMOJI.get(c, "") if qtype == "kind" else COLOR_HEX[c] for c in choices],
        detection=detection,
    )
