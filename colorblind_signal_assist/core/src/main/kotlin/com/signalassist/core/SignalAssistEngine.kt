package com.signalassist.core

data class EngineOutput(
    val timestampMs: Long,
    val detection: FrameDetection,
    val state: SignalState,
    val change: StateChange?,
    val vehicle: VehicleState,
    val alerts: List<Alert>,
    /** 신호등까지 추정 거리(m). 모르면 null */
    val distanceM: Float? = null,
)

/** 신호 인식 파이프라인: 검출 -> 상태 안정화 -> 신호 알림 정책 */
class SignalAssistEngine(
    private val detector: TrafficLightDetector = TrafficLightDetector(),
    private val tracker: SignalStateTracker = SignalStateTracker(),
    private val policy: SignalAlertPolicy = SignalAlertPolicy(),
    private val distance: DistanceEstimator = DistanceEstimator(),
) {
    fun process(
        frame: Frame,
        vehicle: VehicleState,
        timestampMs: Long,
        navigation: NavigationState? = null,
    ): EngineOutput {
        val detection = detector.detect(frame)
        val change = tracker.update(detection)
        val d = distance.update(detection, frame.width, tracker.state.color)
        val alerts = policy.evaluate(tracker.state, change, vehicle, timestampMs, navigation, d)
        return EngineOutput(timestampMs, detection, tracker.state, change, vehicle, alerts, d)
    }

    fun reset() {
        tracker.reset()
        policy.reset()
        distance.reset()
    }
}

/** 한 시점의 입력 */
data class DrivingInput(
    val timestampMs: Long,
    val vehicle: VehicleState,
    /** 카메라/합성 영상. 없으면 신호 인식은 건너뜀 */
    val frame: Frame? = null,
    val cabin: CabinState = CabinState(),
    val navigation: NavigationState? = null,
)

data class DrivingOutput(
    val input: DrivingInput,
    /** 신호 인식 결과 (프로필에서 꺼져 있거나 영상이 없으면 null) */
    val signal: EngineOutput?,
    val alerts: List<Alert>,
    /** 중재를 거친 실제 전달 목록 (알림마다 소리·음성·깜빡임 여부) */
    val deliveries: List<Delivery> = emptyList(),
) {
    val signalState: SignalState get() = signal?.state ?: SignalState.UNKNOWN
}

/**
 * 전체 엔진: 신호 + 차량 상태 + 내비 알림을 프로필에 맞춰 합친다.
 */
class DrivingAssistEngine(
    var profile: DriverProfile = DriverProfile.COLOR_VISION_ONLY,
    private val signalEngine: SignalAssistEngine = SignalAssistEngine(),
    private val vehiclePolicy: VehicleAlertPolicy = VehicleAlertPolicy(),
    private val navigationPolicy: NavigationAlertPolicy = NavigationAlertPolicy(),
    private val arbiter: AlertArbiter = AlertArbiter(),
) {
    fun process(input: DrivingInput): DrivingOutput {
        val p = profile
        val signal = if (p.signalAssist && input.frame != null) {
            signalEngine.process(input.frame, input.vehicle, input.timestampMs, input.navigation)
        } else {
            null
        }
        val alerts = ArrayList<Alert>()
        signal?.alerts?.filterTo(alerts) { p.accepts(it) }
        alerts += vehiclePolicy.evaluate(input.cabin, input.vehicle, input.timestampMs, input.navigation, p.vehicleFeatures)
        if (p.navigationCards) alerts += navigationPolicy.evaluate(input.navigation, input.timestampMs)
        alerts.sortByDescending { it.level }
        val deliveries = arbiter.arbitrate(alerts, p, input.timestampMs, input.cabin.isDark)
        return DrivingOutput(input, signal, alerts, deliveries)
    }

    fun reset() {
        signalEngine.reset()
        vehiclePolicy.reset()
        navigationPolicy.reset()
        arbiter.reset()
    }
}
