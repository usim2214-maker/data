package com.signalassist.core

import kotlin.math.roundToInt

/**
 * '지금 신호?' — 운전자가 교차로에서 헷갈릴 때 버튼을 누르면 현재 신호를 말해 준다.
 *
 * 가장 중요한 원칙은 **모르면서 아는 척하지 않기**:
 * - 확실하면(최근 프레임 80% 이상 일치): "지금은 빨간불입니다. 신호등까지 약 25미터."
 * - 애매하면(60~80%): "빨간불로 보이지만 확실하지 않습니다. 직접 확인하세요."
 * - 못 봤거나 오래됐으면: "지금은 신호등을 확실히 보지 못했습니다. 직접 확인하세요."
 */
object SignalQuery {
    private const val SURE = 0.8f
    private const val MAYBE = 0.6f
    private const val STALE_MS = 2_000L

    private fun word(c: SignalColor) = when (c) {
        SignalColor.RED -> "빨간불"; SignalColor.YELLOW -> "노란불"; SignalColor.GREEN -> "초록불"; SignalColor.UNKNOWN -> "신호"
    }

    private fun tone(c: SignalColor) = when (c) {
        SignalColor.RED -> TonePattern.FALLING_TRIPLE
        SignalColor.YELLOW -> TonePattern.SINGLE_LONG
        SignalColor.GREEN -> TonePattern.RISING_DOUBLE
        SignalColor.UNKNOWN -> TonePattern.SOFT_CHIME
    }

    private fun symbol(c: SignalColor) = when (c) {
        SignalColor.RED -> "■"; SignalColor.YELLOW -> "▲"; SignalColor.GREEN -> "●"; SignalColor.UNKNOWN -> "?"
    }

    fun answer(signal: EngineOutput?, nowMs: Long): Alert {
        val stale = signal == null || nowMs - signal.timestampMs > STALE_MS
        val c = signal?.state?.color ?: SignalColor.UNKNOWN
        val conf = signal?.confidence ?: 0f
        if (stale || c == SignalColor.UNKNOWN || conf < MAYBE) {
            return Alert(AlertType.SIGNAL_STATUS, nowMs,
                customMessage = "지금은 신호등을 확실히 보지 못했습니다. 직접 확인하세요.",
                customSymbol = "?", customHeadline = "직접 확인", customTone = TonePattern.SOFT_CHIME)
        }
        val w = word(c)
        if (conf < SURE) {
            return Alert(AlertType.SIGNAL_STATUS, nowMs,
                customMessage = "${w}로 보이지만 확실하지 않습니다. 직접 확인하세요.",
                customSymbol = symbol(c), customHeadline = "$w?", customTone = TonePattern.SOFT_CHIME)
        }
        val distance = signal!!.distanceM?.let { " 신호등까지 약 ${(it / 5f).roundToInt() * 5}미터." } ?: ""
        val arrow = if (signal.state.leftTurnArrow) " 좌회전 신호도 켜져 있습니다." else ""
        return Alert(AlertType.SIGNAL_STATUS, nowMs,
            customMessage = "지금은 ${w}입니다.$distance$arrow",
            customSymbol = symbol(c), customHeadline = w, customTone = tone(c))
    }

    /** 운전자가 직접 물은 것이므로 프로필이 쓸 수 있는 감각을 모두 사용 (중재·알림 양 설정과 무관) */
    fun plan(profile: DriverProfile, alert: Alert): OutputPlan =
        profile.outputFor(alert).let {
            it.copy(visualFlash = it.visualFlash || profile.has(DriverNeed.HEARING), flashPattern = FlashPattern.SINGLE)
        }
}
