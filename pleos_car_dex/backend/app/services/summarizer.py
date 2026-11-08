"""2단계(b): 유저 키워드를 프롬프트에 동적으로 주입해 카드 뒷면 요약 생성."""
import json
import logging

import anthropic

from app.config import Settings
from app.schemas import CardSummary, KeywordFact, VehicleIdentity
from app.services import llm
from app.services.catalog import find_vehicle

log = logging.getLogger(__name__)

SYSTEM_PROMPT = (
    "당신은 차량 인포테인먼트 화면에 들어갈 '차량 도감 카드'를 쓰는 에디터입니다.\n"
    "운전 중 한눈에 읽혀야 하므로 각 값은 25자 이내로 짧게 씁니다.\n"
    "사용자가 지정한 키워드마다 정확히 하나의 fact 를 같은 순서로 작성하세요(keyword 는 원문 그대로).\n"
    "참고 데이터가 주어지면 그 값을 우선 사용하고, 모르는 정보는 지어내지 말고 '정보 없음'이라고 쓰세요.\n"
    "one_liner 는 이 차를 소개하는 20자 내외의 한 줄 문구입니다."
)


def build_user_prompt(identity: VehicleIdentity, keywords: list[str], language: str) -> str:
    ref = find_vehicle(identity.brand, identity.model)
    lines = [
        f"차량: {identity.brand} {identity.model} ({identity.body_type})",
        f"키워드: {json.dumps(keywords, ensure_ascii=False)}",
        f"출력 언어: {language}",
    ]
    if ref:
        lines.append(f"참고 데이터: {json.dumps(ref['facts'], ensure_ascii=False)}")
    return "\n".join(lines)


class CardSummarizer:
    def __init__(self, settings: Settings):
        self.settings = settings

    def summarize(self, identity: VehicleIdentity, keywords: list[str], language: str = "ko") -> CardSummary:
        if self.settings.llm_enabled:
            try:
                result = llm.parse(
                    output_format=CardSummary,
                    system=SYSTEM_PROMPT,
                    content=build_user_prompt(identity, keywords, language),
                )
                if result is not None:
                    return result
            except (anthropic.APIConnectionError, anthropic.RateLimitError, anthropic.APIStatusError) as e:
                log.warning("summarize failed, mock 으로 대체: %s", e)
        return self._mock(identity, keywords)

    @staticmethod
    def _mock(identity: VehicleIdentity, keywords: list[str]) -> CardSummary:
        ref = find_vehicle(identity.brand, identity.model)
        facts = (ref or {}).get("facts", {})
        return CardSummary(
            one_liner=f"{identity.brand}의 {identity.body_type}, {identity.model}",
            facts=[KeywordFact(keyword=k, value=facts.get(k, "정보 없음")) for k in keywords],
        )
