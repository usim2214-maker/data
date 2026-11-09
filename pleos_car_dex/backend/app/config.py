from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # LLM
    anthropic_api_key: str = ""
    use_mock_llm: bool = False
    llm_model: str = "claude-opus-5-5"
    llm_effort: Literal["low", "medium", "high", "xhigh", "max"] = "low"

    # Vision
    detector_mode: Literal["yolo", "mock"] = "yolo"
    yolo_weights: str = "yolov8n.pt"
    yolo_conf: float = 0.35
    identifier_mode: Literal["llm", "mock"] = "llm"

    # Video (주행 영상 직접 입력)
    video_sample_fps: float = 2.0
    video_max_vehicles: int = 3
    # 같은 차량을 이 시간(초) 안에 다시 보면 LLM 요약을 재사용 (비용/지연 절감)
    reuse_card_within_sec: int = 120
    # 같은 차(겹치는 위치 또는 비슷한 모습)를 이 시간(초) 안에 다시 보면 차종 판별(Claude 비전)도 재사용
    reuse_identity_within_sec: int = 30
    # 개인정보: 기본은 크롭 이미지를 저장하지 않음 (번호판 등). 디버깅용으로만 true
    save_crops: bool = False

    # 한국관광공사 TourAPI (공공데이터포털 KorService2). 비우면 시연용 목업 명소/축제 사용
    tour_api_key: str = ""
    tour_api_base: str = "https://apis.data.go.kr/B551011/KorService2"
    # 히든 스탬프: 명소 반경(m) 안에 들어와야 획득 인정 (앱의 근접 알림 반경과 맞출 것)
    landmark_radius_m: int = 500

    # 시연 도구 (/api/v1/demo/seed, /reset). 실제 배포에서는 false
    demo_mode: bool = False

    # Storage
    data_dir: Path = Path("./storage")

    @property
    def llm_enabled(self) -> bool:
        return bool(self.anthropic_api_key) and not self.use_mock_llm

    @property
    def tour_enabled(self) -> bool:
        return bool(self.tour_api_key)

    @property
    def crops_dir(self) -> Path:
        return self.data_dir / "crops"


@lru_cache
def get_settings() -> Settings:
    s = Settings()
    s.crops_dir.mkdir(parents=True, exist_ok=True)
    return s
