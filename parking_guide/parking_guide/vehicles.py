"""차량 제원 (사이드미러 제외 전폭, 전장, 휠베이스).

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
    wheelbase_cm: float = 0.0  # 0이면 전장으로 추정
    steering_ratio: float = 14.0  # 핸들 각도 / 바퀴 각도
    max_wheel_angle_deg: float = 35.0  # 앞바퀴 최대 조향각

    @property
    def wheelbase(self) -> float:
        return self.wheelbase_cm or self.length_cm * 0.59

    @property
    def rear_overhang_cm(self) -> float:
        """뒷범퍼에서 뒷바퀴 축까지 거리 (앞뒤 오버행을 약 47:53으로 가정)."""
        return (self.length_cm - self.wheelbase) * 0.53

    @property
    def max_steer_wheel_deg(self) -> float:
        return self.max_wheel_angle_deg * self.steering_ratio


PRESETS: dict[str, VehicleSpec] = {
    v.name: v
    for v in [
        VehicleSpec("casper", 159.5, 359.5, 240.0),
        VehicleSpec("ray", 159.5, 359.5, 252.0),
        VehicleSpec("avante", 182.5, 465.0, 272.0),
        VehicleSpec("kona", 182.5, 435.0, 266.0),
        VehicleSpec("sonata", 186.0, 491.0, 284.0),
        VehicleSpec("k5", 186.0, 490.5, 285.0),
        VehicleSpec("tucson", 186.5, 463.0, 275.5),
        VehicleSpec("sportage", 186.5, 466.0, 275.5),
        VehicleSpec("k8", 187.5, 501.5, 289.5),
        VehicleSpec("grandeur", 188.0, 503.5, 289.5),
        VehicleSpec("ioniq5", 189.0, 463.5, 300.0),
        VehicleSpec("ev6", 189.0, 468.0, 290.0),
        VehicleSpec("santafe", 190.0, 483.0, 281.5),
        VehicleSpec("sorento", 190.0, 481.5, 281.5),
        VehicleSpec("palisade", 197.5, 499.5, 290.0),
        VehicleSpec("carnival", 199.5, 515.5, 309.0),
        VehicleSpec("staria", 199.5, 525.5, 327.5),
    ]
}

DEFAULT_VEHICLE = PRESETS["sonata"]


def get_vehicle(name_or_size: str) -> VehicleSpec:
    """프리셋 이름 또는 '폭x길이'(cm) 문자열, 예: '185x470'."""
    key = name_or_size.lower()
    if key in PRESETS:
        return PRESETS[key]
    if "x" in key:
        w, l = key.split("x", 1)
        return VehicleSpec("custom", float(w), float(l))
    raise ValueError(f"알 수 없는 차량: {name_or_size}. 프리셋: {', '.join(PRESETS)}")
