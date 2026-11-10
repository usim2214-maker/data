package com.signalassist.core

/** 운전에 영향을 주는 특성. 여러 개를 함께 고를 수 있다. */
enum class DriverNeed(val labelKo: String, val descriptionKo: String) {
    COLOR_VISION("색각이상", "신호등 색을 소리·모양·문구로 알려줍니다"),
    HEARING("청각장애", "경고음·방향지시등 소리·내비 음성·긴급차량 사이렌을 화면 깜빡임과 큰 글씨로 바꿔줍니다"),
    LOW_VISION("저시력", "작은 경고등을 음성으로 읽어주고 글씨를 크게 표시합니다"),
    ATTENTION("주의력·고령", "깜빡한 방향지시등, 늦은 출발, 뒷좌석 확인을 한 번 더 알려줍니다"),
}

/** 알림 하나를 어떤 감각으로 전달할지 */
data class OutputPlan(
    val tone: Boolean,
    val voice: Boolean,
    /** 화면 전체 깜빡임 (소리 대신) */
    val visualFlash: Boolean,
    val largeText: Boolean,
    /** 야간: 화면 깜빡임 밝기를 낮춰 눈부심 방지 */
    val dimmed: Boolean = false,
) {
    /** 화면 배너·헤드업 알림만 (소리·음성·깜빡임 없음) */
    val silent get() = !tone && !voice && !visualFlash

    fun silenced() = copy(tone = false, voice = false, visualFlash = false)
}

/**
 * 운전자 프로필. 고른 특성에 따라 ① 어떤 기능을 켤지 ② 알림을 어떤 감각으로 낼지 정한다.
 */
data class DriverProfile(val needs: Set<DriverNeed>) {

    fun has(n: DriverNeed) = n in needs

    /** 신호 인식 모듈 사용 여부 */
    val signalAssist get() = has(DriverNeed.COLOR_VISION) || has(DriverNeed.ATTENTION)

    val vehicleFeatures: Set<VehicleFeature>
        get() = buildSet {
            if (has(DriverNeed.HEARING) || has(DriverNeed.LOW_VISION) || has(DriverNeed.ATTENTION)) add(VehicleFeature.WARNINGS)
            // 방향지시등 똑딱 소리를 못 듣거나(청각) 자주 깜빡하는 경우(주의력)
            if (has(DriverNeed.HEARING) || has(DriverNeed.ATTENTION)) add(VehicleFeature.TURN_SIGNAL)
            // 뒷좌석 아이·반려동물 방치 예방은 모두에게 켠다
            add(VehicleFeature.REAR_SEAT)
        }

    /** 긴급차량 사이렌을 화면으로 알릴지 (마이크 사용) */
    val sirenAlerts get() = has(DriverNeed.HEARING)

    /** 내비 안내를 화면 카드로 미리 보여줄지 */
    val navigationCards get() = has(DriverNeed.HEARING) || has(DriverNeed.ATTENTION)

    /** 이 프로필에서 낼 알림인지 (색 구분에 문제 없는 주의력 프로필은 위험·리마인드만) */
    fun accepts(alert: Alert): Boolean {
        if (alert.category != AlertCategory.SIGNAL) return true
        if (has(DriverNeed.COLOR_VISION)) return true
        return alert.type == AlertType.GREEN_REMINDER || alert.type == AlertType.RED_URGENT
    }

    fun outputFor(alert: Alert): OutputPlan {
        val hearingOnly = has(DriverNeed.HEARING) && needs.size == 1
        return OutputPlan(
            tone = !hearingOnly,
            voice = !hearingOnly,
            visualFlash = has(DriverNeed.HEARING) || alert.level >= AlertLevel.WARNING,
            largeText = has(DriverNeed.LOW_VISION) || has(DriverNeed.ATTENTION),
        )
    }

    companion object {
        val COLOR_VISION_ONLY = DriverProfile(setOf(DriverNeed.COLOR_VISION))
        val ALL = DriverProfile(DriverNeed.entries.toSet())
    }
}
