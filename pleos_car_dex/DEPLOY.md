# CarDex 배포 · 수정 가이드

이 폴더 하나에 **백엔드 서버(FastAPI)** 와 **Pleos Connect 차량 앱(Android)** 이 모두 들어 있습니다.

```
pleos_car_dex/
├── backend/        서버 (Python · FastAPI)  — 인식·도감·스탬프·코스·랭킹 API
├── android/        차량 앱 (Kotlin · Jetpack Compose · Pleos SDK)
├── docs/           스크린샷 · 시연 영상 · 에셋 출처
├── scripts/        시연 영상 만들기 · 공식 이미지 주소 수집
├── docker-compose.yml
└── DEPLOY.md       (이 문서)
```

---

## 1. 백엔드 서버 배포

### A. 내 PC에서 바로 실행 (시연·개발)
```bash
cd backend
cp .env.example .env              # 설정 파일 (API 키는 여기에만 — 저장소에 올리지 않음)
python -m venv .venv && source .venv/bin/activate   # Windows: .venv\Scripts\activate
pip install -r requirements.txt
pip install -r requirements-vision.txt              # (선택) YOLOv8 실제 차량 감지
uvicorn app.main:app --host 0.0.0.0 --port 8000
```
→ 브라우저에서 `http://localhost:8000/docs` 로 API 확인

### B. Docker 로 실행 (서버·클라우드 배포)
```bash
cp backend/.env.example backend/.env
docker compose up -d --build      # http://서버주소:8000
```
- 실제 YOLO 감지를 쓰려면 `docker-compose.yml` 의 `VISION: "1"` (이미지가 약 1GB 로 커짐)
- 데이터는 `cardex-data` 볼륨에 저장 (재시작해도 유지)
- 클라우드(예: AWS Lightsail · GCP Cloud Run · Azure · 네이버 클라우드)에 이 Docker 이미지를 그대로 올리면 됩니다. Cloud Run 처럼 `PORT` 를 주는 곳도 자동으로 맞춰짐
- 공개 서버로 쓸 때는 **HTTPS**(로드밸런서·리버스 프록시) 뒤에 두고, `.env` 에서 `DEMO_MODE=false`

### 주요 설정 (`backend/.env`)
| 항목 | 설명 |
|---|---|
| `ANTHROPIC_API_KEY` | 차종 판별·코스 추천 LLM. 비우면 목업으로 동작 |
| `TOUR_API_KEY` | 한국관광공사 TourAPI (공공데이터포털). 비우면 시연용 명소 |
| `DETECTOR_MODE` | `yolo`(실제 감지) / `mock` |
| `DEMO_MODE` | 앱의 '시연 데이터 채우기/초기화' 허용 — **배포 시 false** |
| `SAVE_CROPS` | 인식한 차 사진 저장 (기본 false, 개인정보) |

> 지금 서버는 사용자 1명 기준(파일 저장)입니다. 여러 사용자 서비스로 가려면 계정·DB(예: PostgreSQL)로 `services/store.py` 를 교체해야 합니다.

---

## 2. 차량 앱 빌드 · 설치

### 준비
- Android Studio (JDK 17) + **Pleos Connect Emulator** (Pleos 문서 › 개발 환경 구성)
- `android/local.properties` 에 (저장소에 올리지 않음):
  ```
  sdk.dir=/Android/Sdk 경로
  pleos.appId=Pleos Playground 에서 발급받은 앱 ID
  pleos.appKey=앱 키
  ```

### 빌드 · 설치
```bash
cd android
./gradlew installDebug                                   # 연결된 에뮬레이터에 설치
./gradlew assembleDebug -PapiBaseUrl=https://내서버주소/   # 배포 서버를 바라보는 APK
```
- 기본 서버 주소는 `http://10.0.2.2:8000/` (에뮬레이터 → 같은 PC 의 백엔드)
- 결과 APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- 동봉된 `CarDex-debug.apk` 는 이 기본 주소로 빌드한 시연용 (에뮬레이터 + PC 백엔드)

### Pleos 앱마켓 제출 (정식 배포)
1. **서명 키 만들기** (한 번, 잘 보관): `keytool -genkeypair -v -keystore cardex.jks -alias cardex -keyalg RSA -keysize 2048 -validity 10000`
2. `android/app/build.gradle.kts` 에 `signingConfigs` 추가 후 `./gradlew assembleRelease -PapiBaseUrl=https://내서버주소/`
3. Pleos App Market Console 에서 앱 등록 → 권한 사용 승인(위치·주행 상태·내비 경로/검색/화면·TTS·STT·LLM) → 심사 제출
   - 권한은 앱마켓과 협의·승인된 범위에서만 사용 가능 (Pleos 문서 › 앱 등록 가이드, App Review Guidelines)

---

## 3. 수정할 때 어디를 고치나

| 하고 싶은 것 | 파일 |
|---|---|
| 도감 차량 28종 · 정보(가격·마력 등) | `backend/app/data/vehicle_catalog.json` |
| 카드 뒷면에 고를 수 있는 항목 | 같은 파일의 `fields` |
| 점수 규칙 (시너지·보너스) | `backend/app/services/synergy.py` |
| 이번 주의 차 · 차 쉬는 날 | `backend/app/services/daily.py` |
| 브랜드 체험 스팟 | `backend/app/data/brand_spots.json` |
| 쇼룸 아이템 · 해금 조건 | `backend/app/data/decor_items.json` |
| 지역(시·도) · 여행 점수 | `backend/app/data/regions.json` |
| 공식 차량 이미지 주소 · 색상 | `android/app/src/main/assets/vehicle_images.json` (다시 모으기: `scripts/official_images/`) |
| 메인 화면 (쇼룸 + 검색 + 추천) | `android/.../ui/screens/HomeScreen.kt` |
| 시작 화면 그림 | `android/.../ui/screens/SplashScreen.kt` |
| ☰ 메뉴 항목 | `android/.../MainActivity.kt` (`Dest`, `AppDrawer`) |
| 카드 디자인 | `android/.../ui/components/FlipCard.kt` |
| 실제 차량 보기 · 360° 회전 | `android/.../ui/components/OfficialCarView.kt` |
| 쇼룸 무대 그림 | `android/.../ui/components/ShowroomStage.kt` |
| Pleos 내비 연동 | `android/.../pleos/PleosNavi.kt` |
| 시연 영상 시나리오 | `android/app/src/test/.../DemoFlowTest.kt` → `./scripts/make_demo_video.sh` |

### 고친 뒤 확인
```bash
cd backend && python -m pytest -q                          # 서버 테스트
cd android && ./gradlew testDebugUnitTest lintDebug         # 앱 테스트 + 화면 스크린샷 (app/build/screenshots)
```

---

## 4. 꼭 알아둘 점
- **차량 이미지 저작권**: 현대·기아·제네시스 공식 이미지는 앱에 넣지 않고 주소만 두어 실행 중 내려받습니다. **비상업 프로토타입 용도**이며, 상업화·공개 배포 전에는 제조사 허락을 받거나 교체하세요 (`docs/ASSET_CREDITS.md`).
- **에셋 출처 표기**: Twemoji(CC BY 4.0) 표기 필요 — `docs/ASSET_CREDITS.md`
- **키·비밀번호**: `backend/.env`, `android/local.properties`, 서명 키(`*.jks`)는 저장소·공유 파일에 넣지 마세요.
- **미확인 사항**: 실차·Pleos 에뮬레이터에서의 내비 연동, 주행거리(Odometer) 단위, 차량 28종 수치, 브랜드 스팟 좌표는 출시 전 확인이 필요합니다.
