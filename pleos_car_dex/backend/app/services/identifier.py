"""2단계(a): 크롭된 차량 이미지 -> 브랜드/모델 식별.

MVP 는 Claude 비전으로 식별한다. 시연 차종 전용 분류기(YOLO 커스텀 학습 등)를
붙이려면 이 클래스의 identify() 만 교체하면 된다.
"""
import base64
import hashlib
import io
import logging

import anthropic
from PIL import Image

from app.config import Settings
from app.schemas import VehicleIdentity
from app.services import llm
from app.services.catalog import load_catalog

log = logging.getLogger(__name__)


def _system_prompt() -> str:
    names = ", ".join(f"{v['brand']} {v['model']}" for v in load_catalog())
    return (
        "당신은 자동차 식별 전문가입니다. 이미지 속 차량의 브랜드와 모델을 식별하세요.\n"
        f"시연용 우선 후보 차종: {names}\n"
        "후보 중 하나로 보이면 후보 표기(한글)를 그대로 쓰고, 아니면 가장 가능성 높은 모델을 한글로 쓰세요.\n"
        "차량이 아니거나 판별이 불가능하면 is_vehicle=false, confidence 를 낮게 주세요."
    )


class VehicleIdentifier:
    def __init__(self, settings: Settings):
        self.settings = settings

    def identify(self, crop: Image.Image, hint: str = "") -> VehicleIdentity | None:
        if self.settings.identifier_mode == "mock" or not self.settings.llm_enabled:
            return self._mock(crop, hint)
        try:
            return llm.parse(
                output_format=VehicleIdentity,
                system=_system_prompt(),
                content=[
                    {"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": _to_b64(crop)}},
                    {"type": "text", "text": "이 차량을 식별해 주세요."},
                ],
                max_tokens=1024,
            )
        except (anthropic.APIConnectionError, anthropic.RateLimitError, anthropic.APIStatusError) as e:
            log.warning("identify failed, mock 으로 대체: %s", e)
            return self._mock(crop, hint)

    @staticmethod
    def _mock(crop: Image.Image, hint: str) -> VehicleIdentity:
        """오프라인 시연용: 파일명 힌트(예: 'ioniq-5.jpg')가 카탈로그 id 와 맞으면 그 차량, 아니면 이미지 해시로 결정."""
        catalog = load_catalog()
        hint = hint.lower()
        chosen = next((v for v in catalog if v["id"] in hint or v["id"].split("-", 1)[1] in hint), None)
        if chosen is None:
            digest = hashlib.md5(crop.tobytes()).digest()
            chosen = catalog[digest[0] % len(catalog)]
        return VehicleIdentity(
            is_vehicle=True, brand=chosen["brand"], model=chosen["model"], body_type=chosen["body_type"], confidence=0.9
        )


def _to_b64(img: Image.Image, max_side: int = 1024) -> str:
    img = img.convert("RGB")
    img.thumbnail((max_side, max_side))
    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=85)
    return base64.standard_b64encode(buf.getvalue()).decode()
