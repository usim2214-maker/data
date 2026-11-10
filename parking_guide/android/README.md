# 후방 주차 가이드: Pleos 앱 (Android Automotive)

Python 프로토타입(`../parking_guide`)의 인식·추적·안내·리포트 로직을 Kotlin으로 옮기고,
Pleos Playground SDK와 Android Automotive Car API에 연결한 앱 뼈대다.

```
android/
  core/   순수 Kotlin 모듈 (Android 없이 JVM에서 테스트)
    CameraModel.kt     핀홀/광각(어안) 카메라, 조감도 설정
    SlotDetector.kt    OpenCV 주차선·끝선 검출, 주차칸 선택, 신뢰도
    Geometry.kt        주차선/주차칸 모델, 이동 예측(moved)
    Alignment.kt       좌우 여유·치우침·각도, 하차(문콕 방지) 모드, 신뢰도 점수
    Guidance.kt        예상 경로, 고스트카, 목표 핸들 각도, 안내 문구, 깜빡임 방지
    Tracker.kt         주차칸 추적 (튀는 검출 무시, 가려져도 이어 감)
    Voice.kt           음성 안내 타이밍, 거리 비프음 간격
    Report.kt          주차 리포트, 점수·조언, 성장 추이, 지자체 공유 JSON
    ParkingAssistant.kt  한 프레임 처리 흐름
  app/    Android Automotive 앱
    VehicleSignals.kt  Pleos SteeringWheel + AAOS 기어·차속
    VoiceOutput.kt     Pleos TextToSpeech(ON_DEVICE) + 비프음
    LocationSource.kt  Pleos FusedLocation
    FrameSource.kt     영상 입력 (에뮬레이터용 프레임 재생 / Camera2)
    GuideView.kt       안내 화면 (배지, 핸들 다이얼, 균형 게이지, 미니맵, 가상 벽 수직선, 고스트카)
    ReportView.kt      주차 리포트 카드
    MainActivity.kt    R단 진입 시 안내 시작, R단 해제 시 리포트
```

## Pleos / AAOS 연동 (SDK 소스로 확인한 사실)

API 이름과 시그니처는 문서 대신 Pleos Maven 저장소의 SDK(Vehicle 2.3.2 소스, FusedLocation 1.1.3 소스, TextToSpeech 3.3.1 바이너리)를 직접 열어 확인했다.

| 용도 | 사용한 API | 비고 |
|---|---|---|
| 조향각 | `Vehicle.getSteeringWheel().registerSteeringWheelAngle(SteeringWheelAngleListener)` | **앞바퀴 조향각**(도, 왼쪽 −)이다. 핸들 회전각이 아니므로 `VehicleSpec.steeringWheelFromFrontWheel`(× 조향비)로 바꿔 쓴다. 권한 `pleos.car.permission.CAR_DRIVE` |
| 후진(R단) | AAOS `VehiclePropertyIds.GEAR_SELECTION` | Pleos `Gear` API는 `@hide`·deprecated이고 "Google GEAR_SELECTION property를 쓰라"고 안내한다. 권한 `android.car.permission.CAR_POWERTRAIN` |
| 이동 거리 | AAOS `PERF_VEHICLE_SPEED`(m/s) × 시간 | Pleos `Odometer`는 km 단위라 cm 추적엔 거칠다. 권한 `CAR_SPEED`(실행 중 요청) |
| 음성 | `TextToSpeech(context, Mode.ON_DEVICE)` → `initialize(OnInitializeListener)` → `speak(text, true)` | ON_DEVICE는 앱 등록 불필요. 권한 `pleos.car.permission.TTS_SERVICE`. 이 권한은 문서에서 찾지 못했고 Android Lint가 SDK 어노테이션으로 찾아냈다 |
| 위치 | `FusedLocation(context)` → `isSupported()` → `initialize()` → `getLastKnownLocation(FusedLocationListener)` | 권한 `pleos.car.permission.FUSED_LOCATION`. 지자체 공유 시 약 100m 단위로 반올림 |
| 후방카메라 영상 | **없음** | 2026-11 기준 공개 SDK에 카메라 API가 없다. 지금은 `FrameSource`로 대신하고, Pleos 측 허용이 나면 구현을 하나 추가한다 |

## 빌드와 테스트

```bash
cd android
./gradlew :core:test            # JVM 테스트 20개 (Android SDK 불필요)
./gradlew :app:assembleDebug    # APK (Android SDK 필요: local.properties 에 sdk.dir 지정)
./gradlew :app:lintDebug
```

- **core 테스트**:
  - 안내 문구, 바퀴 수 표현, 하차 모드, 음성 타이밍을 확인한다.
  - 폐루프 주차 시뮬레이션을 돌린다(안내를 따르면 치우침 10cm, 각도 3° 이내).
  - 이동 예측 기하, 추적기, 리포트·지자체 JSON을 확인한다.
- **인식 교차 검증**(`DetectorCrossCheckTest`):
  - Python 합성기로 만든 후방카메라 영상 8장면(광각·일반 렌즈, 닳은 선, 깊이 들어간 상황)을 쓴다.
  - Kotlin 인식기 결과가 Python 인식기와 치우침 0.1cm, 각도 0.02° 이내로 같다.
- **APK**: 디버그 빌드는 약 105MB다. 대부분 OpenCV 네이티브 라이브러리(arm64 + x86_64)라서, 출시 전에는 ABI별로 나누거나 필요한 모듈만 넣은 OpenCV로 줄여야 한다.

## 에뮬레이터에서 시연하기

카메라 API가 없으므로 합성 후방카메라 프레임을 앱에 넣어 재생한다.

```bash
cd parking_guide
python tools/export_demo_frames.py      # → android/app/src/main/assets/demo/*.png (150장)
cd android && ./gradlew :app:installDebug
```

Pleos Connect 에뮬레이터의 Virtual Console에서 조향각을 바꾸면 핸들 안내, 다이얼, 고스트카가 반응한다.
기어 신호가 없으면 앱은 항상 후진 중으로 보고 동작한다. 기어를 R에서 다른 단으로 바꾸면 리포트 카드가 뜬다.

## 아직 확인하지 못한 것

- **실제 에뮬레이터·차량에서 실행해 보지 않았다.** 이 환경에서는 빌드, Lint, JVM 테스트까지만 확인했다. 특히 다음 동작은 Pleos Connect 에뮬레이터에서 확인해야 한다.
  - `initialize()` 직후 SteeringWheel 등록이 바로 되는지
  - TTS 초기화가 성공하는지
  - 후진 중 화면이 CarUxRestrictions에 막히지 않는지
- 차종 선택, 하차 모드 선택, 지자체 공유 동의 화면은 아직 없다. 지금은 쏘나타, 가운데 모드로 고정이고, 공유 데이터는 기기 로그에만 남긴다.
- 조향비(기본 14)는 차종마다 다르다. 다이얼과 "반 바퀴" 표현의 정확도는 이 값에 달려 있다.
