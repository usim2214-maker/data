#!/usr/bin/env bash
# 시연 영상 만들기: 앱 화면을 샘플 데이터로 렌더링(버튼 실제 클릭)한 프레임 -> MP4
#  1) 차량 인식 장면용 도로 그림 렌더링 2) 백엔드 YOLOv8 감지 코드로 실제 분석 3) 전체 프레임 렌더링 4) ffmpeg 합성
# 필요: JDK 17, Android SDK(local.properties), ffmpeg, backend 의존성 + requirements-vision (없으면 인식 장면 생략)
# 사용: ./scripts/make_demo_video.sh  ->  docs/demo/cardex_demo.mp4
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PY="${PYTHON:-python3}"
cd "$ROOT/android"
./gradlew testDebugUnitTest --tests '*RoadSceneRenderTest*' -q
if ! "$PY" "$ROOT/scripts/detect_demo_scene.py" app/build/demo_video/road_scene.png app/build/demo_video/road_scene.json; then
  echo "YOLO 감지를 건너뜀 (차량 인식 장면 없이 진행)"; rm -f app/build/demo_video/road_scene.json
fi
./gradlew testDebugUnitTest --tests '*DemoVideoFramesTest*' -q --rerun-tasks
cd app/build/demo_video
mkdir -p "$ROOT/docs/demo"
ffmpeg -y -loglevel error -f concat -safe 0 -i concat.txt \
  -vf "fps=30,format=yuv420p" -c:v libx264 -preset slow -crf 20 -movflags +faststart \
  "$ROOT/docs/demo/cardex_demo.mp4"
echo "완성: docs/demo/cardex_demo.mp4"
