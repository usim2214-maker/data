package com.signalassist.core

enum class AlertLevel { INFO, CAUTION, WARNING, URGENT }

enum class AlertCategory { SIGNAL, VEHICLE, NAVIGATION }

/**
 * 알림 종류마다 '음높이 방향 + 리듬'이 다른 소리를 준다.
 * 색을 구분하기 어려운 운전자는 소리로, 소리를 듣기 어려운 운전자는 화면 깜빡임으로 구분한다.
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
    /** 같은 음 두 번: 차량 상태 경고 */
    DOUBLE_MID,
}

enum class AlertType(
    val category: AlertCategory,
    val level: AlertLevel,
    val tone: TonePattern,
    val messageKo: String,
    /** 화면/알림에 붙이는 모양 기호 (색에 의존하지 않기 위함) */
    val symbol: String,
) {
    // ---- 신호 (색각이상) ----
    GREEN_GO(AlertCategory.SIGNAL, AlertLevel.INFO, TonePattern.RISING_DOUBLE, "초록불로 바뀌었습니다.", "●"),
    GREEN_REMINDER(AlertCategory.SIGNAL, AlertLevel.CAUTION, TonePattern.RISING_DOUBLE, "초록불입니다. 주변을 확인하고 출발하세요.", "●"),
    LEFT_ARROW_ON(AlertCategory.SIGNAL, AlertLevel.INFO, TonePattern.SOFT_CHIME, "좌회전 신호가 켜졌습니다.", "←"),
    LEFT_ARROW_FOR_ROUTE(AlertCategory.SIGNAL, AlertLevel.INFO, TonePattern.RISING_DOUBLE, "좌회전 신호입니다. 안내 경로도 좌회전입니다.", "←"),
    LEFT_ARROW_NOT_FOR_ROUTE(AlertCategory.SIGNAL, AlertLevel.CAUTION, TonePattern.SOFT_CHIME, "좌회전 신호입니다. 직진은 기다리세요.", "←"),
    YELLOW_CAUTION(AlertCategory.SIGNAL, AlertLevel.CAUTION, TonePattern.SINGLE_LONG, "황색 신호입니다. 정지를 준비하세요.", "▲"),
    RED_STOP(AlertCategory.SIGNAL, AlertLevel.WARNING, TonePattern.FALLING_TRIPLE, "적색 신호입니다. 정지하세요.", "■"),
    RED_URGENT(AlertCategory.SIGNAL, AlertLevel.URGENT, TonePattern.RAPID_BEEPS, "적색 신호! 즉시 감속하세요.", "■"),

    // ---- 차량 상태 (청각: 경고음을 화면으로 / 저시력: 경고등을 음성으로 / 주의력) ----
    SEATBELT_UNBUCKLED(AlertCategory.VEHICLE, AlertLevel.WARNING, TonePattern.DOUBLE_MID, "안전벨트를 매 주세요.", "⚠"),
    DOOR_OPEN_MOVING(AlertCategory.VEHICLE, AlertLevel.URGENT, TonePattern.RAPID_BEEPS, "문이 열려 있습니다! 안전한 곳에 정차하세요.", "⚠"),
    LOW_TIRE_PRESSURE(AlertCategory.VEHICLE, AlertLevel.CAUTION, TonePattern.DOUBLE_MID, "타이어 공기압이 낮습니다. 점검이 필요합니다.", "⚠"),
    TURN_SIGNAL_LEFT_ON(AlertCategory.VEHICLE, AlertLevel.CAUTION, TonePattern.DOUBLE_MID, "방향지시등이 계속 켜져 있습니다.", "⇆"),
    HEADLIGHTS_OFF_DARK(AlertCategory.VEHICLE, AlertLevel.CAUTION, TonePattern.DOUBLE_MID, "주변이 어둡습니다. 전조등을 켜 주세요.", "☼"),
    REAR_SEAT_CHECK(AlertCategory.VEHICLE, AlertLevel.WARNING, TonePattern.SINGLE_LONG, "내리기 전에 뒷좌석을 확인하세요.", "◉"),

    // ---- 내비 (청각: 음성 안내를 큰 화면 카드로 / 주의력: 미리 한 번 더) ----
    NAV_MANEUVER_SOON(AlertCategory.NAVIGATION, AlertLevel.INFO, TonePattern.SOFT_CHIME, "곧 안내 지점입니다.", "↑"),
}

data class Alert(
    val type: AlertType,
    val timestampMs: Long,
    /** 기본 문구 대신 쓸 문구 (예: "300m 앞 좌회전") */
    val customMessage: String? = null,
    val customSymbol: String? = null,
) {
    val level get() = type.level
    val tone get() = type.tone
    val category get() = type.category
    val message get() = customMessage ?: type.messageKo
    val symbol get() = customSymbol ?: type.symbol
}

/** 같은 종류 알림의 최소 간격을 관리하는 공통 도우미 */
internal class Cooldowns {
    private val last = HashMap<AlertType, Long>()

    fun ready(type: AlertType, nowMs: Long, intervalMs: Long): Boolean {
        val l = last[type] ?: return true
        return nowMs - l >= intervalMs
    }

    fun mark(type: AlertType, nowMs: Long) { last[type] = nowMs }

    fun lastFired(type: AlertType): Long? = last[type]

    fun clear() = last.clear()
}
