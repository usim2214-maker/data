"""차량 제원 (사이드미러 제외 전폭, 전장).

값은 참고용 근사치다. 연식·트림에 따라 다르므로 실제 차량 제원을 확인해 바꿔 쓰거나
사용자가 직접 입력하도록 한다. Pleos Vehicle SDK의 CarInfo는 VIN만 주기 때문에,
실제 앱에서는 VIN → 차종 → 제원표 조회 또는 사용자 설정으로 채워야 한다.
"""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class VehicleSpec:
    name: str
    width_cm: float
    length_cm: float


PRESETS: dict[str, VehicleSpec] = {
    v.name: v
    for v in [
        VehicleSpec("casper", 159.5, 359.5),
        VehicleSpec("ray", 159.5, 359.5),
        VehicleSpec("avante", 182.5, 465.0),
        VehicleSpec("kona", 182.5, 435.0),
        VehicleSpec("sonata", 186.0, 491.0),
        VehicleSpec("k5", 186.0, 490.5),
        VehicleSpec("tucson", 186.5, 463.0),
        VehicleSpec("sportage", 186.5, 466.0),
        VehicleSpec("k8", 187.5, 501.5),
        VehicleSpec("grandeur", 188.0, 503.5),
        VehicleSpec("ioniq5", 189.0, 463.5),
        VehicleSpec("ev6", 189.0, 468.0),
        VehicleSpec("santafe", 190.0, 483.0),
        VehicleSpec("sorento", 190.0, 481.5),
        VehicleSpec("palisade", 197.5, 499.5),
        VehicleSpec("carnival", 199.5, 515.5),
        VehicleSpec("staria", 199.5, 525.5),
    ]
}

DEFAULT_VEHICLE = PRESETS["sonata"]


def get_vehicle(name_or_width: str) -> VehicleSpec:
    """프리셋 이름 또는 '폭x길이'(cm) 문자열, 예: '185x470'."""
    key = name_or_width.lower()
    if key in PRESETS:
        return PRESETS[key]
    if "x" in key:
        w, l = key.split("x", 1)
        return VehicleSpec("custom", float(w), float(l))
    raise ValueError(f"알 수 없는 차량: {name_or_width}. 프리셋: {', '.join(PRESETS)}")
