package ai.parkingguide.core

/**
 * 음성 안내와 거리 비프음 타이밍. 실제 재생은 Pleos TextToSpeech와 오디오 출력에 연결한다.
 * 후진할 때 운전자는 미러와 뒤를 함께 봐야 하므로 화면만 볼 수 없다.
 */
fun beepIntervalS(endDistCm: Double?): Double? = when {
    endDistCm == null || endDistCm > 150 -> null
    endDistCm <= 30 -> 0.0 // 연속음
    else -> Math.round((0.12 + (endDistCm - 30) / 120 * 0.68) * 100) / 100.0
}

class VoicePrompter(
    private val minGapS: Double = 2.5,
    private val stopRepeatS: Double = 2.0,
    private val calloutsCm: List<Double> = listOf(100.0, 50.0),
) {
    private var lastT = -1e9
    private var lastKey: String? = null
    private val saidCallouts = mutableSetOf<Double>()

    /** 이번 시점에 말할 문장 (없으면 null). */
    fun update(g: Guidance?, endDistCm: Double?, t: Double): String? {
        if (g == null) return null
        if (g.level == GuidanceLevel.STOP) {
            return if (lastKey != "stop" || t - lastT >= stopRepeatS) say("stop", "멈추세요", t) else null
        }
        if (endDistCm != null) {
            for (c in calloutsCm) {
                if (endDistCm <= c && c !in saidCallouts) {
                    saidCallouts += c
                    return say("dist$c", if (c >= 100) "1미터 남았어요" else "${c.toInt()}센티 남았어요", t)
                }
            }
        }
        if (g.key == lastKey || g.level == GuidanceLevel.INFO) return null
        if (t - lastT < minGapS) return null
        return say(g.key, g.text, t)
    }

    fun reset() {
        lastT = -1e9; lastKey = null; saidCallouts.clear()
    }

    private fun say(key: String, text: String, t: Double): String {
        lastKey = key; lastT = t
        return text
    }
}
