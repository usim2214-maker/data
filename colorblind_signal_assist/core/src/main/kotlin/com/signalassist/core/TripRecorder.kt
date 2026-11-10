package com.signalassist.core

/** 한 번의 주행 요약. 위치는 기록하지 않는다 */
data class TripSummary(
    val startMs: Long,
    val durationMs: Long,
    val distanceKm: Float,
    val alertCounts: Map<AlertType, Int>,
) {
    val urgentCount get() = alertCounts.filterKeys { it.level == AlertLevel.URGENT }.values.sum()
    val signalCount get() = alertCounts.filterKeys { it.category == AlertCategory.SIGNAL }.values.sum()

    /** 가족·보호자와 공유할 수 있는 짧은 요약 */
    fun summaryKo(): String = buildString {
        val minutes = (durationMs / 60_000).coerceAtLeast(1)
        append("주행 ${minutes}분 · %.1fkm".format(distanceKm))
        if (signalCount > 0) append("\n신호 안내 ${signalCount}회")
        val vehicle = alertCounts.filterKeys { it.category == AlertCategory.VEHICLE || it.category == AlertCategory.ENVIRONMENT }
        if (vehicle.isNotEmpty()) {
            append("\n차량·주변 알림: ")
            append(vehicle.entries.sortedByDescending { it.key.level }.joinToString(", ") { "${labelOf(it.key)} ${it.value}회" })
        }
        append("\n")
        append(if (urgentCount == 0) "긴급 경고 없이 안전하게 주행했습니다." else "긴급 경고 ${urgentCount}회가 있었습니다. 다음 주행에서 신호 접근 속도를 확인해 보세요.")
    }

    private fun labelOf(t: AlertType) = when (t) {
        AlertType.SEATBELT_UNBUCKLED -> "안전벨트"
        AlertType.DOOR_OPEN_MOVING -> "주행 중 문 열림"
        AlertType.LOW_TIRE_PRESSURE -> "타이어 저압"
        AlertType.TURN_SIGNAL_LEFT_ON -> "방향지시등"
        AlertType.HEADLIGHTS_OFF_DARK -> "전조등"
        AlertType.REAR_SEAT_CHECK -> "뒷좌석 확인"
        AlertType.SIREN_NEARBY -> "긴급차량 사이렌"
        else -> t.symbol
    }
}

/**
 * 주행 시작·종료를 감지하고 알림을 집계한다.
 * 시작: 5km/h 초과 / 종료: P 기어, 또는 3분 넘게 정차
 */
class TripRecorder(private val endAfterStoppedMs: Long = 180_000) {
    private var startMs: Long? = null
    private var lastMs: Long? = null
    private var stoppedSinceMs: Long? = null
    private var distanceM = 0.0
    private val counts = HashMap<AlertType, Int>()

    val inTrip get() = startMs != null

    /** @return 주행이 끝났으면 요약 */
    fun update(output: DrivingOutput): TripSummary? {
        val now = output.input.timestampMs
        val v = output.input.vehicle
        val start = startMs
        if (start == null) {
            if (v.speedKmh > 5f) { startMs = now; lastMs = now; distanceM = 0.0; counts.clear() } else return null
        }
        lastMs?.let { distanceM += v.speedKmh / 3.6 * (now - it) / 1000.0 }
        lastMs = now
        for (a in output.alerts) counts.merge(a.type, 1, Int::plus)

        stoppedSinceMs = if (v.speedKmh < 1f) stoppedSinceMs ?: now else null
        val parked = v.gear == Gear.PARK && v.speedKmh < 1f
        val longStop = stoppedSinceMs?.let { now - it >= endAfterStoppedMs } ?: false
        return if (parked || longStop) finish(now) else null
    }

    fun finish(nowMs: Long): TripSummary? {
        val s = startMs ?: return null
        val summary = TripSummary(s, nowMs - s, (distanceM / 1000.0).toFloat(), counts.toMap())
        startMs = null; lastMs = null; stoppedSinceMs = null
        return summary
    }
}
