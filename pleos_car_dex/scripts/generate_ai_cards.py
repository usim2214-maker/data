"""시연 영상용 '실제' AI 카드 요약 생성 (Claude API 호출).

백엔드와 똑같은 프롬프트/구조화 출력(app.services.summarizer)으로, 사용자 키워드를 넣어 카드 뒷면 요약을 만든다.
결과는 docs/demo/ai_cards.json 에 저장되고, scripts/make_demo_video.sh 가 영상에 사용한다.

사용 (PC 에서):
  cd backend && pip install -r requirements.txt
  # backend/.env 에 ANTHROPIC_API_KEY=... (또는 환경변수)
  python ../scripts/generate_ai_cards.py            # 기본 키워드: 가격,브랜드,마력,연비
  python ../scripts/generate_ai_cards.py 가격 마력 제로백
"""
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
os.chdir(ROOT / "backend")  # backend/.env 를 읽도록
sys.path.insert(0, str(ROOT / "backend"))

from app.config import get_settings  # noqa: E402
from app.schemas import CardSummary, VehicleIdentity  # noqa: E402
from app.services import llm  # noqa: E402
from app.services.catalog import load_catalog  # noqa: E402
from app.services.summarizer import SYSTEM_PROMPT, build_user_prompt  # noqa: E402

DEMO_CARS = ["kia-ev9", "hyundai-ioniq-5", "hyundai-ioniq-5-n", "genesis-g80", "hyundai-avante-n", "genesis-g90"]

settings = get_settings()
if not settings.anthropic_api_key:
    sys.exit("ANTHROPIC_API_KEY 가 없어요. backend/.env 또는 환경변수에 넣어 주세요.")

keywords = sys.argv[1:] or ["가격", "브랜드", "마력", "연비"]
by_id = {v["id"]: v for v in load_catalog()}
cards = {}
for cid in DEMO_CARS:
    v = by_id[cid]
    ident = VehicleIdentity(is_vehicle=True, brand=v["brand"], model=v["model"], body_type=v["body_type"], confidence=1.0)
    # summarizer 와 동일한 프롬프트로 직접 호출 (실패 시 mock 으로 넘어가지 않고 에러를 그대로 보여줌)
    result = llm.parse(output_format=CardSummary, system=SYSTEM_PROMPT, content=build_user_prompt(ident, keywords, "ko"))
    if result is None:
        sys.exit(f"{cid}: 모델이 요청을 거절했어요 (stop_reason=refusal)")
    cards[cid] = result.model_dump()
    print(f"✓ {v['brand']} {v['model']}: {result.one_liner}")
    for f in result.facts:
        print(f"    {f.keyword}: {f.value}")

out = ROOT / "docs" / "demo" / "ai_cards.json"
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps({
    "generated_at": datetime.now(timezone.utc).isoformat(),
    "model": settings.llm_model,
    "keywords": keywords,
    "cards": cards,
}, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"\n저장: {out.relative_to(ROOT)}  →  ./scripts/make_demo_video.sh 로 영상에 반영")
