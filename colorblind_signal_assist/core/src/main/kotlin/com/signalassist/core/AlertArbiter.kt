package com.signalassist.core

/** 실제로 전달할 알림과 전달 방식 */
data class Delivery(val alert: Alert, val plan: OutputPlan)

data class ArbiterConfig(
    /** 긴급 경고 직후 이 시간 동안 덜 중요한 알림은 화면 표시만 */
    val quietAfterUrgentMs: Long = 4_000,
    /** 음성/소리 알림 사이 최소 간격 (경고 이상은 예외) */
    val minVoiceGapMs: Long = 2_000,
)

/**
 * 알림 중재. 여러 알림이 한꺼번에 몰리면 정말 중요한 것을 놓치게 된다(특히 주의력·인지 부담이 큰 운전자).
 * 1. 같은 순간에는 가장 중요한 알림 하나만 소리·음성·깜빡임으로, 나머지는 배너/헤드업 알림만 (경고 이상은 예외)
 * 2. 긴급 경고 직후에는 안내·주의 알림을 조용히 표시만
 * 3. 음성 알림이 너무 촘촘하면 덜 중요한 것은 조용히
 * 4. 어두울 때는 화면 깜빡임을 어둡게 (눈부심 방지)
 * 알림을 버리지는 않는다 — 항상 배너와 헤드업 알림에는 남는다.
 */
class AlertArbiter(private val config: ArbiterConfig = ArbiterConfig()) {
    private var lastUrgentMs: Long? = null
    private var lastVoicedMs: Long? = null

    fun reset() { lastUrgentMs = null; lastVoicedMs = null }

    fun arbitrate(alerts: List<Alert>, profile: DriverProfile, nowMs: Long, isDark: Boolean? = null): List<Delivery> {
        if (alerts.isEmpty()) return emptyList()
        val out = ArrayList<Delivery>(alerts.size)
        var primaryTaken = false
        for (alert in alerts.sortedByDescending { it.level }) {
            var plan = profile.outputFor(alert).copy(dimmed = isDark == true)
            val important = alert.level >= AlertLevel.WARNING
            val inQuiet = lastUrgentMs?.let { nowMs - it < config.quietAfterUrgentMs } ?: false
            val tooSoon = lastVoicedMs?.let { nowMs - it < config.minVoiceGapMs } ?: false
            val keep = important || (!primaryTaken && !inQuiet && !tooSoon)
            if (!keep) plan = plan.silenced()
            if (!plan.silent) {
                primaryTaken = true
                lastVoicedMs = nowMs
            }
            if (alert.level == AlertLevel.URGENT) lastUrgentMs = nowMs
            out += Delivery(alert, plan)
        }
        return out
    }
}
