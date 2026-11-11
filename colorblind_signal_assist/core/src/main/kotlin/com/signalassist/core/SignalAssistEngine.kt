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
        reactionTimeS: Float? = null,
    ): EngineOutput {
        val detection = detector.detect(frame)
        val change = tracker.update(detection)
        val d = distance.update(detection, frame.width, tracker.state.color)
        val alerts = policy.evaluate(tracker.state, change, vehicle, timestampMs, navigation, d, reactionTimeS)
        return EngineOutput(timestampMs, detection, tracker.state, change, vehicle, alerts, d)
    }

    val redUrgentActive get() = policy.redUrgentActive
    val signalLostActive get() = policy.signalLostActive

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
    /** 실내 마이크로 긴급차량 사이렌 감지 ([SirenDetector]). 모르면 null */
    val sirenNearby: Boolean? = null,
)

data class DrivingOutput(
    val input: DrivingInput,
    /** 신호 인식 결과 (프로필에서 꺼져 있거나 영상이 없으면 null) */
    val signal: EngineOutput?,
    val alerts: List<Alert>,
    /** 중재를 거친 실제 전달 목록 (알림마다 소리·음성·깜빡임 여부) */
    val deliveries: List<Delivery> = emptyList(),
    /**
     * 지금 계속되고 있는 위험. 깜빡임은 1~2초 만에 끝나므로, 도로를 보느라 놓친 운전자(특히 청각장애)가
     * 나중에 화면을 봐도 알 수 있도록 상황이 끝날 때까지 화면에 남겨 둔다.
     */
    val activeHazards: List<AlertType> = emptyList(),
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
    private val sirenCooldownMs: Long = 20_000,
    val frameScheduler: FrameScheduler = FrameScheduler(),
    /** 운전하며 익히기 횟수 (앱이 저장·복원) */
    val hints: HintTracker = HintTracker(),
) {
    private var lastSignal: EngineOutput? = null

    private var sirenWasOn = false
    private var lastSirenAlertMs: Long? = null

    fun process(input: DrivingInput): DrivingOutput {
        val p = profile
        // 영상 처리는 필요할 때만 (FrameScheduler). 건너뛴 프레임에서는 직전 신호 상태를 그대로 보여준다
        val fresh = if (p.signalAssist && input.frame != null &&
            frameScheduler.shouldProcess(input.timestampMs, input.vehicle, input.navigation)
        ) {
            signalEngine.process(input.frame, input.vehicle, input.timestampMs, input.navigation, p.reactionTimeS)
                .also { frameScheduler.observe(input.timestampMs, it); lastSignal = it }
        } else {
            null
        }
        val signal = fresh ?: lastSignal?.takeIf { p.signalAssist }?.copy(change = null, alerts = emptyList())
        val alerts = ArrayList<Alert>()
        fresh?.alerts?.filterTo(alerts) { p.accepts(it) }
        alerts += vehiclePolicy.evaluate(input.cabin, input.vehicle, input.timestampMs, input.navigation, p.vehicleFeatures)
        if (p.navigationCards) alerts += navigationPolicy.evaluate(input.navigation, input.timestampMs)
        // 사이렌: 들리기 시작한 순간 한 번 (청각장애 프로필). 계속 들리면 20초마다
        val siren = input.sirenNearby == true
        if (p.sirenAlerts && siren) {
            val last = lastSirenAlertMs
            if (!sirenWasOn || (last != null && input.timestampMs - last >= sirenCooldownMs)) {
                lastSirenAlertMs = input.timestampMs
                alerts += Alert(AlertType.SIREN_NEARBY, input.timestampMs)
            }
        }
        sirenWasOn = siren
        // 사용자 설정(알림 양, 끈 알림)과 프로필에 맞지 않는 알림은 뺀다
        alerts.retainAll { p.accepts(it) }
        alerts.sortByDescending { it.level }
        val deliveries = hints.decorate(arbiter.arbitrate(alerts, p, input.timestampMs, input.cabin.isDark), p.prefs)
        return DrivingOutput(input, signal, alerts, deliveries, activeHazards(input, p))
    }

    private fun activeHazards(input: DrivingInput, p: DriverProfile): List<AlertType> = buildList {
        val speed = input.vehicle.speedKmh
        val c = input.cabin
        if (p.signalAssist && signalEngine.redUrgentActive && p.accepts(Alert(AlertType.RED_URGENT, 0))) add(AlertType.RED_URGENT)
        if (VehicleFeature.WARNINGS in p.vehicleFeatures) {
            if (c.doorOpen == true && speed >= 5f) add(AlertType.DOOR_OPEN_MOVING)
            if ((c.seatbeltWarning == true || c.driverSeatbeltBuckled == false) && speed >= 10f) add(AlertType.SEATBELT_UNBUCKLED)
        }
        if (p.sirenAlerts && input.sirenNearby == true) add(AlertType.SIREN_NEARBY)
        if (VehicleFeature.TURN_SIGNAL in p.vehicleFeatures && vehiclePolicy.turnSignalForgotten) add(AlertType.TURN_SIGNAL_LEFT_ON)
        if (p.has(DriverNeed.COLOR_VISION) && signalEngine.signalLostActive) add(AlertType.SIGNAL_LOST)
    }.sortedByDescending { it.level }

    fun reset() {
        signalEngine.reset()
        vehiclePolicy.reset()
        navigationPolicy.reset()
        arbiter.reset()
        frameScheduler.reset()
        lastSignal = null
        sirenWasOn = false
        lastSirenAlertMs = null
    }
}
