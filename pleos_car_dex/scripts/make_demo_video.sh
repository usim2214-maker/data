#!/usr/bin/env bash
# 시연 영상 만들기: 실제 앱 화면을 애니메이션 시계에 맞춰 1초 24프레임으로 그리고 ffmpeg 로 바로 인코딩 (DemoFlowTest)
# 커서가 실제 버튼 위치로 움직여 터치·드래그 입력을 넣고, 화면 전환·턴테이블·카드 뒤집기 등 애니메이션이 그대로 흐른다.
# 필요: JDK 17, Android SDK(local.properties), ffmpeg. 공식 차량 이미지를 받으려면 인터넷 (없으면 3D 대체 화면)
# 사용: ./scripts/make_demo_video.sh  ->  docs/demo/cardex_demo.mp4
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/android"
./gradlew testDebugUnitTest --tests '*RoadSceneRenderTest*' -q   # 퀴즈 장면용 도로 그림
./gradlew testDebugUnitTest --tests '*DemoFlowTest*' -q --rerun-tasks
mkdir -p "$ROOT/docs/demo"
cp app/build/demo_flow/cardex_demo.mp4 "$ROOT/docs/demo/cardex_demo.mp4"
echo "완성: docs/demo/cardex_demo.mp4"
