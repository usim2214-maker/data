package ai.parkingguide.core

import org.opencv.core.Mat

/**
 * 한 프레임 처리 흐름 (Python parking_guide/pipeline.py 와 같은 구조).
 *
 *   카메라 프레임 ─▶ SlotDetector(인식) ─▶ SlotTracker(추적·검증) ─▶ computeAlignment(균형)
 *       ─▶ buildGuidance(안내) ─▶ GuidanceStabilizer(깜빡임 방지) ─▶ VoicePrompter / 비프음 ─▶ ParkingSession(기록)
 *
 * 차량 신호
 *  - steerWheelDeg: 핸들 회전각. Pleos SteeringWheel(앞바퀴 각도) × 조향비
 *  - dsCm: 지난 프레임 이후 후진한 거리 (AAOS PERF_VEHICLE_SPEED × 시간)
 */
class FrameOutput(
    val detection: DetectionResult,
    val slot: SlotEstimate?,
    val alignment: Alignment?,
    val guidance: Guidance,
    val speech: String?,
    val beepIntervalS: Double?,
    val rejected: Boolean,
)

class ParkingAssistant(camera: CameraModel, val vehicle: VehicleSpec, doorMode: DoorMode = DoorMode.CENTER) {
    val detector = SlotDetector(camera, vehicle).apply { this.doorMode = doorMode }
    private val tracker = SlotTracker()
    private var stabilizer = GuidanceStabilizer()
    private val voice = VoicePrompter()
    var session = ParkingSession()
        private set
    private var prevSteer: Double? = null

    /** 새 주차 시도 (R단 진입 시). */
    fun reset() {
        tracker.reset()
        stabilizer = GuidanceStabilizer()
        voice.reset()
        session = ParkingSession()
        prevSteer = null
    }

    /** 주차 완료 (R단 해제 시): 리포트. */
    fun finish(): ParkingReport? = session.finish()

    fun step(img: Mat, steerWheelDeg: Double?, dsCm: Double = 0.0, t: Double = 0.0): FrameOutput {
        val res = detector.analyze(img)
        // 지난 프레임 핸들 각도로 그동안의 이동을 예측
        val k = wheelToCurvature(prevSteer ?: 0.0, vehicle)
        val slot = tracker.update(res.slot, dsCm, k, -vehicle.rearOverhangCm)
        prevSteer = steerWheelDeg
        val align = slot?.let { computeAlignment(it, vehicle) }
        val g = stabilizer.update(buildGuidance(align, slot, vehicle, steerWheelDeg))
        val end = align?.endDistCm
        session.add(align, slot, g, steerWheelDeg, t, measured = res.slot)
        return FrameOutput(res, slot, align, g, voice.update(g, end, t), beepIntervalS(end), tracker.lastRejected)
    }
}
