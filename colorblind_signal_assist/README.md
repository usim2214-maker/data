# 모두의 운전석 — 운전자 특성에 맞춰 차량 정보를 '다른 감각'으로 전달하는 접근성 운전 보조 앱

> **한 줄 소개**: 색(신호등), 소리(경고음·방향지시등·내비 음성), 작은 경고등처럼 **한 가지 감각에만 의존하는 차량 정보**를
> 색각이상·청각장애·저시력·주의력 저하(고령) 운전자가 받아들일 수 있는 **소리 패턴·음성·화면 깜빡임·큰 글씨**로 바꿔 전달하는 Pleos Connect 앱

⚠️ 보조 기능입니다. 안전장치가 아니며, 운전자는 언제나 직접 신호와 주변을 확인해야 합니다.

---

## 실행 방법

### APK 설치 (바로 실행)
```bash
adb install apk/팀명_모두의운전석_release.apk
```
앱 목록에서 **모두의 운전석**을 실행합니다. 처음 실행 시 차속·알림 권한을 허용합니다.

### 소스 빌드
```bash
./gradlew :core:test           # 단위·시나리오 테스트 (34개, Android SDK 불필요)
./gradlew :demo:run            # PC 데모: 시나리오별 알림 로그 + 신호 검출 정확도 + 합성 프레임 PNG
./gradlew :app:assembleRelease # APK (ANDROID_HOME 또는 local.properties 필요)
```

### 시연 순서 (에뮬레이터)
1. 화면 아래 **모드**에서 `시뮬레이션: …` 시나리오 5종 중 하나를 고릅니다. 시나리오에 맞는 운전자 특성이 자동으로 선택됩니다.
2. **운전자 특성** 체크박스(색각이상·청각장애·저시력·주의력·고령)를 바꿔 같은 상황에서 알림 방식이 달라지는 것을 확인합니다.
3. `실차: 차량 경고·내비 (카메라 없이)` 모드에서는 Pleos 에뮬레이터 Virtual Console로 도어·방향지시등·좌석·조명을 바꿔 봅니다.
   에뮬레이터는 차속을 시뮬레이션하지 못하므로, 차속이 들어오지 않으면 **시연용 차속** 막대가 나타납니다.

## 테스트 환경 · 필요 설정

| 항목 | 내용 |
|---|---|
| 대상 | Pleos Connect (AAOS 기반), Android Automotive 에뮬레이터 API 34 |
| 빌드 | minSdk 28 / targetSdk 34, Gradle 8.14, AGP 8.7, Kotlin 2.0, JDK 17+ |
| 권한 | `android.car.permission.CAR_SPEED`(런타임), `CAR_POWERTRAIN`, `pleos.car.permission.CAR_SAFETY·SEATS·DOORS·LIGHTS·DRIVE`, 알림 |
| Pleos 에뮬레이터 | Virtual Console: `adb forward tcp:8088 tcp:8088` 후 `http://127.0.0.1:8088/` |
| 카메라 | 불필요 (Pleos 공개 API에 카메라 없음 → 합성 영상으로 대체, 아래 참고) |

## APK 위치
`apk/팀명_모두의운전석_release.apk`

---

## 무엇을 해결하나

| 운전자 | 차량이 주는 정보의 문제 | 이 앱이 바꿔 주는 방식 |
|---|---|---|
| **색각이상** | 신호등 적·녹·황을 색으로만 구분 | 신호 변화를 **음높이 패턴 + 음성 + 모양 기호(■▲●←)** 로. 좌회전 화살표가 **내 경로**에 해당하는지도 내비와 맞춰 알려줌 |
| **청각장애** | 벨트 경고음, 방향지시등 똑딱 소리, 내비 음성 안내 | **화면 전체 깜빡임 + 큰 글씨 + 헤드업 알림**. "300m 앞 좌회전" 카드, 방향지시등 끄는 것을 잊으면 알림 |
| **저시력** | 계기판 경고등·아이콘이 작음 | 타이어 저압·전조등 미점등·벨트 등을 **음성**으로 읽어주고 글씨 1.4배 |
| **주의력·고령** | 깜빡한 방향지시등, 초록불 늦은 출발, 뒷좌석 아이 방치 | **한 번 더 상기**. 위험 상황(적색 고속 접근)만 강하게, 나머지는 조용히 |

### 알림 규칙 (요약)

| 상황 | 알림 | 소리 패턴 |
|---|---|---|
| 정차 중 적/황 → 녹 | "초록불로 바뀌었습니다." | 도→솔 (올라감) |
| 녹색인데 3초 넘게 출발 안 함 | "초록불입니다. 주변을 확인하고 출발하세요." (1회) | 도→솔 |
| 주행 중 황색 / 적색 | "황색 신호입니다…" / "적색 신호입니다. 정지하세요." | 한 번 길게 / 높음→낮음 3번 |
| 적색인데 25km/h 이상, 감속 안 함 | "적색 신호! 즉시 감속하세요." (3초마다, 제동하면 멈춤) | 빠른 연속음 |
| 좌회전 화살표 점등 | 내비 경로가 좌회전이면 "안내 경로도 좌회전입니다", 직진이면 "직진은 기다리세요" | 차임 |
| 주행 중 벨트 미착용 / 문 열림 | "안전벨트를 매 주세요." / "문이 열려 있습니다!" | 같은 음 두 번 / 연속음 |
| 방향지시등 20초 이상 켜짐 (30km/h↑, 안내 지점 근처 아님) | "방향지시등이 계속 켜져 있습니다." | 같은 음 두 번 |
| 어두운데 전조등 꺼짐 / 타이어 저압 | 1회 안내 | 같은 음 두 번 |
| 주차(P) 또는 문 열림 + 이번 주행에 뒷좌석 탑승 | "내리기 전에 뒷좌석을 확인하세요." | 한 번 길게 |
| 내비 안내 지점 300m·100m 전 | "300m 앞 좌회전 (강남대로)" 큰 카드 | 차임 |
| 정차 중 적색 / P·R 기어 | 알리지 않음 (화면 표시만) | — |

**프로필별 출력**: 청각장애만 고르면 소리·음성 없이 화면 깜빡임 + 헤드업 알림, 저시력·주의력은 큰 글씨, 경고(WARNING) 이상은 모두에게 화면 깜빡임.

## 구조

```
core/   순수 Kotlin — PC에서 빌드·테스트
  TrafficLightDetector      프레임 → 켜진 등화 (HSV 색 분할 + 연결 요소 + 함체/위치 점수)
  SignalStateTracker        프레임 다수결로 깜빡임·오검출 억제
  SignalAlertPolicy         신호 + 차속 + 내비 경로 → 신호 알림
  VehicleAlertPolicy        실내 상태(벨트·문·타이어·방향지시등·전조등·뒷좌석) → 차량 알림
  NavigationAlertPolicy     내비 안내 지점 → 300m/100m 카드
  DriverProfile             운전자 특성 → 켤 기능 + 감각별 출력(OutputPlan)
  DrivingAssistEngine       위를 합친 엔진
  SyntheticSceneGenerator   카메라 대체용 합성 장면 (국내 가로형 4구 신호등 + 방해 요소)
  Scenario / ScenarioPlayer 시나리오 5종 (차속·실내·경로·신호를 시간축으로 재생)
demo/   PC 데모
app/    Pleos Connect 앱
  AssistService             포그라운드 서비스 — 화면과 무관하게 알림 (주행 중 UX 제약 대응)
  MainActivity              설정(주차 중) + 큰 상태 표시(주행 중, CarUxRestrictions 준수)
  vehicle/                  차속·기어(AAOS 표준), 실내 상태(Pleos Vehicle State ↔ AAOS 대체), 시나리오
  nav/                      Pleos NaviHelper 어댑터
  camera/                   영상: 합성 시나리오 / 가상 신호 주기 / assets 테스트 이미지
  alert/                    TonePlayer(패턴별 합성음), AlertNotifier(TTS + 헤드업 알림)
tools/make_submission.sh    블라인드 심사용 익명 제출 저장소 생성
docs/기획서_초안.md          공모전 기획서 초안
```

## Pleos SDK 사용

| 기능 | Pleos API | 코드 위치 | 상태 |
|---|---|---|---|
| 벨트·문·타이어·방향지시등·전조등·뒷좌석 | Vehicle State: `getCurrentSeatBeltBuckledState`, `hasSeatBeltNotBuckledWarning`, `getCurrentDoorPosition`, `getLowTirePressure`, `getCurrentTurnSignalState`, `getCurrentLowBeamLightState`, `getCurrentSeatOccupancy` | `vehicle/PleosCabinDataSource.kt` | 매핑·규칙 구현 완료, SDK 호출 연결 예정 (지금은 AAOS 표준 프로퍼티로 대체) |
| 브레이크 반응 판단 | Vehicle State: `getCurrentBrakePedalStatus` | `VehicleState.brakePedal` | 연결 예정 |
| 내비 경로 / 안내 지점 | NaviHelper: `getRouteStateInfo`, `getTBTInfo`, `getCurrentLocationInfo` | `nav/NaviHelperNavigationSource.kt` | 규칙 구현 완료, SDK 호출 연결 예정 |
| 차속·기어 | (Vehicle State에 없음) AAOS `PERF_VEHICLE_SPEED`, `GEAR_SELECTION` | `vehicle/CarPropertyVehicleDataSource.kt` | 구현 완료. 기어를 못 읽으면 차속만으로 판단 |
| 음성 | (Pleos TTS SDK 준비 중) Android `TextToSpeech` | `alert/AlertNotifier.kt` | 구현 완료 |
| 주행 중 화면 제약 | AAOS `CarUxRestrictionsManager` | `MainActivity.kt` | 구현 완료 |

## 카메라가 없는 문제

Pleos 공개 API에는 카메라가 없어서, 신호 인식은 영상 입력을 `FrameSource` 인터페이스로 분리하고 **합성 영상**으로 검증했습니다.
카메라 접근이 열리면 CameraX 결과를 `Frame`으로 바꾸는 구현만 추가하면 되고(`Bitmap.toFrame()` 있음), 나머지는 그대로입니다.
차량 경고·내비 카드 기능(청각·저시력·주의력)은 카메라 없이 **지금 SDK만으로 동작**합니다.

합성 데이터 결과: 무작위 장면 600장에서 정확도 99.7%, **다른 색으로 오인 0건**, 좌회전 화살표 76/77.
실제 도로 영상은 합성보다 어려우므로 이 수치는 상한으로 봐야 합니다.

## 오픈소스 라이선스

| 라이브러리 | 라이선스 |
|---|---|
| AndroidX (core-ktx, appcompat, lifecycle) | Apache License 2.0 |
| Kotlin 표준 라이브러리, kotlinx.coroutines | Apache License 2.0 |
| Android Car API (`android.car`, 플랫폼 제공) | Apache License 2.0 |

색상 팔레트: Okabe & Ito, *Color Universal Design* (색각이상 친화 팔레트).
