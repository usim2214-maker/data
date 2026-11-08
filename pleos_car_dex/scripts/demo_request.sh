#!/usr/bin/env bash
# 백엔드 단독 시연: 이미지 한 장 업로드 -> 카드 JSON 확인
# 사용법: ./scripts/demo_request.sh path/to/hyundai-ioniq-5.jpg [키워드,쉼표구분]
set -euo pipefail
IMG=${1:?이미지 경로를 넣어주세요}
KW=${2:-}
API=${API:-http://localhost:8000}
curl -sS -X POST "$API/api/v1/recognize" \
  -F "image=@${IMG}" -F "lat=37.5665" -F "lon=126.9780" ${KW:+-F "keywords=${KW}"} | python3 -m json.tool
