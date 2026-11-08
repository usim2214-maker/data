"""코스 완주 챌린지 + 축제 한정 배지.

- 추천 코스로 챌린지 시작 -> 코스의 모든 장소(500m 반경)를 방문하면 완주 보너스
- 축제가 포함된 코스를 축제 기간 안에 완주하면 한정 배지 (기간이 지나면 다시 얻을 수 없음)
- 제한 시간: 시작 후 24시간 (축제 코스는 축제 종료일까지 중 빠른 쪽)
"""
from datetime import datetime, timedelta, timezone

from app.schemas import Challenge, ChallengeUpdate, DriveCourse, LimitedBadge
from app.services.competition import KST, CompetitionService
from app.services.store import JsonStore

CHALLENGE_HOURS = 24
LIMITED_BADGE_POINTS = 50


def completion_bonus(expected_points: int) -> int:
    """완주 보너스 = 30 + 코스 예상 점수의 절반 (코스대로 다 돌수록 이득)"""
    return 30 + expected_points // 2


class ChallengeService:
    def __init__(self, store: JsonStore, competition: CompetitionService):
        self.store, self.competition = store, competition

    def start(self, course: DriveCourse) -> Challenge:
        now = datetime.now(timezone.utc)
        expires = now + timedelta(hours=CHALLENGE_HOURS)
        if course.limited:
            fest_end = datetime.strptime(course.limited.event_end, "%Y%m%d").replace(hour=23, minute=59, tzinfo=KST)
            expires = min(expires, fest_end.astimezone(timezone.utc))
        challenge = Challenge(course=course, started_at=now, expires_at=expires)
        self.store.save_challenge(challenge)
        return challenge

    def current(self) -> Challenge | None:
        ch = self.store.get_challenge()
        if ch and ch.status == "active" and datetime.now(timezone.utc) > ch.expires_at:
            ch.status = "expired"
            self.store.save_challenge(ch)
        return ch

    def abandon(self) -> None:
        self.store.save_challenge(None)

    def on_visit(self, content_id: str) -> ChallengeUpdate | None:
        """명소 반경 안 방문이 확인됐을 때 호출 (LandmarkService)."""
        ch = self.current()
        if ch is None or ch.status != "active":
            return None
        stop_ids = [s.content_id for s in ch.course.stops]
        if content_id not in stop_ids or content_id in ch.visited:
            return None
        ch.visited.append(content_id)
        update = ChallengeUpdate(challenge=ch)

        if set(stop_ids) <= set(ch.visited):
            ch.status, ch.completed_at = "completed", datetime.now(timezone.utc)
            dest_region = ch.course.stops[-1].region_id
            update.just_completed, update.bonus_points = True, ch.course.completion_bonus
            self.competition.record(ch.course.completion_bonus, dest_region, "course_complete", ch.course.title)

            if (lim := ch.course.limited) and ch.completed_at.astimezone(KST).strftime("%Y%m%d") <= lim.event_end:
                year = ch.completed_at.astimezone(KST).year
                badge = LimitedBadge(
                    id=f"{lim.content_id}-{year}", title=f"{year} {lim.title} 완주", festival_title=lim.title,
                    region_id=next((s.region_id for s in ch.course.stops if s.content_id == lim.content_id), dest_region),
                    earned_at=ch.completed_at,
                )
                if self.store.add_badge(badge):
                    update.badge = badge
                    self.competition.record(LIMITED_BADGE_POINTS, badge.region_id, "limited_badge", badge.title)
        self.store.save_challenge(ch)
        return update
