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
    /** 위험도에 맞는 깜빡임 방식 */
    val flashPattern: FlashPattern = FlashPattern.DOUBLE,
    /** 알림음 크기 (위험도별) */
    val toneGain: Float = 1f,
    /** 고령자용 낮은 음역 알림음 */
    val lowPitchTones: Boolean = false,
    /** 음성 속도 (긴급은 빠르게, 고령자는 천천히) */
    val speechRate: Float = 1f,
) {
    /** 화면 배너·헤드업 알림만 (소리·음성·깜빡임 없음) */
    val silent get() = !tone && !voice && !visualFlash

    fun silenced() = copy(tone = false, voice = false, visualFlash = false)
}

/**
 * 운전자 프로필. 고른 특성에 따라 ① 어떤 기능을 켤지 ② 알림을 어떤 감각으로 낼지 정한다.
 */
data class DriverProfile(
    val needs: Set<DriverNeed>,
    /** 개인 맞춤 설정 (첫 설정의 소리 테스트 결과 등) */
    val prefs: AlertPreferences = AlertPreferences(),
) {

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

    /**
     * 알림을 받고 브레이크를 밟기까지 걸리는 시간(초). 긴급 경고 시점을 이만큼 앞당긴다.
     * - 고령·주의력: +0.5초 (반응이 느려지는 경향)
     * - 청각장애만: +0.3초 (소리 없이 화면으로만 알아채야 하므로)
     */
    val reactionTimeS: Float
        get() = 1.0f + (if (has(DriverNeed.ATTENTION)) 0.5f else 0f) + (if (hearingOnly && !prefs.soundForHearing) 0.3f else 0f)

    private val hearingOnly get() = has(DriverNeed.HEARING) && needs.size == 1

    /** 이 프로필에서 낼 알림인지 (색 구분에 문제 없는 주의력 프로필은 위험·리마인드만) */
    fun accepts(alert: Alert): Boolean {
        val t = alert.type
        if (t.mutable && t in prefs.muted) return false
        if (prefs.verbosity == Verbosity.MINIMAL && t.mutable) return false
        if (alert.category != AlertCategory.SIGNAL) return true
        if (has(DriverNeed.COLOR_VISION)) return true
        return alert.type == AlertType.GREEN_REMINDER || alert.type == AlertType.RED_URGENT ||
            (signalAssist && alert.type == AlertType.SIGNAL_LOST)
    }

    fun outputFor(alert: Alert): OutputPlan {
        val elderly = has(DriverNeed.ATTENTION)
        return OutputPlan(
            // 부분 난청: 화면 깜빡임에 더해 (크고 낮은) 알림음도. 말소리는 알아듣기 어려워 음성은 끈다
            tone = !hearingOnly || prefs.soundForHearing,
            voice = !hearingOnly,
            visualFlash = has(DriverNeed.HEARING) || alert.level >= AlertLevel.WARNING,
            largeText = has(DriverNeed.LOW_VISION) || elderly,
            flashPattern = FlashPattern.forLevel(alert.level),
            toneGain = (ToneLibrary.gainFor(alert.level) * prefs.volumeBoost).coerceAtMost(1f),
            lowPitchTones = prefs.lowPitch ?: elderly,
            speechRate = when {
                alert.level == AlertLevel.URGENT -> if (elderly) 1.0f else 1.15f
                elderly -> 0.9f
                else -> 1.0f
            },
        )
    }

    companion object {
        val COLOR_VISION_ONLY = DriverProfile(setOf(DriverNeed.COLOR_VISION))
        val ALL = DriverProfile(DriverNeed.entries.toSet())
    }
}
