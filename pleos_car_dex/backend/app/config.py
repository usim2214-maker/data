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

    # Storage
    data_dir: Path = Path("./storage")

    @property
    def llm_enabled(self) -> bool:
        return bool(self.anthropic_api_key) and not self.use_mock_llm

    @property
    def crops_dir(self) -> Path:
        return self.data_dir / "crops"


@lru_cache
def get_settings() -> Settings:
    s = Settings()
    s.crops_dir.mkdir(parents=True, exist_ok=True)
    return s
