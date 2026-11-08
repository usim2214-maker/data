"""도감(Collection) & 유저 설정 저장소. MVP 는 단일 사용자 JSON 파일."""
import json
import threading
from datetime import datetime, timezone
from pathlib import Path

from app.schemas import CollectionEntry, GeoPoint, Preferences, VehicleCard


class JsonStore:
    def __init__(self, data_dir: Path):
        self._lock = threading.Lock()
        self._collection_path = data_dir / "collection.json"
        self._prefs_path = data_dir / "preferences.json"
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

    def delete(self, card_id: str) -> bool:
        with self._lock:
            entries = self._load()
            if entries.pop(card_id, None) is None:
                return False
            self._dump(entries)
            return True
