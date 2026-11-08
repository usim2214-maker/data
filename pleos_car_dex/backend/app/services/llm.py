"""Claude 클라이언트 공통 설정."""
from functools import lru_cache

import anthropic

from app.config import get_settings

# 안전 분류기가 요청을 거절하면 서버 측에서 권장 모델로 자동 재시도
FALLBACK_BETA = "server-side-fallback-2026-07-01"


@lru_cache
def get_client() -> anthropic.Anthropic:
    return anthropic.Anthropic(api_key=get_settings().anthropic_api_key, timeout=30.0, max_retries=2)


def parse(*, output_format, system: str, content, max_tokens: int = 2048):
    """구조화 출력(Pydantic) 호출. refusal 이면 None 반환."""
    s = get_settings()
    response = get_client().beta.messages.parse(
        model=s.llm_model,
        max_tokens=max_tokens,
        system=system,
        messages=[{"role": "user", "content": content}],
        output_config={"effort": s.llm_effort},
        output_format=output_format,
        betas=[FALLBACK_BETA],
        fallbacks="default",
    )
    if response.stop_reason == "refusal":
        return None
    return response.parsed_output
