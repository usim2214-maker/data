package com.signalassist.core

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** 알림음의 음 하나 */
data class ToneNote(val freqHz: Double, val durMs: Int, val gapMs: Int = 60)

/**
 * 알림음 설계 (앱과 시연 영상이 같은 소리를 쓴다).
 *
 * 인지성을 위한 원칙
 * - **배음이 풍부한 소리**: 순음(사인파) 하나는 도로 소음·음악에 쉽게 묻히고 방향감도 약하다.
 *   기본음 + 2·3·4배음을 섞어, 일부 주파수가 소음에 가려도 들리게 한다.
 * - **고령자용 낮은 음역**: 노인성 난청은 고음부터 안 들리므로, 주의력·고령 프로필은 전체 음높이를 0.6배로 낮춘다.
 * - **위험도에 따른 크기**: 안내 < 주의 < 경고 < 긴급. 사소한 알림이 긴급 경고만큼 크면 긴급함이 묻힌다.
 * - **의미는 리듬과 음높이 방향으로**: 진행=올라감, 정지=내려감, 긴급=빠른 반복 (색에 의존하지 않음).
 */
object ToneLibrary {
    private val base: Map<TonePattern, List<ToneNote>> = mapOf(
        TonePattern.RISING_DOUBLE to listOf(ToneNote(523.0, 150), ToneNote(784.0, 220)),
        TonePattern.SINGLE_LONG to listOf(ToneNote(660.0, 500)),
        TonePattern.FALLING_TRIPLE to listOf(ToneNote(880.0, 150), ToneNote(660.0, 150), ToneNote(440.0, 260)),
        TonePattern.RAPID_BEEPS to List(6) { ToneNote(1000.0, 80, 50) },
        TonePattern.SOFT_CHIME to listOf(ToneNote(1047.0, 120)),
        TonePattern.DOUBLE_MID to listOf(ToneNote(740.0, 140, 120), ToneNote(740.0, 140)),
    )

    /** 배음 세기 (기본음, 2배음, 3배음, 4배음) */
    private val harmonics = doubleArrayOf(1.0, 0.5, 0.35, 0.25)

    fun notes(pattern: TonePattern, lowPitch: Boolean = false): List<ToneNote> {
        val n = base.getValue(pattern)
        return if (lowPitch) n.map { it.copy(freqHz = it.freqHz * 0.6) } else n
    }

    fun durationMs(pattern: TonePattern): Int = base.getValue(pattern).sumOf { it.durMs + it.gapMs }

    fun gainFor(level: AlertLevel): Float = when (level) {
        AlertLevel.INFO -> 0.55f
        AlertLevel.CAUTION -> 0.7f
        AlertLevel.WARNING -> 0.85f
        AlertLevel.URGENT -> 1.0f
    }

    /** PCM 16bit mono 로 합성 */
    fun synthesize(pattern: TonePattern, sampleRate: Int, lowPitch: Boolean = false, gain: Float = 1f): ShortArray {
        val list = notes(pattern, lowPitch)
        val out = ShortArray(list.sumOf { (it.durMs + it.gapMs) * sampleRate / 1000 })
        val norm = harmonics.sum()
        var pos = 0
        for (note in list) {
            val len = note.durMs * sampleRate / 1000
            val fade = min(len / 4, sampleRate / 100) // 10ms 페이드로 '틱' 소리 방지
            for (i in 0 until len) {
                val env = when {
                    i < fade -> i.toDouble() / fade
                    i > len - fade -> (len - i).toDouble() / fade
                    else -> 1.0
                }
                var v = 0.0
                for (h in harmonics.indices) {
                    val f = note.freqHz * (h + 1)
                    if (f < sampleRate / 2.0) v += harmonics[h] * sin(2 * PI * f * i / sampleRate)
                }
                out[pos + i] = (v / norm * env * 0.9 * gain * Short.MAX_VALUE).toInt().toShort()
            }
            pos += len + note.gapMs * sampleRate / 1000
        }
        return out
    }
}

/**
 * 화면 깜빡임 방식. 위험도가 높을수록 빠르고 많이 깜빡인다.
 * - 모든 방식이 **초당 3회 이하** (광과민성 발작 기준, WCAG 2.3.1)
 * - 안내(INFO)는 화면 전체를 덮지 않고 **테두리만** 빛나게 해 주의를 덜 빼앗는다
 */
enum class FlashPattern(val durationMs: Int, val pulses: Int, val fullScreen: Boolean) {
    EDGE(1_000, 1, false),
    SINGLE(1_200, 1, true),
    DOUBLE(1_600, 2, true),
    RAPID(2_000, 5, true);

    val flashesPerSecond: Float get() = pulses * 1000f / durationMs

    /** 시작 후 [tMs] 시점의 밝기(0..1). 각 펄스는 부드럽게 켜졌다 꺼진다 */
    fun alphaAt(tMs: Long): Float {
        if (tMs < 0 || tMs >= durationMs) return 0f
        val phase = tMs.toFloat() / durationMs * pulses
        val s = sin(PI * (phase - phase.toInt())).toFloat()
        return s * s
    }

    companion object {
        fun forLevel(level: AlertLevel) = when (level) {
            AlertLevel.INFO -> EDGE
            AlertLevel.CAUTION -> SINGLE
            AlertLevel.WARNING -> DOUBLE
            AlertLevel.URGENT -> RAPID
        }
    }
}
