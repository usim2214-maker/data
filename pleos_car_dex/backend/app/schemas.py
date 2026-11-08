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
    """LLM 요약 결과 (Claude 구조화 출력 스키마로도 사용)."""

    one_liner: str
    facts: list[KeywordFact]


class VehicleCard(BaseModel):
    id: str  # brand-model slug, 도감 키
    brand: str
    model: str
    body_type: str
    image_url: str | None = None  # 카드 앞면 이미지 (MVP: 크롭 이미지)
    one_liner: str
    facts: list[KeywordFact]  # 카드 뒷면
    rarity: str = "common"  # common | rare | legendary (도감 게이미피케이션)


class RecognitionResponse(BaseModel):
    detected: bool
    detection: Detection | None = None
    identity: VehicleIdentity | None = None
    card: VehicleCard | None = None
    is_new_entry: bool = False
    message: str = ""


class Preferences(BaseModel):
    keywords: list[str] = Field(default_factory=lambda: list(DEFAULT_KEYWORDS), max_length=8)
    language: str = "ko"


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
    base: int
    bonuses: list[SynergyBonus]


class Region(BaseModel):
    id: str
    name: str
    lat: float
    lon: float
    landmark: str
    emoji: str
    colors: list[str]  # 쇼룸 배경 그라데이션 [시작, 끝] (#RRGGBB)


class Stamp(BaseModel):
    region: Region
    acquired: bool
    first_visited_at: datetime | None = None
    visit_count: int = 0


class StampBook(BaseModel):
    acquired: int
    total: int
    stamps: list[Stamp]


class VisitRequest(BaseModel):
    lat: float
    lon: float


class VisitResponse(BaseModel):
    stamp: Stamp | None = None
    is_new: bool = False
    message: str = ""


MAX_STICKERS = 3


class Showroom(BaseModel):
    """카트라이더식 쇼룸: 대표 차량 1대 + 획득한 지역 스탬프로 꾸민 배경/스티커."""

    car: VehicleCard | None = None
    background: Region | None = None
    stickers: list[Region] = []


class ShowroomUpdate(BaseModel):
    card_id: str | None = None
    background_region_id: str | None = None
    sticker_region_ids: list[str] = Field(default_factory=list, max_length=MAX_STICKERS)


CollectionResponse.model_rebuild()
