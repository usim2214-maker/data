package com.signalassist.core

/**
 * 내비 음성 안내를 듣기 어려운 운전자(청각)나 미리 한 번 더 듣고 싶은 운전자(주의력)를 위해
 * 안내 지점 [thresholdsM] 앞에서 큰 카드 + 짧은 알림을 띄운다.
 */
class NavigationAlertPolicy(private val thresholdsM: List<Float> = listOf(300f, 100f)) {
    private var lastDistance: Float? = null
    private var lastManeuver: Maneuver? = null
    private val fired = HashSet<Float>()

    fun reset() { lastDistance = null; lastManeuver = null; fired.clear() }

    fun evaluate(nav: NavigationState?, nowMs: Long): List<Alert> {
        if (nav == null || !nav.guiding) { reset(); return emptyList() }
        val d = nav.distanceToManeuverM ?: return emptyList()
        val m = nav.nextManeuver ?: Maneuver.OTHER
        // 안내 지점을 지나 다음 지점으로 넘어가면(거리가 늘어나거나 종류가 바뀌면) 새 안내로 본다
        val prev = lastDistance
        if (prev == null || d > prev + 30f || m != lastManeuver) fired.clear()
        lastDistance = d
        lastManeuver = m

        val out = ArrayList<Alert>(1)
        // 지금 거리 안쪽으로 새로 넘어온 문턱이 있으면 한 번만 안내 (여러 개를 한꺼번에 넘어도 1회)
        val crossed = thresholdsM.filter { d <= it && it !in fired }
        if (crossed.isNotEmpty()) {
            fired += crossed
            val meters = (Math.round(d / 10f) * 10).coerceAtLeast(10)
            val text = if (m == Maneuver.DESTINATION) "${meters}m 앞 목적지" else "${meters}m 앞 ${m.labelKo}"
            val road = nav.roadName?.let { " ($it)" } ?: ""
            out += Alert(AlertType.NAV_MANEUVER_SOON, nowMs, customMessage = "$text$road", customSymbol = m.symbol,
                customHeadline = "${meters}m") // 기호는 화면이 symbol 로 따로 붙인다 (중복 방지)
        }
        return out
    }
}
