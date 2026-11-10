"""한 프레임 처리 흐름을 묶은 객체. Kotlin(Pleos 앱)으로 옮길 때 이 구조를 그대로 따르면 된다.

  카메라 프레임 ─▶ SlotDetector(인식) ─▶ SlotTracker(추적·검증) ─▶ compute_alignment(균형)
      ─▶ build_guidance(안내) ─▶ GuidanceStabilizer(깜빡임 방지) ─▶ VoicePrompter / 비프음

차량 신호 (Pleos Vehicle SDK)
  - steer_deg: SteeringWheel.getCurrentSteeringWheelAngle
  - ds_cm: 지난 프레임 이후 후진한 거리 (속도 × 시간)
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .camera import CameraModel
from .detector import Alignment, DetectionResult, SlotDetector, SlotEstimate, SlotTracker, compute_alignment
from .guidance import Guidance, GuidanceStabilizer, build_guidance, wheel_to_curvature
from .vehicles import VehicleSpec
from .report import ParkingReport, ParkingSession
from .voice import VoicePrompter, beep_interval_s


@dataclass
class FrameOutput:
    detection: DetectionResult
    slot: SlotEstimate | None
    alignment: Alignment | None
    guidance: Guidance
    speech: str | None
    beep_interval_s: float | None
    rejected: bool  # 이번 검출을 이상값으로 무시했는지


class ParkingAssistant:
    def __init__(self, camera: CameraModel, vehicle: VehicleSpec, door_mode: str = "center") -> None:
        self.vehicle = vehicle
        self.detector = SlotDetector(camera, vehicle=vehicle)
        self.detector.door_mode = door_mode
        self.tracker = SlotTracker()
        self.stabilizer = GuidanceStabilizer()
        self.voice = VoicePrompter()
        self.session = ParkingSession()
        self.prev_steer: float | None = None

    def reset(self) -> None:
        """새 주차 시도 (R단 진입 시)."""
        self.tracker.reset()
        self.stabilizer = GuidanceStabilizer()
        self.voice.reset()
        self.session = ParkingSession()

    def finish(self) -> ParkingReport | None:
        """주차 완료 (P단 또는 R단 해제 시): 리포트를 만든다."""
        return self.session.finish()

    def step(self, img: np.ndarray, steer_deg: float | None = None, ds_cm: float = 0.0,
             t: float = 0.0) -> FrameOutput:
        res = self.detector.analyze(img)
        # 지난 프레임 핸들 각도로 그동안의 이동을 예측
        k = wheel_to_curvature(self.prev_steer or 0.0, self.vehicle)
        slot = self.tracker.update(res.slot, ds_cm, k, -self.vehicle.rear_overhang_cm)
        self.prev_steer = steer_deg
        align = compute_alignment(slot, self.vehicle, self.detector.thr) if slot is not None else None
        g = self.stabilizer.update(build_guidance(align, slot, self.vehicle, steer_deg))
        end = align.end_dist_cm if align else None
        self.session.add(align, slot, g, steer_deg, t, measured=res.slot)
        return FrameOutput(res, slot, align, g, self.voice.update(g, end, t), beep_interval_s(end),
                           self.tracker.last_rejected)
