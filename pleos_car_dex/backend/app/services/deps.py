"""서비스 싱글톤 (FastAPI Depends 용)"""
from functools import lru_cache

from app.config import get_settings
from app.services.challenge import ChallengeService
from app.services.competition import CompetitionService
from app.services.courses import CourseService
from app.services.decor import DecorService
from app.services.landmarks import LandmarkService
from app.services.pipeline import get_pipeline
from app.services.tour import TourClient


@lru_cache
def get_tour() -> TourClient:
    return TourClient(get_settings())


@lru_cache
def get_competition() -> CompetitionService:
    comp = CompetitionService(get_pipeline().store)
    from app.services.mycar import TWIN_POINTS
    get_pipeline().on_twin = lambda card: comp.record(TWIN_POINTS, None, "twin", card.model)  # 쌍둥이 차 보너스
    daily = get_daily()

    def on_card(card):  # 오늘의 차 보너스 + 출석
        bonus = daily.on_card(card.id)
        if bonus:
            comp.record(bonus, None, "daily", card.model)
        return bonus

    get_pipeline().on_card = on_card
    return comp


@lru_cache
def get_daily():
    from app.services.daily import DailyService
    return DailyService(get_pipeline().store)


@lru_cache
def get_challenge() -> ChallengeService:
    return ChallengeService(get_pipeline().store, get_competition())


@lru_cache
def get_decor() -> DecorService:
    return DecorService(get_pipeline().store, get_competition())


@lru_cache
def get_landmarks() -> LandmarkService:
    return LandmarkService(get_settings(), get_tour(), get_pipeline().store, get_challenge(), get_competition())


@lru_cache
def get_courses() -> CourseService:
    return CourseService(get_settings(), get_tour(), get_pipeline().store)


def clear_all() -> None:
    for f in (get_tour, get_daily, get_competition, get_challenge, get_decor, get_landmarks, get_courses):
        f.cache_clear()
