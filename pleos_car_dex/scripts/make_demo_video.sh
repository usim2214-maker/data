#!/usr/bin/env bash
# 시연 영상 만들기: 앱 화면을 샘플 데이터로 렌더링(버튼 실제 클릭)한 프레임 -> MP4
# 필요: JDK 17, Android SDK(local.properties), ffmpeg
# 사용: ./scripts/make_demo_video.sh  ->  docs/demo/cardex_demo.mp4
set -euo pipefail
cd "$(dirname "$0")/../android"
./gradlew testDebugUnitTest --tests '*DemoVideoFramesTest*' -q
cd app/build/demo_video
mkdir -p ../../../../docs/demo
ffmpeg -y -loglevel error -f concat -safe 0 -i concat.txt \
  -vf "fps=30,format=yuv420p" -c:v libx264 -preset slow -crf 20 -movflags +faststart \
  ../../../../docs/demo/cardex_demo.mp4
echo "완성: docs/demo/cardex_demo.mp4"
