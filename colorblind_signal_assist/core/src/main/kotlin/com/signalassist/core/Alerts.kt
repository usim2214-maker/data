package com.signalassist.core

enum class AlertLevel { INFO, CAUTION, WARNING, URGENT }

enum class AlertCategory { SIGNAL, VEHICLE, NAVIGATION, ENVIRONMENT }

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
    /** 화면에 보여줄 문장 */
    val messageKo: String,
    /** 화면/알림에 붙이는 모양 기호 (색에 의존하지 않기 위함) */
    val symbol: String,
    /** 음성 문장: 핵심어를 맨 앞에, 일상어(빨간불·노란불·초록불)로, 짧게 */
    val speechKo: String,
    /** 화면 깜빡임에 크게 띄우는 한두 단어 (한눈에 읽히도록) */
    val headlineKo: String,
) {
    // ---- 신호 (색각이상) ----
    GREEN_GO(AlertCategory.SIGNAL, AlertLevel.INFO, TonePattern.RISING_DOUBLE, "초록불로 바뀌었습니다.", "●", "초록불입니다.", "진행"),
    GREEN_REMINDER(AlertCategory.SIGNAL, AlertLevel.CAUTION, TonePattern.RISING_DOUBLE, "초록불입니다. 주변을 확인하고 출발하세요.", "●", "초록불, 주변 확인 후 출발하세요.", "진행"),
    LEFT_ARROW_ON(AlertCategory.SIGNAL, AlertLevel.INFO, TonePattern.SOFT_CHIME, "좌회전 신호가 켜졌습니다.", "←", "좌회전 신호입니다.", "좌회전"),
    LEFT_ARROW_FOR_ROUTE(AlertCategory.SIGNAL, AlertLevel.INFO, TonePattern.RISING_DOUBLE, "좌회전 신호입니다. 안내 경로도 좌회전입니다.", "←", "좌회전 신호, 경로도 좌회전입니다.", "좌회전"),
    LEFT_ARROW_NOT_FOR_ROUTE(AlertCategory.SIGNAL, AlertLevel.CAUTION, TonePattern.SOFT_CHIME, "좌회전 신호입니다. 직진은 기다리세요.", "←", "좌회전 신호, 직진은 대기하세요.", "직진 대기"),
    YELLOW_CAUTION(AlertCategory.SIGNAL, AlertLevel.CAUTION, TonePattern.SINGLE_LONG, "노란불입니다. 정지를 준비하세요.", "▲", "노란불, 정지 준비하세요.", "주의"),
    /** 정지선이 너무 가까워 편안하게 설 수 없을 때: 급정거(추돌 위험)를 유도하지 않는다 */
    YELLOW_DILEMMA(AlertCategory.SIGNAL, AlertLevel.CAUTION, TonePattern.SINGLE_LONG, "노란불입니다. 급정거하지 말고 주의해서 진행하세요.", "▲", "노란불, 급정거 말고 주의해서 진행하세요.", "주의 진행"),
    RED_STOP(AlertCategory.SIGNAL, AlertLevel.WARNING, TonePattern.FALLING_TRIPLE, "빨간불입니다. 정지하세요.", "■", "빨간불, 정지하세요.", "정지"),
    /** 인식 실패를 숨기지 않는다: 침묵을 '여전히 빨간불'로 오해하지 않게 */
    SIGNAL_LOST(AlertCategory.SIGNAL, AlertLevel.CAUTION, TonePattern.SOFT_CHIME, "신호등이 보이지 않습니다. 신호를 직접 확인하세요.", "?", "신호등이 안 보입니다. 직접 확인하세요.", "직접 확인"),
    RED_URGENT(AlertCategory.SIGNAL, AlertLevel.URGENT, TonePattern.RAPID_BEEPS, "빨간불! 즉시 감속하세요.", "■", "빨간불! 감속하세요!", "감속!"),

    // ---- 차량 상태 (청각: 경고음을 화면으로 / 저시력: 경고등을 음성으로 / 주의력) ----
    SEATBELT_UNBUCKLED(AlertCategory.VEHICLE, AlertLevel.WARNING, TonePattern.DOUBLE_MID, "안전벨트를 매 주세요.", "⚠", "안전벨트를 매 주세요.", "벨트"),
    DOOR_OPEN_MOVING(AlertCategory.VEHICLE, AlertLevel.URGENT, TonePattern.RAPID_BEEPS, "문이 열려 있습니다! 안전한 곳에 정차하세요.", "⚠", "문 열림! 안전하게 정차하세요!", "문 열림"),
    LOW_TIRE_PRESSURE(AlertCategory.VEHICLE, AlertLevel.CAUTION, TonePattern.DOUBLE_MID, "타이어 공기압이 낮습니다. 점검이 필요합니다.", "⚠", "타이어 공기압이 낮습니다.", "타이어"),
    TURN_SIGNAL_LEFT_ON(AlertCategory.VEHICLE, AlertLevel.CAUTION, TonePattern.DOUBLE_MID, "방향지시등이 계속 켜져 있습니다.", "⇆", "방향지시등이 켜져 있습니다.", "방향지시등"),
    HEADLIGHTS_OFF_DARK(AlertCategory.VEHICLE, AlertLevel.CAUTION, TonePattern.DOUBLE_MID, "주변이 어둡습니다. 전조등을 켜 주세요.", "☼", "전조등을 켜 주세요.", "전조등"),
    REAR_SEAT_CHECK(AlertCategory.VEHICLE, AlertLevel.WARNING, TonePattern.SINGLE_LONG, "내리기 전에 뒷좌석을 확인하세요.", "◉", "뒷좌석을 확인하세요.", "뒷좌석"),

    // ---- 주변 소리 (청각: 사이렌을 듣지 못함) ----
    SIREN_NEARBY(AlertCategory.ENVIRONMENT, AlertLevel.WARNING, TonePattern.RAPID_BEEPS, "긴급차량 사이렌이 들립니다. 주변을 살피고 길을 양보하세요.", "🚨", "사이렌, 길을 양보하세요.", "사이렌"),

    // ---- 내비 (청각: 음성 안내를 큰 화면 카드로 / 주의력: 미리 한 번 더) ----
    NAV_MANEUVER_SOON(AlertCategory.NAVIGATION, AlertLevel.INFO, TonePattern.SOFT_CHIME, "곧 안내 지점입니다.", "↑", "곧 안내 지점입니다.", "안내"),
}

data class Alert(
    val type: AlertType,
    val timestampMs: Long,
    /** 기본 문구 대신 쓸 문구 (예: "300m 앞 좌회전"). 화면·음성 모두 이 문구를 쓴다 */
    val customMessage: String? = null,
    val customSymbol: String? = null,
    val customHeadline: String? = null,
    /** 음성 끝에 덧붙이는 설명 (예: "두 음이 올라가면 초록불입니다.") */
    val speechSuffix: String? = null,
) {
    val level get() = type.level
    val tone get() = type.tone
    val category get() = type.category
    val message get() = customMessage ?: type.messageKo
    val symbol get() = customSymbol ?: type.symbol
    /** 음성으로 읽을 문장 (짧고 핵심어 먼저) */
    val speech get() = (customMessage ?: type.speechKo) + (speechSuffix?.let { " $it" } ?: "")
    /** 깜빡임에 크게 띄울 한두 단어 */
    val headline get() = customHeadline ?: type.headlineKo
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

/** 계속되는 위험을 화면에 남겨 둘 때 쓰는 짧은 상태 문구 */
val AlertType.ongoingLabelKo: String
    get() = when (this) {
        AlertType.RED_URGENT -> "빨간불 · 감속 필요"
        AlertType.DOOR_OPEN_MOVING -> "문 열림"
        AlertType.SEATBELT_UNBUCKLED -> "벨트 미착용"
        AlertType.SIREN_NEARBY -> "사이렌 감지 중"
        AlertType.TURN_SIGNAL_LEFT_ON -> "방향지시등 켜짐"
        AlertType.SIGNAL_LOST -> "신호 직접 확인"
        else -> headlineKo
    }

/**
 * 사용자가 끌 수 있는 알림인지. 경고 이상, 그리고 노란불·신호 놓침처럼 안전 판단에 꼭 필요한 알림은 끌 수 없다.
 */
val AlertType.mutable: Boolean
    get() = level < AlertLevel.WARNING &&
        this !in setOf(AlertType.YELLOW_CAUTION, AlertType.YELLOW_DILEMMA, AlertType.SIGNAL_LOST)
