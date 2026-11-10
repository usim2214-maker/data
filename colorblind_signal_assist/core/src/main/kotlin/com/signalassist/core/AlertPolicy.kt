package com.signalassist.core

enum class AlertLevel { INFO, CAUTION, WARNING, URGENT }

/**
 * 색이 아니라 '소리 패턴'으로 신호를 구분하도록 알림음마다 고유한 리듬을 준다.
 * (적록색맹 운전자는 색 대신 소리·문구·모양으로 판단)
 */
enum class TonePattern {
    /** 낮은음 -> 높은음 두 번: 진행 */
    RISING_DOUBLE,
    /** 가운데 음 한 번 길게: 주의 */
    SINGLE_LONG,
    /** 높은음 -> 낮은음 세 번: 정지 */
    FALLING_TRIPLE,
    /** 빠른 연속 경고음: 긴급 */
    RAPID_BEEPS,
    /** 짧은 차임: 안내 */
    SOFT_CHIME,
}

enum class AlertType(
    val level: AlertLevel,
    val tone: TonePattern,
    val messageKo: String,
) {
    GREEN_GO(AlertLevel.INFO, TonePattern.RISING_DOUBLE, "초록불로 바뀌었습니다."),
    GREEN_REMINDER(AlertLevel.CAUTION, TonePattern.RISING_DOUBLE, "초록불입니다. 주변을 확인하고 출발하세요."),
    LEFT_ARROW_ON(AlertLevel.INFO, TonePattern.SOFT_CHIME, "좌회전 신호가 켜졌습니다."),
    YELLOW_CAUTION(AlertLevel.CAUTION, TonePattern.SINGLE_LONG, "황색 신호입니다. 정지를 준비하세요."),
    RED_STOP(AlertLevel.WARNING, TonePattern.FALLING_TRIPLE, "적색 신호입니다. 정지하세요."),
    RED_URGENT(AlertLevel.URGENT, TonePattern.RAPID_BEEPS, "적색 신호! 즉시 감속하세요."),
}

data class Alert(val type: AlertType, val timestampMs: Long) {
    val level get() = type.level
    val message get() = type.messageKo
    val tone get() = type.tone
}

data class AlertPolicyConfig(
    /** 이 속도 미만이면 정차로 본다 */
    val stoppedSpeedKmh: Float = 3f,
    /** 적색인데 이 속도 이상이면 긴급 경고 */
    val urgentSpeedKmh: Float = 25f,
    /** 이 이상 감속 중이면(km/h/s) 운전자가 이미 반응한 것으로 보고 반복 경고를 멈춤 */
    val brakingDecelKmhPerS: Float = 6f,
    /** 초록불로 바뀐 뒤 이 시간 동안 출발하지 않으면 한 번 더 알려줌 */
    val greenReminderDelayMs: Long = 3_000,
    /** 긴급 경고 반복 간격 */
    val urgentRepeatMs: Long = 3_000,
    /** 같은 종류 알림 최소 간격 (신호 깜빡임에 의한 중복 방지) */
    val sameAlertCooldownMs: Long = 2_500,
)

/**
 * 신호 상태 + 차량 상태(속도·기어)를 보고 "지금 운전자에게 알려야 하는가"를 결정한다.
 * 핵심 원칙: 불필요한 알림은 줄이고(정차 중 적색은 조용히 표시만), 위험할 때만 강하게.
 */
class AlertPolicy(private val config: AlertPolicyConfig = AlertPolicyConfig()) {

    private val lastFired = HashMap<AlertType, Long>()
    private var greenSinceMs: Long? = null
    private var greenReminded = false
    private var prevSpeed: Float? = null
    private var prevTimeMs: Long? = null
    private var decelKmhPerS = 0f

    fun reset() {
        lastFired.clear(); greenSinceMs = null; greenReminded = false
        prevSpeed = null; prevTimeMs = null; decelKmhPerS = 0f
    }

    fun evaluate(state: SignalState, change: StateChange?, vehicle: VehicleState, nowMs: Long): List<Alert> {
        updateDecel(vehicle, nowMs)
        if (vehicle.gear == Gear.PARK || vehicle.gear == Gear.REVERSE) {
            greenSinceMs = null
            return emptyList()
        }

        val out = ArrayList<Alert>(2)
        val stopped = vehicle.speedKmh < config.stoppedSpeedKmh
        val braking = decelKmhPerS >= config.brakingDecelKmhPerS || vehicle.brakePedal == true

        if (change != null && change.from.color != change.to.color) {
            when (change.to.color) {
                SignalColor.GREEN -> {
                    greenSinceMs = nowMs
                    greenReminded = false
                    val fromStop = change.from.color == SignalColor.RED || change.from.color == SignalColor.YELLOW
                    if (fromStop && stopped) fire(AlertType.GREEN_GO, nowMs, out)
                }
                SignalColor.YELLOW -> if (!stopped) fire(AlertType.YELLOW_CAUTION, nowMs, out)
                SignalColor.RED -> when {
                    vehicle.speedKmh >= config.urgentSpeedKmh && !braking -> fire(AlertType.RED_URGENT, nowMs, out)
                    !stopped -> fire(AlertType.RED_STOP, nowMs, out)
                }
                SignalColor.UNKNOWN -> Unit
            }
        }
        if (state.color != SignalColor.GREEN) greenSinceMs = null

        if (change != null && !change.from.leftTurnArrow && change.to.leftTurnArrow && stopped) {
            fire(AlertType.LEFT_ARROW_ON, nowMs, out)
        }

        // 적색인데 빠른 속도로 계속 접근 중 -> 반복 긴급 경고
        if (state.color == SignalColor.RED && vehicle.speedKmh >= config.urgentSpeedKmh && !braking) {
            val last = lastFired[AlertType.RED_URGENT]
            if (last == null || nowMs - last >= config.urgentRepeatMs) fire(AlertType.RED_URGENT, nowMs, out)
        }

        // 초록불인데 출발하지 않음 -> 한 번만 리마인드
        val since = greenSinceMs
        if (state.color == SignalColor.GREEN && since != null && !greenReminded && stopped &&
            nowMs - since >= config.greenReminderDelayMs
        ) {
            greenReminded = true
            fire(AlertType.GREEN_REMINDER, nowMs, out)
        }
        if (!stopped) greenReminded = true

        return out
    }

    private fun fire(type: AlertType, nowMs: Long, out: MutableList<Alert>) {
        val last = lastFired[type]
        val cooldown = if (type == AlertType.RED_URGENT) config.urgentRepeatMs else config.sameAlertCooldownMs
        if (last != null && nowMs - last < cooldown) return
        if (out.any { it.type == type }) return
        lastFired[type] = nowMs
        out += Alert(type, nowMs)
    }

    private fun updateDecel(vehicle: VehicleState, nowMs: Long) {
        val ps = prevSpeed
        val pt = prevTimeMs
        if (ps != null && pt != null && nowMs > pt) {
            val instant = (ps - vehicle.speedKmh) / ((nowMs - pt) / 1000f)
            decelKmhPerS = 0.6f * decelKmhPerS + 0.4f * instant // 지수평활
        }
        prevSpeed = vehicle.speedKmh
        prevTimeMs = nowMs
    }
}
