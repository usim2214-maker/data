# Pleos CarDex — 차량 인식 & 맞춤형 요약 도감 (MVP)

현대차그룹 **Pleos Connect** 인포테인먼트 환경(Pleos Playground / 에뮬레이터)에서
주변 차량을 인식하고, 유저가 고른 키워드(가격·브랜드·마력 등)로 LLM이 요약한 정보를
**플립 카드**로 보여주며 **도감(Collection)** 에 수집하는 시연용 앱입니다.

```
[Pleos 앱 (Android Automotive, Compose)]
  ├─ FrameSource ──────── 시연 이미지(assets) / (TODO) 카메라 스트림
  ├─ VehicleContext ───── (TODO) Pleos FusedLocation·Vehicle SDK / Mock 주행 데이터
  │        │ multipart: image + lat/lon
  ▼        ▼
[FastAPI 백엔드]  POST /api/v1/recognize
  1. VehicleDetector   YOLO(COCO car/truck/bus/motorcycle) 감지 → 가장 큰 차량 크롭
  2a. VehicleIdentifier Claude 비전으로 브랜드/모델 식별 (시연 차종 우선 후보)
  2b. CardSummarizer   유저 키워드를 프롬프트에 동적 주입 → 구조화 출력(CardSummary)
  3. JsonStore         도감 등록(신규/재발견, 최초 발견 위치) + 크롭 이미지 저장
  ▼
[카드 UI]  앞면: 이미지·이름·한 줄 소개 / 뒷면: 키워드별 요약   [도감] 그리드
```

## 디렉토리 구조

```
pleos_car_dex/
├── backend/                     FastAPI 서버
│   ├── app/
│   │   ├── main.py              앱 엔트리, 라우터/정적파일(크롭 이미지) 마운트
│   │   ├── config.py            환경변수 설정 (.env)
│   │   ├── schemas.py           API 계약 (안드로이드 Models.kt 와 1:1)
│   │   ├── routers/             recognize / collection / preferences
│   │   ├── services/
│   │   │   ├── detector.py      1단계 YOLO 감지·크롭
│   │   │   ├── identifier.py    2단계(a) 차종 식별 (Claude 비전 / mock)
│   │   │   ├── summarizer.py    2단계(b) 키워드 기반 요약 (Claude / mock)
│   │   │   ├── llm.py           Claude 클라이언트·구조화 출력 공통
│   │   │   ├── pipeline.py      전체 흐름 오케스트레이션
│   │   │   ├── catalog.py       시연 차종 카탈로그 매칭
│   │   │   └── store.py         도감·설정 JSON 저장소
│   │   └── data/vehicle_catalog.json   시연 차종 6종 + 근거 데이터
│   ├── tests/test_api.py        mock 모드 E2E 테스트
│   ├── requirements.txt         기본 (mock 시연 가능)
│   └── requirements-vision.txt  + ultralytics (실제 YOLO)
├── android/                     Pleos Connect 앱 (Android Automotive OS, Kotlin/Compose)
│   └── app/src/main/
│       ├── AndroidManifest.xml  automotive feature, distractionOptimized
│       ├── assets/demo_frames/  ← 시연용 차량 사진을 넣는 곳
│       └── java/com/pleos/cardex/
│           ├── MainActivity.kt          내비게이션 레일 (스캔 / 도감 / 키워드)
│           ├── data/                    Retrofit API, 모델, Repository
│           ├── pleos/                   Pleos SDK 연동 지점 (위치·주행, 프레임 소스)
│           └── ui/                      ViewModel, FlipCard, 화면 3종, 테마
└── scripts/demo_request.sh      curl 로 백엔드 단독 시연
```

## 빠른 시작

### 1) 백엔드
```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt            # 실제 YOLO 를 쓰려면 requirements-vision.txt
cp .env.example .env                       # ANTHROPIC_API_KEY 입력 (비우면 mock 모드)
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
# 문서: http://localhost:8000/docs
```
- **완전 오프라인 시연**: `.env` 에서 `DETECTOR_MODE=mock`, `IDENTIFIER_MODE=mock`, `USE_MOCK_LLM=true`.
  mock 식별은 파일명(예: `kia-ev9.jpg`)으로 카탈로그 차량을 고릅니다.
- ultralytics 가 없거나 가중치 다운로드에 실패하면 자동으로 mock 감지로 내려가 시연이 멈추지 않습니다.
- 테스트: `python -m pytest -q`

### 2) Pleos 앱 (에뮬레이터)
1. Android Studio 로 `android/` 열기 → Pleos Playground 에서 제공하는 AAOS 에뮬레이터 이미지로 AVD 생성
2. `app/src/main/assets/demo_frames/` 에 차량 사진 추가 (파일명 = 카탈로그 id 권장)
3. 실행 → **스캔** 탭에서 `지금 인식` / `자동 스캔` → 카드 탭하면 뒤집혀 키워드 요약 표시
4. **키워드** 탭에서 순서·항목 변경 → 다음 인식부터 카드 뒷면이 바뀜 (핵심 시연 포인트)
5. **도감** 탭에서 수집 현황 확인

에뮬레이터에서 호스트 백엔드는 `http://10.0.2.2:8000/` (`app/build.gradle.kts` 의 `API_BASE_URL`).

### Pleos SDK 연동 (TODO)
`BuildConfig.USE_MOCK_PLEOS=true` 상태에선 목업 주행 데이터로 동작합니다. 실제 연동 시:
- `settings.gradle.kts` / `app/build.gradle.kts` 의 `TODO(Pleos)` 위치에 Playground 에서 받은 SDK 저장소·의존성 추가
- `pleos/VehicleContext.kt` 의 `PleosVehicleContextProvider` 를 FusedLocation(위치)·차량 신호(차속/기어)로 구현
- 카메라 사용 가능 환경이면 `pleos/FrameSource.kt` 에 `CameraFrameSource` 추가

SDK 클래스/메서드명은 버전마다 다를 수 있어 스켈레톤에서는 인터페이스로만 분리해 두었습니다.

## API 요약

| Method | Path | 설명 |
|---|---|---|
| POST | `/api/v1/recognize` | multipart `image`, 선택 `lat`,`lon`,`keywords`(쉼표), `save_to_collection` → `RecognitionResponse` |
| GET | `/api/v1/collection` | 도감 목록 (최근 발견 순) |
| DELETE | `/api/v1/collection/{id}` | 도감에서 삭제 |
| GET/PUT | `/api/v1/preferences` | 유저 키워드(최대 8개)·언어 |
| GET | `/health` | 감지/식별/LLM 모드 확인 |

## LLM 설정 메모
- 모델 `claude-opus-5-5`, 구조화 출력(`messages.parse` + Pydantic)으로 카드 스키마를 보장
- 차량 UI 응답성을 위해 `LLM_EFFORT=low` 기본값 (정확도가 더 필요하면 `medium`)
- 안전 분류기 거절 시 서버 측 자동 재시도(`fallbacks="default"`) 사용, 최종 거절이면 mock 요약으로 대체
- 카탈로그 데이터를 참고 근거로 주입해 수치 환각을 줄임 — **카탈로그 수치는 예시값이므로 시연 전 검수 필요**

## 다음 단계 (MVP 이후)
- 시연 차종 전용 분류기(YOLO 커스텀 학습)로 `VehicleIdentifier` 교체 → 지연·비용 감소
- 카드 앞면 일러스트 생성(현재는 크롭 사진)
- 주행 중(차속 > 0)에는 카드 상세를 축약하는 운전자 주의분산 정책
