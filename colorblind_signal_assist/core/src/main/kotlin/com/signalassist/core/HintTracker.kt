package com.signalassist.core

/**
 * 운전하며 익히기: 알림음의 뜻을 처음 몇 번만 음성으로 덧붙이고, 익숙해지면 짧게.
 * - 보통: 알림 종류마다 처음 [learnCount]번
 * - 자세히: 항상
 * - 위험할 때만: 덧붙이지 않음 (짧게)
 * 횟수는 앱이 저장해 두었다가 다음 주행에 이어 쓴다 ([counts]).
 */
class HintTracker(private val learnCount: Int = 3) {
    val counts = HashMap<AlertType, Int>()

    fun decorate(deliveries: List<Delivery>, prefs: AlertPreferences): List<Delivery> = deliveries.map { d ->
        val hint = AlertGuide.hintFor(d.alert.type)
        if (!d.plan.voice || hint == null || d.alert.customMessage != null) return@map d
        val n = counts[d.alert.type] ?: 0
        val add = when (prefs.verbosity) {
            Verbosity.DETAILED -> true
            Verbosity.NORMAL -> n < learnCount
            Verbosity.MINIMAL -> false
        }
        counts[d.alert.type] = n + 1
        if (add) d.copy(alert = d.alert.copy(speechSuffix = hint)) else d
    }
}
