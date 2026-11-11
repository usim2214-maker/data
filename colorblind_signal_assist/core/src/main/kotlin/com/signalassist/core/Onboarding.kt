package com.signalassist.core

/**
 * 개인 맞춤 알림 설정. 같은 '청각장애'라도 전혀 못 듣는 사람과 크고 낮은 소리는 듣는 사람이 다르다.
 */
data class AlertPreferences(
    /** 낮은 음역 알림음. null 이면 프로필 기본값 (주의력·고령이면 낮게) */
    val lowPitch: Boolean? = null,
    /** 알림음 크기 배율 (1.0 ~ 1.25) */
    val volumeBoost: Float = 1f,
    /** 청각장애지만 큰 알림음은 들림: 화면 깜빡임과 함께 알림음도 낸다 */
    val soundForHearing: Boolean = false,
)

/** 처음 실행 때 묻는 질문 (예/아니요). 장애 이름 대신 '겪는 상황'으로 묻는다 */
enum class OnboardingQuestion(val textKo: String, val need: DriverNeed) {
    COLOR("신호등의 빨간불과 초록불을 구분하기 어려우신가요?", DriverNeed.COLOR_VISION),
    HEARING("차의 경고음이나 구급차 사이렌이 잘 들리지 않으시나요?", DriverNeed.HEARING),
    VISION("계기판의 작은 경고등이나 글씨가 잘 안 보이시나요?", DriverNeed.LOW_VISION),
    ATTENTION("방향지시등 끄는 것을 잊거나, 알림이 겹치면 헷갈리시나요? (65세 이상이면 '예'를 권합니다)", DriverNeed.ATTENTION),
}

/** 소리 테스트에서 들려줄 소리 */
enum class HearingProbe(val textKo: String, val lowPitch: Boolean, val level: AlertLevel) {
    NORMAL("보통 높이의 알림음", false, AlertLevel.WARNING),
    LOW("낮은 알림음", true, AlertLevel.WARNING),
    QUIET("작은 알림음", false, AlertLevel.INFO),
}

/**
 * 첫 설정: 질문 답 + 소리 테스트 결과 -> 프로필과 맞춤 설정.
 * 소리 테스트는 실제 차 스피커로 하므로, 그 차·그 사람의 청력에 맞춰진다.
 */
object Onboarding {
    fun build(answers: Map<OnboardingQuestion, Boolean>, heard: Map<HearingProbe, Boolean>? = null): DriverProfile {
        val needs = answers.filterValues { it }.keys.map { it.need }.toSet()
        if (heard == null) return DriverProfile(needs)
        val normal = heard[HearingProbe.NORMAL] == true
        val low = heard[HearingProbe.LOW] == true
        val quiet = heard[HearingProbe.QUIET] == true
        val anyHeard = normal || low
        val prefs = AlertPreferences(
            // 낮은 소리만 들리면 낮은 음역으로 (고음부터 안 들리는 난청)
            lowPitch = if (low && !normal) true else null,
            // 작은 소리를 못 들으면 전체를 키운다
            volumeBoost = if (anyHeard && !quiet) 1.25f else 1f,
            // 청각장애라고 답했지만 알림음은 들림 -> 소리도 함께 (부분 난청)
            soundForHearing = DriverNeed.HEARING in needs && anyHeard,
        )
        // 아무 소리도 못 들었는데 청각장애를 고르지 않았다면 화면 알림이 꼭 필요하므로 추가
        val finalNeeds = if (!anyHeard) needs + DriverNeed.HEARING else needs
        return DriverProfile(finalNeeds, prefs)
    }
}
