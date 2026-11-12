package com.signalassist.core

data class VehicleAlertConfig(
    val movingSpeedKmh: Float = 10f,
    val seatbeltRepeatMs: Long = 15_000,
    val doorRepeatMs: Long = 5_000,
    /** 방향지시등이 이 시간 넘게 켜져 있으면 '깜빡함'으로 본다 */
    val turnSignalForgottenMs: Long = 20_000,
    val turnSignalMinSpeedKmh: Float = 30f,
    /** 내비 안내 지점이 이 거리 안이면 방향지시등은 의도한 것으로 본다 */
    val turnSignalNavGraceM: Float = 300f,
    val headlightRepeatMs: Long = 60_000,
)

/**
 * 차량이 '소리'나 '작은 경고등'으로만 알려주는 상태를 다른 감각으로 다시 알려준다.
 * - 청각장애: 벨트 경고음, 방향지시등 똑딱 소리를 못 들음 -> 화면 깜빡임 + 큰 글씨
 * - 저시력: 계기판 경고등이 작아 놓침 -> 음성
 * - 주의력·고령: 방향지시등 깜빡함, 뒷좌석 확인 -> 한 번 더 상기
 */
class VehicleAlertPolicy(private val config: VehicleAlertConfig = VehicleAlertConfig()) {

    private val cooldowns = Cooldowns()
    private var tireAnnounced = false
    private var headlightAnnounced = false
    private var turnSignalOnSince: Long? = null
    private var turnSignalAnnounced = false

    /** 방향지시등 '깜빡함' 알림 후에도 여전히 켜져 있는지 (화면 지속 표시용) */
    val turnSignalForgotten get() = turnSignalAnnounced && turnSignalOnSince != null
    private var rearSeenThisTrip = false

    fun reset() {
        cooldowns.clear(); tireAnnounced = false; headlightAnnounced = false
        turnSignalOnSince = null; turnSignalAnnounced = false; rearSeenThisTrip = false
    }

    fun evaluate(
        cabin: CabinState,
        vehicle: VehicleState,
        nowMs: Long,
        navigation: NavigationState? = null,
        features: Set<VehicleFeature> = VehicleFeature.entries.toSet(),
    ): List<Alert> {
        val out = ArrayList<Alert>(2)
        val speed = if (vehicle.hasValidSpeed) vehicle.speedKmh else Float.NaN
        val moving = speed >= config.movingSpeedKmh

        if (VehicleFeature.WARNINGS in features) {
            val unbuckled = cabin.seatbeltWarning == true || cabin.driverSeatbeltBuckled == false
            if (unbuckled && moving) fire(AlertType.SEATBELT_UNBUCKLED, nowMs, config.seatbeltRepeatMs, out)

            if (cabin.doorOpen == true && speed >= 5f) fire(AlertType.DOOR_OPEN_MOVING, nowMs, config.doorRepeatMs, out)

            when (cabin.lowTirePressure) {
                true -> if (!tireAnnounced) { tireAnnounced = true; fire(AlertType.LOW_TIRE_PRESSURE, nowMs, 0, out) }
                false -> tireAnnounced = false
                null -> Unit
            }

            val lightsNeeded = cabin.isDark == true && cabin.headlightsOn == false && moving
            if (lightsNeeded && !headlightAnnounced) {
                headlightAnnounced = true
                fire(AlertType.HEADLIGHTS_OFF_DARK, nowMs, config.headlightRepeatMs, out)
            }
            if (cabin.headlightsOn == true) headlightAnnounced = false
        }

        if (VehicleFeature.TURN_SIGNAL in features) {
            val signal = cabin.turnSignal
            if (signal == TurnSignal.LEFT || signal == TurnSignal.RIGHT) {
                val since = turnSignalOnSince ?: nowMs.also { turnSignalOnSince = it }
                val navNear = navigation?.guiding == true &&
                    (navigation.distanceToManeuverM ?: Float.MAX_VALUE) <= config.turnSignalNavGraceM
                if (!turnSignalAnnounced && !navNear && speed >= config.turnSignalMinSpeedKmh &&
                    nowMs - since >= config.turnSignalForgottenMs
                ) {
                    turnSignalAnnounced = true
                    fire(AlertType.TURN_SIGNAL_LEFT_ON, nowMs, 0, out)
                }
            } else if (signal != null) {
                turnSignalOnSince = null
                turnSignalAnnounced = false
            }
        }

        if (VehicleFeature.REAR_SEAT in features) {
            if (cabin.rearSeatOccupied == true && speed > 5f) rearSeenThisTrip = true
            val tripEnding = speed < 1f && (vehicle.gear == Gear.PARK || cabin.doorOpen == true)
            if (tripEnding && rearSeenThisTrip) {
                rearSeenThisTrip = false
                fire(AlertType.REAR_SEAT_CHECK, nowMs, 0, out)
            }
        }
        return out
    }

    private fun fire(type: AlertType, nowMs: Long, intervalMs: Long, out: MutableList<Alert>) {
        if (!cooldowns.ready(type, nowMs, intervalMs)) return
        cooldowns.mark(type, nowMs)
        out += Alert(type, nowMs)
    }
}

enum class VehicleFeature { WARNINGS, TURN_SIGNAL, REAR_SEAT }
