"""한글 텍스트 그리기. OpenCV putText는 한글을 못 그리므로 Pillow + 한글 폰트를 쓴다.
한글 폰트를 못 찾으면 영어 문구로 대체한다."""
from __future__ import annotations

import os
from functools import lru_cache

import cv2
import numpy as np

_FONT_CANDIDATES = [
    os.environ.get("PARKING_GUIDE_FONT", ""),
    "C:/Windows/Fonts/malgun.ttf",
    "C:/Windows/Fonts/malgunbd.ttf",
    "/System/Library/Fonts/AppleSDGothicNeo.ttc",
    "/Library/Fonts/AppleGothic.ttf",
    "/usr/share/fonts/truetype/nanum/NanumGothicBold.ttf",
    "/usr/share/fonts/truetype/nanum/NanumGothic.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
]


@lru_cache(maxsize=None)
def _font_path() -> str | None:
    for p in _FONT_CANDIDATES:
        if p and os.path.exists(p):
            return p
    return None


@lru_cache(maxsize=32)
def _font(size: int):
    from PIL import ImageFont

    return ImageFont.truetype(_font_path(), size)


def korean_available() -> bool:
    if _font_path() is None:
        return False
    try:
        import PIL  # noqa: F401
    except ImportError:
        return False
    return True


def text_width(text: str, size: int) -> int:
    if korean_available():
        return int(_font(size).getlength(text))
    return int(cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, size / 30.0, 2)[0][0])


def draw_texts(img: np.ndarray, items: list[tuple[str, tuple[int, int], int, tuple[int, int, int]]]) -> np.ndarray:
    """items: (문구, (x, y) 왼쪽 위, 글자 크기 px, BGR 색). img를 직접 수정하고 반환한다."""
    if not items:
        return img
    if korean_available():
        from PIL import Image, ImageDraw

        pil = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
        draw = ImageDraw.Draw(pil)
        for text, (x, y), size, color in items:
            draw.text((x, y), text, font=_font(size), fill=(color[2], color[1], color[0]),
                      stroke_width=max(1, size // 14), stroke_fill=(0, 0, 0))
        img[:] = cv2.cvtColor(np.asarray(pil), cv2.COLOR_RGB2BGR)
    else:
        for text, (x, y), size, color in items:
            scale = size / 30.0
            org = (x, y + size)
            cv2.putText(img, text, org, cv2.FONT_HERSHEY_SIMPLEX, scale, (0, 0, 0), 4, cv2.LINE_AA)
            cv2.putText(img, text, org, cv2.FONT_HERSHEY_SIMPLEX, scale, color, 2, cv2.LINE_AA)
    return img
