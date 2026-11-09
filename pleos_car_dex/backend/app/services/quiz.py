"""아이용 차 맞히기 퀴즈 — YOLOv8 결과만으로 문제를 낸다 (차종 판별 AI 불필요).

- 종류: YOLO 클래스 (car/bus/truck/motorcycle) -> 승용차/버스/트럭/오토바이
- 색깔: 잘라낸 차량 이미지 가운데(차체) 영역의 대표 색 (HSV)
"""
import colorsys
import json
import random
import uuid
from collections import Counter

from PIL import Image

from app.schemas import Detection, QuizQuestion, QuizStars

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


# ---------------- 별 / 스티커 ----------------
# 채점은 앱이 한다(정답 포함 출제). 별은 랭킹 점수와 분리 — 아이용 보상은 쇼룸 스티커로만.


def _path(store):
    return store.extra_path("quiz.json")


def _load(store) -> dict:
    p = _path(store)
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else {"stars": 0, "answered": []}


def stars(store) -> int:
    return _load(store)["stars"]


def sticker_rules() -> list[tuple[int, str]]:
    """(필요 별, 스티커 이름) 오름차순 — decor_items.json 의 quiz:<n> 아이템"""
    from app.services.decor import _raw
    out = [(int(i["unlock"].split(":")[1]), i["name"]) for i in _raw() if i["unlock"].startswith("quiz:")]
    return sorted(out)


def add_star(store, question_id: str) -> QuizStars:
    d = _load(store)
    if question_id not in d["answered"]:  # 같은 문제로 별 중복 금지
        d["answered"] = (d["answered"] + [question_id])[-500:]
        d["stars"] += 1
        _path(store).write_text(json.dumps(d, ensure_ascii=False), encoding="utf-8")
        new = True
    else:
        new = False
    n = d["stars"]
    rules = sticker_rules()
    unlocked = next((name for need, name in rules if need == n), None) if new else None
    nxt = next(((need, name) for need, name in rules if need > n), None)
    return QuizStars(
        stars=n, unlocked_sticker=unlocked,
        next_sticker=nxt[1] if nxt else None, stars_to_next=(nxt[0] - n) if nxt else 0,
        stickers_owned=sum(need <= n for need, _ in rules), stickers_total=len(rules),
    )
