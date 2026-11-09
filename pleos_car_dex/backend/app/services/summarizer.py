"""카드 뒷면 정보: AI 가 아니라 차량 정보 목록(vehicle_catalog.json)에서 사용자가 고른 항목을 그대로 꺼낸다.

- 사실 확인된 목록 값만 보여주므로 수치를 지어낼 위험이 없고, API 비용·지연도 없다.
- 목록(28종)에 없는 차량은 카드를 만들지 않는다 (도감 대상 아님).
"""
from app.config import Settings
from app.schemas import CardSummary, KeywordFact, VehicleIdentity
from app.services.catalog import available_fields, find_vehicle


class CardSummarizer:
    def __init__(self, settings: Settings):
        self.settings = settings

    def summarize(self, identity: VehicleIdentity, keywords: list[str], language: str = "ko") -> CardSummary | None:
        """목록에 있는 차량이면 고른 항목 순서대로 정보를 꺼내고, 없으면 None"""
        ref = find_vehicle(identity.brand, identity.model)
        if ref is None:
            return None
        fields = available_fields()
        chosen = [k for k in keywords if k in fields] or fields[:4]
        return CardSummary(
            one_liner=ref.get("one_liner", f"{ref['brand']} {ref['model']}"),
            facts=[KeywordFact(keyword=k, value=ref["facts"].get(k, "정보 없음")) for k in chosen],
        )
