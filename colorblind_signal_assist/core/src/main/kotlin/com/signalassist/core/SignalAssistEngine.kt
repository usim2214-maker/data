package com.signalassist.core

data class EngineOutput(
    val timestampMs: Long,
    val detection: FrameDetection,
    val state: SignalState,
    val change: StateChange?,
    val vehicle: VehicleState,
    val alerts: List<Alert>,
)

/** 검출 -> 상태 안정화 -> 알림 정책 파이프라인. 프레임마다 [process] 를 호출한다. */
class SignalAssistEngine(
    private val detector: TrafficLightDetector = TrafficLightDetector(),
    private val tracker: SignalStateTracker = SignalStateTracker(),
    private val policy: AlertPolicy = AlertPolicy(),
) {
    fun process(frame: Frame, vehicle: VehicleState, timestampMs: Long): EngineOutput {
        val detection = detector.detect(frame)
        val change = tracker.update(detection)
        val alerts = policy.evaluate(tracker.state, change, vehicle, timestampMs)
        return EngineOutput(timestampMs, detection, tracker.state, change, vehicle, alerts)
    }

    fun reset() {
        tracker.reset()
        policy.reset()
    }
}
