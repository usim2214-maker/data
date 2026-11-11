package com.signalassist.core

data class AlertPolicyConfig(
    /** 이 속도 미만이면 정차로 본다 */
    val stoppedSpeedKmh: Float = 3f,
    /** 거리를 모를 때: 적색인데 이 속도 이상이면 긴급 경고 */
    val urgentSpeedKmh: Float = 25f,
    /**
     * 거리를 알 때: 정지선에 서려면 필요한 감속도(m/s²)가 이 값 이상이면 긴급 경고.
     * 일반적인 편안한 제동이 약 2~3 m/s² 이므로, 이 이상이 필요하면 '지금 밟아야 하는' 상황이다.
     */
    val urgentDecelMps2: Float = 2.5f,
    /** 황색에서 이 감속도(m/s²) 이하로 설 수 있으면 '정지 준비', 아니면 딜레마 구간으로 본다 */
    val comfortableStopDecelMps2: Float = 3.0f,
    /** 운전자 반응 시간(s). 알림을 듣고 브레이크를 밟기까지 */
    val reactionTimeS: Float = 1.0f,
    /** 국내 신호등은 대부분 교차로 건너편에 있어, 정지선은 신호등보다 이만큼 앞에 있다고 본다 */
    val stopLineOffsetM: Float = 12f,
    /** 이 이상 감속 중이면(km/h/s) 운전자가 이미 반응한 것으로 보고 반복 경고를 멈춤 */
    val brakingDecelKmhPerS: Float = 4f,
    /** 초록불로 바뀐 뒤 이 시간 동안 출발하지 않으면 한 번 더 알려줌 */
    val greenReminderDelayMs: Long = 3_000,
    /** 긴급 경고 반복 간격 */
    val urgentRepeatMs: Long = 3_000,
    /** 같은 종류 알림 최소 간격 (신호 깜빡임에 의한 중복 방지) */
    val sameAlertCooldownMs: Long = 2_500,
    /** 정차 중 신호를 이 시간 이상 못 보면 '직접 확인' 안내 */
    val signalLostAfterMs: Long = 2_000,
    /** 최근 이 시간 안에 신호를 봤을 때만 (교차로 접근 중이었을 때만) 안내 */
    val signalRecentlySeenMs: Long = 20_000,
    /** 내비 다음 안내 지점이 이 거리 안이면 '이번 교차로의 경로'로 본다 */
    val routeLookaheadM: Float = 250f,
)

/**
 * 신호 상태 + 차량 상태(속도·기어) + 내비 경로를 보고 "지금 운전자에게 알려야 하는가"를 결정한다.
 * 핵심 원칙: 불필요한 알림은 줄이고(정차 중 적색은 조용히 표시만), 위험할 때만 강하게.
 */
class SignalAlertPolicy(private val config: AlertPolicyConfig = AlertPolicyConfig()) {

    private val cooldowns = Cooldowns()
    private var greenSinceMs: Long? = null
    private var greenReminded = false
    private var prevSpeed: Float? = null
    private var prevTimeMs: Long? = null
    private var decelKmhPerS = 0f
    private var lastKnownMs: Long? = null
    private var unknownSinceMs: Long? = null
    private var lostAnnounced = false

    /** 지금 '적색 + 제동 필요' 상황이 계속되는 중인지 (화면 지속 표시용) */
    var redUrgentActive = false
        private set

    /** 정차 중 신호등을 놓친 상태가 계속되는 중인지 (화면 지속 표시용) */
    var signalLostActive = false
        private set

    fun reset() {
        cooldowns.clear(); greenSinceMs = null; greenReminded = false
        prevSpeed = null; prevTimeMs = null; decelKmhPerS = 0f
        lastKnownMs = null; unknownSinceMs = null; lostAnnounced = false
        redUrgentActive = false; signalLostActive = false
    }

    fun evaluate(
        state: SignalState,
        change: StateChange?,
        vehicle: VehicleState,
        nowMs: Long,
        navigation: NavigationState? = null,
        /** 신호등까지 추정 거리 (등화 크기 기반). null 이면 차속만으로 판단 */
        distanceToLightM: Float? = null,
        /** 운전자 반응 시간(초). 프로필마다 다름 ([DriverProfile.reactionTimeS]). null 이면 설정값 */
        reactionTimeS: Float? = null,
    ): List<Alert> {
        val tr = reactionTimeS ?: config.reactionTimeS
        updateDecel(vehicle, nowMs)
        // 기어를 읽을 수 있을 때만 P/R 에서 끈다. (Pleos Vehicle State 에는 기어가 없어 UNKNOWN 일 수 있음)
        if (vehicle.gear == Gear.PARK || vehicle.gear == Gear.REVERSE) {
            greenSinceMs = null
            redUrgentActive = false
            return emptyList()
        }

        val out = ArrayList<Alert>(2)
        val stopped = vehicle.speedKmh < config.stoppedSpeedKmh
        val braking = decelKmhPerS >= config.brakingDecelKmhPerS || vehicle.brakePedal == true
        val required = requiredDecelMps2(vehicle.speedKmh, distanceToLightM, tr)
        // 적색에서 '알림을 듣고 반응한 뒤에도 강하게 제동해야 하는가': 거리를 알면 필요한 감속도로, 모르면 차속으로
        // 이미 정지선을 지나 교차로 안에 있으면(신호등까지 거리가 정지선 간격보다 짧음) 적색 경고는 오히려 위험
        val pastStopLine = distanceToLightM != null && distanceToLightM < config.stopLineOffsetM - 2f
        val redUrgent = !braking && !stopped && !pastStopLine && if (required != null) {
            required >= config.urgentDecelMps2
        } else {
            vehicle.speedKmh >= config.urgentSpeedKmh
        }
        redUrgentActive = state.color == SignalColor.RED && redUrgent

        if (change != null && change.from.color != change.to.color) {
            when (change.to.color) {
                SignalColor.GREEN -> {
                    greenSinceMs = nowMs
                    greenReminded = false
                    val fromStop = change.from.color == SignalColor.RED || change.from.color == SignalColor.YELLOW
                    if (fromStop && stopped) fire(AlertType.GREEN_GO, nowMs, out)
                }
                SignalColor.YELLOW -> if (!stopped) {
                    val stoppable = canStopComfortably(vehicle.speedKmh, distanceToLightM, tr)
                    fire(if (stoppable == false) AlertType.YELLOW_DILEMMA else AlertType.YELLOW_CAUTION, nowMs, out)
                }
                SignalColor.RED -> when {
                    redUrgent -> fire(AlertType.RED_URGENT, nowMs, out)
                    !stopped && !pastStopLine -> fire(AlertType.RED_STOP, nowMs, out)
                }
                SignalColor.UNKNOWN -> Unit
            }
        }
        if (state.color != SignalColor.GREEN) greenSinceMs = null

        // 좌회전 화살표: 내비 경로를 알면 '내 경로에 해당하는 신호인지'까지 알려준다
        if (change != null && !change.from.leftTurnArrow && change.to.leftTurnArrow && stopped) {
            val planned = plannedManeuver(navigation)
            val type = when (planned) {
                Maneuver.LEFT, Maneuver.UTURN -> AlertType.LEFT_ARROW_FOR_ROUTE
                Maneuver.STRAIGHT, Maneuver.RIGHT -> AlertType.LEFT_ARROW_NOT_FOR_ROUTE
                else -> AlertType.LEFT_ARROW_ON
            }
            fire(type, nowMs, out)
        }

        // 적색인데 제동 없이 계속 접근 중 -> 반복 긴급 경고
        if (state.color == SignalColor.RED && redUrgent) {
            fire(AlertType.RED_URGENT, nowMs, out)
        }

        // 정차했는데 접근할 때 보이던 신호등이 사라짐 (정지선 바로 앞이라 화각을 벗어남 등) -> 직접 확인 안내
        if (state.color != SignalColor.UNKNOWN) {
            lastKnownMs = nowMs; unknownSinceMs = null
        } else if (unknownSinceMs == null) {
            unknownSinceMs = nowMs
        }
        val lostFor = unknownSinceMs?.let { nowMs - it } ?: 0L
        val seenRecently = lastKnownMs?.let { nowMs - it <= config.signalRecentlySeenMs } ?: false
        if (stopped && !lostAnnounced && seenRecently && lostFor >= config.signalLostAfterMs) {
            lostAnnounced = true
            fire(AlertType.SIGNAL_LOST, nowMs, out)
        }
        if (vehicle.speedKmh >= 10f) lostAnnounced = false
        signalLostActive = lostAnnounced && state.color == SignalColor.UNKNOWN && stopped

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

    /**
     * 알림 후 반응 시간 [reactionS] 동안 그대로 달린 뒤, 정지선에 서기 위해 필요한 감속도 v²/(2d).
     * 반응할 여유조차 없으면 무한대. 거리를 모르면 null.
     */
    private fun requiredDecelMps2(speedKmh: Float, distanceToLightM: Float?, reactionS: Float): Float? {
        val d = distanceToLightM ?: return null
        val v = speedKmh / 3.6f
        val room = d - config.stopLineOffsetM - v * reactionS
        if (room <= 0.5f) return Float.POSITIVE_INFINITY
        return v * v / (2f * room)
    }

    /**
     * 황색 딜레마 구간 판단: 반응 시간 동안 달리는 거리를 빼고도 편안한 감속으로 정지선에 설 수 있는가.
     * 서기 어려운데 "정지하세요"라고 하면 급정거로 뒤차 추돌을 부를 수 있다. 거리를 모르면 null.
     */
    private fun canStopComfortably(speedKmh: Float, distanceToLightM: Float?, reactionS: Float): Boolean? {
        val d = distanceToLightM ?: return null
        val v = speedKmh / 3.6f
        val brakingRoom = d - config.stopLineOffsetM - v * reactionS
        if (brakingRoom <= 0f) return false
        return v * v / (2f * brakingRoom) <= config.comfortableStopDecelMps2
    }

    /** 다음 안내 지점이 가까우면 그 회전 방향, 아니면 직진으로 본다. 경로 안내 중이 아니면 null */
    private fun plannedManeuver(nav: NavigationState?): Maneuver? {
        if (nav == null || !nav.guiding) return null
        val d = nav.distanceToManeuverM ?: return null
        return if (d <= config.routeLookaheadM) nav.nextManeuver else Maneuver.STRAIGHT
    }

    private fun fire(type: AlertType, nowMs: Long, out: MutableList<Alert>) {
        val interval = if (type == AlertType.RED_URGENT) config.urgentRepeatMs else config.sameAlertCooldownMs
        if (!cooldowns.ready(type, nowMs, interval)) return
        if (out.any { it.type == type }) return
        cooldowns.mark(type, nowMs)
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
