"""도감(Collection) & 유저 설정 저장소. MVP 는 단일 사용자 JSON 파일."""
import json
import threading
from datetime import datetime, timezone
from pathlib import Path

from datetime import timedelta

from app.schemas import (
    Challenge,
    CollectionEntry,
    GeoPoint,
    HiddenStamp,
    LimitedBadge,
    PointEvent,
    Preferences,
    ShowroomUpdate,
    VehicleCard,
)


class JsonStore:
    def __init__(self, data_dir: Path):
        self._lock = threading.Lock()
        self._collection_path = data_dir / "collection.json"
        self._prefs_path = data_dir / "preferences.json"
        self._showroom_path = data_dir / "showroom.json"
        self._stamps_path = data_dir / "stamps.json"
        self._hidden_path = data_dir / "hidden_stamps.json"
        self._events_path = data_dir / "point_events.json"
        self._challenge_path = data_dir / "challenge.json"
        self._badges_path = data_dir / "badges.json"
        data_dir.mkdir(parents=True, exist_ok=True)

    # ---- preferences ----
    def get_preferences(self) -> Preferences:
        if self._prefs_path.exists():
            return Preferences.model_validate_json(self._prefs_path.read_text(encoding="utf-8"))
        return Preferences()

    def save_preferences(self, prefs: Preferences) -> Preferences:
        with self._lock:
            self._prefs_path.write_text(prefs.model_dump_json(indent=2), encoding="utf-8")
        return prefs

    # ---- collection ----
    def _load(self) -> dict[str, CollectionEntry]:
        if not self._collection_path.exists():
            return {}
        raw = json.loads(self._collection_path.read_text(encoding="utf-8"))
        return {k: CollectionEntry.model_validate(v) for k, v in raw.items()}

    def _dump(self, entries: dict[str, CollectionEntry]) -> None:
        payload = {k: v.model_dump(mode="json") for k, v in entries.items()}
        self._collection_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    def list_entries(self) -> list[CollectionEntry]:
        return sorted(self._load().values(), key=lambda e: e.last_seen_at, reverse=True)

    def register(self, card: VehicleCard, location: GeoPoint | None) -> tuple[CollectionEntry, bool]:
        """도감 등록. (entry, 신규여부) 반환. 재발견 시 카드 내용(최신 키워드 요약)과 카운트 갱신."""
        now = datetime.now(timezone.utc)
        with self._lock:
            entries = self._load()
            existing = entries.get(card.id)
            if existing:
                existing.card, existing.last_seen_at = card, now
                existing.seen_count += 1
                entry, is_new = existing, False
            else:
                entry = CollectionEntry(card=card, first_seen_at=now, last_seen_at=now, first_seen_location=location)
                entries[card.id], is_new = entry, True
            self._dump(entries)
        return entry, is_new

    def reset_all(self) -> None:
        """시연 초기화: 저장된 진행 데이터를 모두 지운다 (크롭 이미지는 유지)"""
        with self._lock:
            for p in (self._collection_path, self._prefs_path, self._showroom_path, self._stamps_path,
                      self._hidden_path, self._events_path, self._challenge_path, self._badges_path,
                      self.extra_path("daily.json"), self.extra_path("trips.json"), self.extra_path("quiz.json")):
                p.unlink(missing_ok=True)

    def extra_path(self, name: str) -> Path:
        """부가 기능(이번 주의 차·주행 리포트·퀴즈)용 파일 경로"""
        return self._collection_path.with_name(name)

    def set_card_color(self, card_id: str, color_code: str | None) -> CollectionEntry | None:
        with self._lock:
            entries = self._load()
            e = entries.get(card_id)
            if e is None:
                return None
            e.card = e.card.model_copy(update={"color_code": color_code})
            self._dump(entries)
            return e

    def get_entry(self, card_id: str) -> CollectionEntry | None:
        return self._load().get(card_id)

    def delete(self, card_id: str) -> bool:
        with self._lock:
            entries = self._load()
            if entries.pop(card_id, None) is None:
                return False
            self._dump(entries)
        showroom = self.get_showroom()
        if showroom.card_id == card_id:
            showroom.card_id = None
            self.save_showroom(showroom)
        return True

    # ---- showroom (대표 차량 + 배경 꾸미기) ----
    def get_showroom(self) -> ShowroomUpdate:
        if self._showroom_path.exists():
            return ShowroomUpdate.model_validate_json(self._showroom_path.read_text(encoding="utf-8"))
        return ShowroomUpdate()

    def save_showroom(self, state: ShowroomUpdate) -> ShowroomUpdate:
        with self._lock:
            self._showroom_path.write_text(state.model_dump_json(indent=2), encoding="utf-8")
        return state

    # ---- 지역 스탬프 ----
    REVISIT_GAP = timedelta(hours=1)  # 같은 지역 재방문으로 세는 최소 간격

    def get_stamps(self) -> dict[str, dict]:
        """{region_id: {"first_visited_at": iso, "last_visited_at": iso, "visit_count": n}}"""
        if self._stamps_path.exists():
            return json.loads(self._stamps_path.read_text(encoding="utf-8"))
        return {}

    def visit(self, region_id: str) -> tuple[dict, bool, bool]:
        """반환: (기록, 첫 방문 여부, 재방문으로 카운트됐는지)"""
        now = datetime.now(timezone.utc)
        revisit = False
        with self._lock:
            stamps = self.get_stamps()
            rec = stamps.get(region_id)
            is_new = rec is None
            if is_new:
                rec = {"first_visited_at": now.isoformat(), "last_visited_at": now.isoformat(), "visit_count": 1}
            elif now - datetime.fromisoformat(rec["last_visited_at"]) >= self.REVISIT_GAP:
                rec["visit_count"] += 1
                rec["last_visited_at"] = now.isoformat()
                revisit = True
            stamps[region_id] = rec
            self._stamps_path.write_text(json.dumps(stamps, ensure_ascii=False, indent=2), encoding="utf-8")
        return rec, is_new, revisit

    # ---- 히든 스탬프 (TourAPI 명소/축제 방문) ----
    def get_hidden(self) -> dict[str, HiddenStamp]:
        if not self._hidden_path.exists():
            return {}
        raw = json.loads(self._hidden_path.read_text(encoding="utf-8"))
        return {k: HiddenStamp.model_validate(v) for k, v in raw.items()}

    def add_hidden(self, stamp: HiddenStamp) -> tuple[HiddenStamp, bool]:
        with self._lock:
            hidden = self.get_hidden()
            if (existing := hidden.get(stamp.attraction.content_id)) is not None:
                return existing, False
            hidden[stamp.attraction.content_id] = stamp
            payload = {k: v.model_dump(mode="json") for k, v in hidden.items()}
            self._hidden_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        return stamp, True

    # ---- 여행 점수 원장 (랭킹/마스터) ----
    def get_events(self) -> list[PointEvent]:
        if not self._events_path.exists():
            return []
        return [PointEvent.model_validate(e) for e in json.loads(self._events_path.read_text(encoding="utf-8"))]

    def add_event(self, event: PointEvent) -> None:
        with self._lock:
            events = self.get_events() + [event]
            payload = [e.model_dump(mode="json") for e in events]
            self._events_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    # ---- 코스 챌린지 ----
    def get_challenge(self) -> Challenge | None:
        if not self._challenge_path.exists():
            return None
        return Challenge.model_validate_json(self._challenge_path.read_text(encoding="utf-8"))

    def save_challenge(self, challenge: Challenge | None) -> None:
        with self._lock:
            if challenge is None:
                self._challenge_path.unlink(missing_ok=True)
            else:
                self._challenge_path.write_text(challenge.model_dump_json(indent=2), encoding="utf-8")

    # ---- 축제 한정 배지 ----
    def get_badges(self) -> list[LimitedBadge]:
        if not self._badges_path.exists():
            return []
        return [LimitedBadge.model_validate(b) for b in json.loads(self._badges_path.read_text(encoding="utf-8"))]

    def add_badge(self, badge: LimitedBadge) -> bool:
        with self._lock:
            badges = self.get_badges()
            if any(b.id == badge.id for b in badges):
                return False
            payload = [b.model_dump(mode="json") for b in badges + [badge]]
            self._badges_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        return True
