"""API 계약(백엔드 <-> Pleos 프론트엔드). 안드로이드 Models.kt 와 필드명을 맞춘다."""
from datetime import datetime

from pydantic import BaseModel, Field

DEFAULT_KEYWORDS = ["가격", "브랜드", "마력", "연비", "출시연도"]


class BoundingBox(BaseModel):
    x1: int
    y1: int
    x2: int
    y2: int

    @property
    def area(self) -> int:
        return max(0, self.x2 - self.x1) * max(0, self.y2 - self.y1)


class Detection(BaseModel):
    label: str  # COCO 클래스명 (car, truck, bus, motorcycle)
    confidence: float
    bbox: BoundingBox


class VehicleIdentity(BaseModel):
    """2단계 식별 결과 (Claude 구조화 출력 스키마로도 사용)."""

    is_vehicle: bool
    brand: str
    model: str
    body_type: str = Field(description="세단, SUV, 해치백, 트럭, 버스 등")
    confidence: float = Field(description="0.0 ~ 1.0")


class KeywordFact(BaseModel):
    keyword: str
    value: str


class CardSummary(BaseModel):
    """카드 뒷면: 차량 정보 목록에서 사용자가 고른 항목"""

    one_liner: str
    facts: list[KeywordFact]


class VehicleCard(BaseModel):
    id: str  # brand-model slug, 도감 키
    brand: str
    model: str
    body_type: str
    image_url: str | None = None  # 기본은 None (개인정보: 원본/크롭 이미지 미저장, 앞면은 일러스트). SAVE_CROPS=true 일 때만
    one_liner: str
    facts: list[KeywordFact]  # 카드 뒷면
    rarity: str = "common"  # common | rare | legendary (도감 게이미피케이션)
    is_my_car: bool = False  # 내 차와 같은 모델
    vs_my_car: list[str] = []  # 카드 뒷면 '내 차와 비교' (조회 시 계산)


class RecognitionResponse(BaseModel):
    detected: bool
    twin: bool = False  # 내 차와 같은 차(쌍둥이)를 만남
    daily_bonus: int = 0  # 오늘의 차를 찾았으면 보너스 점수
    detection: Detection | None = None  # 카드로 만든 차량 (가장 가깝게 보인 차)
    detections: list[Detection] = []  # 화면 속 모든 차량 (앱에서 박스 표시)
    identity: VehicleIdentity | None = None
    card: VehicleCard | None = None
    is_new_entry: bool = False
    message: str = ""


class Preferences(BaseModel):
    keywords: list[str] = Field(default_factory=lambda: list(DEFAULT_KEYWORDS), max_length=8)
    language: str = "ko"
    my_car_id: str | None = None  # 내 차 (도감 28종 중)


class GeoPoint(BaseModel):
    lat: float
    lon: float


class CollectionEntry(BaseModel):
    card: VehicleCard
    first_seen_at: datetime
    last_seen_at: datetime
    seen_count: int = 1
    first_seen_location: GeoPoint | None = None


class CollectionResponse(BaseModel):
    total: int
    entries: list[CollectionEntry]
    synergy: "SynergyScore"


class VideoRecognitionResponse(BaseModel):
    frames_analyzed: int
    vehicles: list[RecognitionResponse]


class SynergyBonus(BaseModel):
    name: str
    points: int
    description: str


class SynergyScore(BaseModel):
    total: int
    base: int  # 차량 희귀도 합
    travel: int = 0  # 지역 스탬프 기본 점수 합
    bonuses: list[SynergyBonus]


class Region(BaseModel):
    id: str
    name: str
    lat: float
    lon: float
    landmark: str
    emoji: str
    colors: list[str]  # 쇼룸 배경 그라데이션 [시작, 끝] (#RRGGBB)
    travel_points: int = 10  # 첫 방문 점수 (지방·인구감소지역일수록 높게)
    area: str = ""  # 권역 (수도권/충청권/호남권/영남권/강원권/제주권)
    scene: str = "city"  # 쇼룸 배경 풍경 종류


class Stamp(BaseModel):
    region: Region
    acquired: bool
    first_visited_at: datetime | None = None
    visit_count: int = 0


class Attraction(BaseModel):
    """TourAPI 관광지/축제 (히든 스탬프 & 코스 후보)"""

    content_id: str
    title: str
    addr: str = ""
    lat: float
    lon: float
    image: str | None = None
    kind: str = "attraction"  # attraction | festival | brand (브랜드 체험 스팟)
    event_start: str | None = None  # YYYYMMDD (축제)
    event_end: str | None = None
    region_id: str | None = None


class HiddenStamp(BaseModel):
    attraction: Attraction
    acquired_at: datetime
    points: int


class LandmarkNearby(BaseModel):
    attraction: Attraction
    distance_m: int
    collected: bool
    points: int


class LandmarkVisitRequest(BaseModel):
    lat: float
    lon: float


class LandmarkVisitResponse(BaseModel):
    acquired: bool
    is_new: bool = False
    stamp: HiddenStamp | None = None
    message: str = ""
    challenge: "ChallengeUpdate | None" = None
    legendary_item: str | None = None  # 브랜드 스팟 첫 방문 시 열린 전설 쇼룸 아이템


class StampBook(BaseModel):
    acquired: int
    total: int
    stamps: list[Stamp]
    hidden: list[HiddenStamp] = []


# ---------------- AI 드라이브 코스 ----------------
class CourseRequest(BaseModel):
    lat: float
    lon: float
    max_hours: float = Field(4.0, ge=1, le=12, description="왕복 희망 시간")
    date: str | None = Field(None, description="YYYYMMDD, 비우면 오늘")
    note: str = Field("", max_length=200, description="취향 메모 (예: 바다, 아이와 함께)")


class CourseStop(BaseModel):
    content_id: str
    name: str
    lat: float
    lon: float
    kind: str  # attraction | festival | region
    region_id: str | None = None
    reason: str
    new_region_stamp: bool = False
    new_hidden_stamp: bool = False


class LimitedEvent(BaseModel):
    """축제 한정 코스: 축제 기간 안에 완주하면 한정 배지"""

    content_id: str
    title: str
    event_end: str  # YYYYMMDD


class DriveCourse(BaseModel):
    title: str
    summary: str
    stops: list[CourseStop]
    est_drive_hours: float  # 서버 계산 (직선거리 기반 추정)
    expected_points: int  # 서버 계산
    candidates_source: str  # tourapi | mock
    limited: LimitedEvent | None = None
    completion_bonus: int = 0  # 완주 시 추가 점수 (서버 계산)


# ---------------- 경쟁: 코스 챌린지 / 랭킹 / 지역 마스터 / 한정 배지 ----------------
class Challenge(BaseModel):
    course: DriveCourse
    started_at: datetime
    expires_at: datetime
    visited: list[str] = []  # 방문한 stop content_id
    status: str = "active"  # active | completed | expired
    completed_at: datetime | None = None


class LimitedBadge(BaseModel):
    id: str  # "{content_id}-{year}"
    title: str  # 예: "2026 진주 남강 유등 축제 완주"
    festival_title: str
    region_id: str | None = None
    earned_at: datetime


class PointEvent(BaseModel):
    """여행 점수 원장 (랭킹·지역 마스터 계산용)"""

    user_id: str
    nickname: str
    points: int
    region_id: str | None = None
    kind: str  # region_stamp | revisit | hidden_stamp | course_complete | limited_badge
    label: str = ""
    at: datetime


class RankRow(BaseModel):
    rank: int
    user_id: str
    nickname: str
    points: int
    is_me: bool = False


class Leaderboard(BaseModel):
    period: str  # 예: "2026-W45"
    region_id: str | None = None
    rows: list[RankRow]
    me: RankRow | None = None
    gap_to_next: int | None = None  # 바로 윗 순위까지 남은 점수


class RegionMaster(BaseModel):
    region: Region
    master: RankRow | None = None
    my_points: int = 0
    gap: int = 0  # 마스터가 되기까지 필요한 점수 (내가 마스터면 0)
    is_me: bool = False


class MastersResponse(BaseModel):
    period: str  # 예: "2026-11"
    masters: list[RegionMaster]
    my_titles: list[str]  # 내가 마스터인 지역 이름


class ChallengeUpdate(BaseModel):
    """명소 방문 시 챌린지 진행 상황"""

    challenge: Challenge
    just_completed: bool = False
    bonus_points: int = 0
    badge: LimitedBadge | None = None


class CourseCandidate(CourseStop):
    dist_km: float = 0
    event: str = ""


class CourseCandidates(BaseModel):
    """앱의 Pleos LLM 이 코스를 고를 수 있도록 주는 후보 + 프롬프트 (+ LLM 이 없을 때 쓸 규칙 기반 선택)"""

    candidates: list[CourseCandidate]
    fallback: "LLMCoursePick | None" = None
    prompt: str
    source: str


class CourseBuildRequest(BaseModel):
    request: CourseRequest
    title: str
    summary: str
    stop_ids: list[str]
    reasons: list[str] = []


class LLMCoursePick(BaseModel):
    """LLM 구조화 출력: 후보 ID 중에서 고른 코스"""

    title: str
    summary: str
    stop_ids: list[str]
    reasons: list[str]


class VisitRequest(BaseModel):
    lat: float
    lon: float


class VisitResponse(BaseModel):
    stamp: Stamp | None = None
    is_new: bool = False
    message: str = ""


MAX_PROPS = 8


class DecorItem(BaseModel):
    """쇼룸 꾸미기 아이템 (바닥/조명/전시대/간판/소품). 지역 스탬프 소품은 id = 'region:<region_id>'"""

    id: str
    slot: str  # floor | light | pedestal | sign | prop
    key: str  # 그리기 키 (prop 은 이모지, light 는 색, sign 은 문구)
    name: str
    unlocked: bool = False
    unlock_hint: str = ""  # 잠금 해제 조건 설명
    legendary: bool = False  # 브랜드 스팟 방문으로만 열리는 전설 아이템


class PlacedProp(BaseModel):
    item_id: str
    x: float = Field(0.5, ge=0, le=1)  # 무대 기준 0~1 좌표
    y: float = Field(0.8, ge=0, le=1)
    scale: float = Field(1.0, ge=0.5, le=2.0)


class PlacedPropOut(PlacedProp):
    item: DecorItem


class Showroom(BaseModel):
    """쇼룸(차고): 대표 차량 1대 + 획득한 아이템으로 꾸민 무대."""

    car: VehicleCard | None = None
    background: Region | None = None  # 지역 풍경 배경 (획득한 지역 스탬프)
    floor: DecorItem | None = None
    light: DecorItem | None = None
    pedestal: DecorItem | None = None
    sign: DecorItem | None = None
    sign_text: str = "MY GARAGE"
    props: list[PlacedPropOut] = []
    background_mastered: bool = False  # 배경 지역의 이번 달 마스터면 왕관·깃발 표시
    titles: list[str] = []  # 내가 마스터인 지역들
    badges: list["LimitedBadge"] = []  # 축제 한정 배지 (트로피 진열)


class ShowroomUpdate(BaseModel):
    card_id: str | None = None
    background_region_id: str | None = None
    floor_id: str = "floor-concrete"
    light_id: str = "light-white"
    pedestal_id: str = "pedestal-basic"
    sign_id: str = "sign-garage"
    props: list[PlacedProp] = Field(default_factory=list, max_length=MAX_PROPS)


class DecorCatalog(BaseModel):
    items: list[DecorItem]
    unlocked: int
    total: int


class CatalogVehicle(BaseModel):
    """도감 칸 (미발견 차량은 앱에서 실루엣 '???' 로 표시)"""

    id: str
    brand: str
    model: str
    body_type: str
    rarity: str


class DailyMission(BaseModel):
    """오늘의 차 미션 + 연속 출석"""

    date: str
    target: CatalogVehicle
    found: bool
    bonus: int
    streak: int
    best_streak: int


class HomeSummary(BaseModel):
    """도감/여행 탭 상단 '한눈에 보기' 카드"""

    dex_owned: int = 0
    dex_total: int = 0
    legendary_owned: int = 0
    legendary_total: int = 0
    synergy_total: int
    weekly_rank: int
    weekly_points: int
    gap_to_next: int | None = None
    region_stamps: int
    region_total: int
    hidden_stamps: int
    collection: int
    items_unlocked: int
    items_total: int
    titles: list[str] = []
    challenge_title: str | None = None
    challenge_progress: str | None = None  # 예: "1/3"
    next_goal: str  # 다음 목표 한 줄 (예: "부산 마스터까지 35점")
    daily: DailyMission | None = None
    brand_spots: int = 0  # 방문한 브랜드 체험 스팟
    brand_total: int = 0


class QuizQuestion(BaseModel):
    """아이용 차 맞히기 퀴즈 (YOLO 결과만으로 출제). 정답도 함께 보내고 앱이 채점"""

    id: str
    type: str  # kind | color
    prompt: str
    choices: list[str]
    choice_icons: list[str]  # 종류: 이모지 / 색깔: #RRGGBB
    answer: str
    detection: Detection


class QuizStars(BaseModel):
    """퀴즈 별 + 쇼룸 스티커 진행"""

    stars: int
    unlocked_sticker: str | None = None  # 이번 별로 열린 스티커
    next_sticker: str | None = None
    stars_to_next: int = 0
    stickers_owned: int = 0
    stickers_total: int = 0


class QuizStarRequest(BaseModel):
    question_id: str


class QuizResponse(BaseModel):
    found: bool
    question: QuizQuestion | None = None
    detections: list[Detection] = []
    message: str = ""


CollectionResponse.model_rebuild()
CourseCandidates.model_rebuild()
LandmarkVisitResponse.model_rebuild()
Showroom.model_rebuild()


class TripReportRequest(BaseModel):
    """주차(P단) 시 앱이 보내는 주행 기록. 거리·시간은 Pleos Vehicle SDK Odometer(이번 주행) 값"""

    started_at: datetime  # 이번 주행 시작 (앱이 주행 기어 진입 시각을 기억)
    distance_km: float = Field(0, ge=0, le=2000)
    drive_minutes: int = Field(0, ge=0, le=24 * 60)
    source: str = "pleos_odometer"  # pleos_odometer | estimate


class TripReport(BaseModel):
    """주행 리포트: 이번 드라이브에서 모은 것 (속도·운전 습관 평가는 하지 않음)"""

    distance_km: float
    drive_minutes: int
    new_cards: list[str] = []  # "현대 아이오닉 5"
    new_legendary: int = 0
    stamps: list[str] = []  # 지역/히든/브랜드 스탬프 이름
    points: int = 0
    daily_found: bool = False
    streak: int = 0
    total_km: float = 0  # 앱과 함께한 누적 거리
    trips: int = 0
    headline: str  # 한 줄 제목
    tts: str  # 음성 요약 (짧게)
    next_goal: str = ""
