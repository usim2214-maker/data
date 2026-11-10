# 신호 도우미 — 적록색맹 운전자를 위한 차량용 신호 알림 앱 (Pleos Connect / AAOS)

적록색맹 운전자는 신호등의 **적색과 녹색을 색만으로 구분하기 어렵습니다**.
이 앱은 전방 영상에서 신호 색을 인식하고, **차속·기어**를 함께 보고 상황에 맞춰
**소리 패턴 · 음성 · 모양 기호 · 헤드업 알림**으로 알려줍니다. 색에만 기대는 정보는 쓰지 않습니다.

> ⚠️ 보조 기능입니다. 안전장치가 아니며, 운전자는 언제나 직접 신호를 확인해야 합니다.

## 구조

```
colorblind_signal_assist/
├── core/   순수 Kotlin (Android 의존성 없음) — PC에서 빌드·테스트 가능
│   ├── TrafficLightDetector   프레임 → 켜진 등화 (HSV 색 분할 + 연결 요소 + 함체/위치 점수)
│   ├── SignalStateTracker     프레임 다수결로 깜빡임·오검출 억제
│   ├── AlertPolicy            신호 + 차속/기어 → 알릴지 판단
│   ├── SignalAssistEngine     위 세 단계를 묶은 파이프라인
│   ├── SyntheticSceneGenerator  카메라 대체용 합성 장면 (국내 가로형 4구 신호등)
│   └── Scenario / ScenarioPlayer  교차로 접근 시나리오 (차속 적분 → 거리 → 신호등 크기·위치)
├── demo/   PC 데모: 시나리오 실행 로그 + 검출 정확도 평가 + 합성 프레임 PNG 저장
└── app/    Android Automotive 앱 (Pleos Connect 대상)
    ├── vehicle/   CarPropertyVehicleDataSource (표준 Car API), PleosVehicleDataSource (Pleos SDK 자리), 시나리오
    ├── camera/    FrameSource: 합성 시나리오 / 가상 신호 주기 / assets 테스트 이미지
    ├── alert/     TonePlayer (신호별 고유 소리), AlertNotifier (TTS + 헤드업 알림)
    └── ui/        SignalPanelView (위치 + 모양 + 문구 + 색각이상 친화 팔레트)
```

## 카메라가 없는 문제 → 합성 영상으로 대체

Pleos Playground 공개 API에는 카메라가 없으므로 영상 입력을 `FrameSource` 인터페이스로 분리했습니다.

| 모드 (앱에서 선택) | 영상 | 차량 데이터 |
|---|---|---|
| 시뮬레이션 (3종) | 시나리오대로 합성 | 시나리오의 차속 (영상과 동기화) |
| 실차 데이터 + 가상 신호 주기 | 적 10s → 녹 8s → 황 3s 반복 합성 | Car API / Pleos SDK |
| 실차 데이터 + 테스트 이미지 | `app/src/main/assets/test_frames/*.png,jpg` 순환 | Car API / Pleos SDK |

나중에 카메라 접근이 열리거나 외장 카메라를 쓰게 되면, CameraX `ImageAnalysis` 결과를 `Frame`으로 바꾸는
`FrameSource` 구현만 추가하면 됩니다(`Bitmap.toFrame()` 유틸 있음). 나머지 코드는 바꿀 필요가 없습니다.

합성 장면에는 오검출을 시험하기 위한 방해 요소(앞차 브레이크등, LED 가로등, 가로수, 야간 창문 불빛)와
센서 노이즈, 노출 변화, 주행 중 흔들림이 들어 있습니다.

## 알림 설계

| 상황 | 알림 | 소리 패턴 |
|---|---|---|
| 정차 중 적/황 → 녹 | "초록불로 바뀌었습니다." | 도→솔 (올라감) |
| 녹색인데 3초 넘게 출발 안 함 | "초록불입니다. 주변을 확인하고 출발하세요." (1회) | 도→솔 |
| 주행 중 황색 | "황색 신호입니다. 정지를 준비하세요." | 한 번 길게 |
| 주행 중 적색 | "적색 신호입니다. 정지하세요." | 높음→낮음 3번 (내려감) |
| 적색인데 25km/h 이상, 감속 안 함 | "적색 신호! 즉시 감속하세요." (3초마다 반복) | 빠른 연속음 |
| 정차 중 좌회전 화살표 점등 | "좌회전 신호가 켜졌습니다." | 짧은 차임 |
| 정차 중 적색 / P·R 기어 | 알리지 않음 (화면 표시만) | — |

- 감속도(km/h/s)를 계산해, 운전자가 이미 제동 중이면 반복 경고를 멈춥니다.
- 같은 알림은 2.5초 안에 다시 나오지 않습니다.
- 화면: 실제 신호등과 같은 순서로 켜진 칸을 강조하고, **정지=팔각형, 주의=삼각형, 진행=원, 좌회전=화살표** 모양과
  "정지/주의/진행" 문구를 크게 표시합니다. 색은 Okabe-Ito 팔레트(주홍·노랑·청록)를 씁니다.

## 실행

```bash
# 1) 코어 단위 테스트 + 시나리오 E2E 테스트 (Android SDK 불필요)
./gradlew :core:test

# 2) PC 데모: 시나리오별 알림 로그, 무작위 합성 장면 600장 검출 정확도, 프레임 PNG 저장
./gradlew :demo:run
./gradlew :demo:run --args="app/src/main/assets/test_frames"   # 앱의 테스트 이미지 모드용으로 저장

# 3) 앱 빌드 (ANDROID_HOME 또는 local.properties 가 있으면 :app 모듈이 포함됨)
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

AAOS 에뮬레이터(또는 Pleos Playground 에뮬레이터)에서 **Extended controls → Car data**로 차속·기어를 바꾸면
"실차 데이터" 모드의 알림 정책을 직접 시험할 수 있습니다.

### 현재 결과 (합성 데이터 기준)

- 단위·E2E 테스트 18개 통과
- 무작위 합성 장면 600장: 정확도 99.7%, **다른 색으로 잘못 인식 0건**(놓친 2건은 '인식 안 됨'), 좌회전 화살표 76/77
- 시나리오 `stop_and_go`: 황색 주의 → 적색 정지 → 녹색 전환 0.2초 후 안내 → 3초 뒤 출발 리마인드
- 시나리오 `red_light_risk`: 적색 전환 0.2초 후 긴급 경고, 제동 전까지 3초 간격 반복

합성 데이터는 실제 도로보다 쉽습니다. 실제 영상에서는 정확도가 이보다 낮을 것으로 봐야 합니다.

## Pleos Playground 연동 메모

- Pleos Connect는 AAOS 기반이고 Vehicle SDK가 Android API 호환을 유지한다고 소개되어 있어,
  차량 데이터는 표준 `CarPropertyManager`(`PERF_VEHICLE_SPEED`, `GEAR_SELECTION`)로 먼저 구현했습니다.
- `app/.../vehicle/PleosVehicleDataSource.kt`는 Pleos Vehicle SDK를 연결할 자리입니다. 지금은 Car API에 위임합니다.
  SDK 문서를 받으면 이 파일만 고치면 되고, 브레이크 페달 신호가 있으면 `VehicleState.brakePedal`에 넣어 주세요.
- 클러스터/HUD 표시나 시트·스티어링 햅틱 API가 있으면 `alert/AlertNotifier.kt`에 출력 채널로 추가하면 좋습니다.

## 다음 단계

1. **실제 영상 검증**: 블랙박스 영상에서 프레임을 뽑아 `test_frames`에 넣고, 역광·비·LED 깜빡임(플리커) 조건 확인
2. **ML 검출기**: 색 기반 검출기를 경량 객체 검출 모델(TFLite 등)로 교체. `TrafficLightDetector.detect()` 입출력만 맞추면 됨
3. **내 차로의 신호 선택**: 교차로에 신호등이 여러 개일 때 차로·지도 정보로 해당 신호만 고르기
4. **사용자 연구**: 적록색맹 운전자 대상으로 알림 빈도와 소리 패턴이 실제로 구분되는지 검증
