# Pleos CarDex — 주행 중 차량 인식 & 도감 (MVP)

현대차그룹 **Pleos Connect** 인포테인먼트 앱(Pleos Playground SDK / 에뮬레이터)입니다.
주행 영상에서 **YOLOv8**로 차량을 인식하고, **LLM API**가 유저 키워드(차종·마력 등)로 요약한
정보를 **앞/뒷면 카드**로 보여준 뒤 **도감**에 모읍니다. 모은 차량으로 **시너지 점수**를 쌓고,
카트라이더처럼 도감 차량을 **소환**할 수 있습니다.

## 기획 메모 → 구현 대응

| 기획 | 구현 |
|---|---|
| 차량 인식: YOLOv8 (가볍고 빠름) | `backend/app/services/detector.py` — `yolov8n.pt`, COCO 차량 클래스, 영상은 트래킹으로 같은 차 중복 제거 |
| 인식 방법: 서라운드뷰 등 차량 내부 API 연계 예정 → 지금은 **영상 직접 입력** | 앱: `VideoFrameSource` 가 재생 중인 주행 영상의 프레임을 주기적으로 전송 / 서버: `POST /api/v1/recognize/video` 로 영상 통째 분석도 가능. 서라운드뷰 API 공개 시 `FrameSource` 만 교체 |
| 차종·마력 등 정보 조회 → LLM API 키 활용 | `identifier.py`(차종 식별) + `summarizer.py`(유저 키워드 동적 주입 요약), Claude API |
| 카드: 앞면 차량 이미지 / 뒷면 정보 요약 | `FlipCard.kt` — 탭하면 뒤집힘 |
| 주행 중 마주친 차량 도감 등록 + 시너지 점수 | `store.py`(최초 발견 위치 = Pleos FusedLocation) + `synergy.py` |
| 카트라이더처럼 차량 소환 | `routers/garage.py` + `SummonedCar.kt` (도감 차량을 대시보드에 소환, 같은 브랜드 카드에 '소환 공명' 보너스) |
| 지나간 지역 스탬프 | 아직 미구현 (메모에서 삭제선인지 확인 필요) |

### 시너지 점수 규칙 (MVP 정의, `synergy.py` 상수로 튜닝)
- 기본: 희귀도 합 (common 10 / rare 30 / legendary 100)
- 재회: 같은 차량을 다시 마주칠 때 +2 (차량당 최대 10)
- 브랜드 컬렉터: 같은 브랜드 3종 이상 +50
- 그룹 트리오: 현대·기아·제네시스 모두 수집 +100
- 다양성: 차체 유형 3가지 이상 +30
- 소환 공명: 소환한 차량과 같은 브랜드 카드 1장당 +5

## Pleos Playground 에서 쓰는 것

| 항목 | 내용 |
|---|---|
| SDK 저장소 | `https://nexus-playground.pleos.ai/repository/maven-releases/` (공식 샘플과 동일) |
| FusedLocation SDK | `ai.pleos.playground:FusedLocation:1.1.3`, 권한 `pleos.car.permission.FUSED_LOCATION` — 도감의 '발견 장소' |
| Vehicle SDK (예정) | `ai.pleos.playground:Vehicle` — 차속/기어 연동 시 추가 (`TODO(Pleos)`) |
| 서라운드뷰/카메라 | 공개 API 미확인 → 영상 직접 입력으로 대체 |

> **API 키 관련 메모**: 공식 FusedLocation 샘플 기준, 차량 내 SDK(FusedLocation/Vehicle)는 별도 API 키 없이
> Maven 의존성 + 권한으로 동작합니다. Playground 에서 발급하는 *Vehicle Data API* 키는 내 차량의 커넥티드
> 데이터를 클라우드 REST 로 받는 용도(개인 발급은 현재 유럽 한정)라 주변 차량 인식과는 무관합니다.
> 차량 정보 요약에 필요한 키는 **LLM API 키**(`ANTHROPIC_API_KEY`)입니다.

## 구조

```
pleos_car_dex/
├── backend/                         FastAPI
│   ├── app/
│   │   ├── routers/                 recognize(이미지·영상) / collection(+시너지) / garage(소환) / preferences
│   │   ├── services/
│   │   │   ├── detector.py          YOLOv8 감지·크롭·영상 트래킹
│   │   │   ├── identifier.py        차종 식별 (Claude 비전 / mock)
│   │   │   ├── summarizer.py        키워드 기반 요약 (Claude / mock)
│   │   │   ├── synergy.py           시너지 점수
│   │   │   ├── pipeline.py          전체 흐름 (+ 방금 본 차량은 LLM 재호출 생략)
│   │   │   ├── store.py             도감·설정·소환 JSON 저장소
│   │   │   ├── catalog.py / llm.py
│   │   └── data/vehicle_catalog.json   시연 차종 6종
│   └── tests/test_api.py            mock 모드 E2E (이미지·영상·시너지·소환)
├── android/                         Pleos Connect 앱 (Kotlin/Compose)
│   └── app/src/main/
│       ├── assets/demo_video/       ← 시연 주행 영상 demo_drive.mp4
│       ├── assets/demo_frames/      ← (대체) 시연 이미지
│       └── java/com/pleos/cardex/
│           ├── pleos/VehicleContext.kt  Pleos FusedLocation 연동 (+목업 대체)
│           ├── pleos/FrameSource.kt     영상 프레임 입력 (서라운드뷰 연계 지점)
│           ├── data/                    Retrofit API
│           └── ui/                      스캔(영상+카드+소환) / 도감(시너지·소환) / 키워드
└── scripts/demo_request.sh
```

## 빠른 시작

### 백엔드
```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements-vision.txt     # YOLOv8 포함 (mock 만 쓸 거면 requirements.txt)
cp .env.example .env                       # ANTHROPIC_API_KEY 입력 (비우면 mock 요약)
uvicorn app.main:app --host 0.0.0.0 --port 8000
# 영상 통째 테스트
curl -F "video=@drive.mp4" http://localhost:8000/api/v1/recognize/video
```
완전 오프라인 시연: `DETECTOR_MODE=mock IDENTIFIER_MODE=mock USE_MOCK_LLM=true` (파일명으로 차종 결정).
테스트: `python -m pytest -q`

### Pleos 앱
1. Android Studio 로 `android/` 열기 → Pleos Connect 에뮬레이터 실행
2. `assets/demo_video/demo_drive.mp4` 에 주행 영상 넣기
3. 실행 → FusedLocation 권한 허용 (지원 안 되는 환경이면 자동으로 목업 위치)
4. 스캔 탭: 영상 재생 중 `자동 스캔` → 차량 카드 등장, 탭하면 뒤집혀 키워드 요약
5. 도감 탭: 시너지 점수 확인, `소환` → 스캔 화면에 소환 차량 등장
6. 키워드 탭: 항목 변경 → 다음 인식부터 카드 뒷면 변경

에뮬레이터 → 호스트 백엔드 주소는 `http://10.0.2.2:8000/` (`app/build.gradle.kts`).
에뮬레이터 위치 테스트: `adb emu geo fix 127.0276 37.4979` (경도 먼저).

## API

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/v1/recognize` | 프레임 1장 + `lat`,`lon`,`keywords` → 카드 |
| POST | `/api/v1/recognize/video` | 주행 영상 → 차량별 카드 (최대 `VIDEO_MAX_VEHICLES`) |
| GET | `/api/v1/collection` | 도감 + 시너지 점수 |
| DELETE | `/api/v1/collection/{id}` | 도감 삭제 |
| GET / POST / DELETE | `/api/v1/garage`, `/api/v1/garage/summon` | 소환 상태 / 소환 `{card_id}` / 해제 |
| GET / PUT | `/api/v1/preferences` | 유저 키워드(최대 8개) |

## LLM 설정 메모
- `claude-opus-5-5`, 구조화 출력으로 카드 형식 보장, 응답성을 위해 `LLM_EFFORT=low`
- 안전 필터 거절 시 서버 측 자동 재시도(`fallbacks="default"`), 최종 실패 시 mock 요약
- 같은 차량을 2분 안에 다시 보면 저장된 카드 재사용 (영상 연속 인식 비용 절감)
- 카탈로그 수치는 예시값 — 시연 전 검수 필요
