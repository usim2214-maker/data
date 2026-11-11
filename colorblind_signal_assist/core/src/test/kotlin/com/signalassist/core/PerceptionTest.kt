package com.signalassist.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 경고가 '잘 인지되는가'를 확인하는 테스트 */
class PerceptionTest {
    private fun power(x: ShortArray, f: Double, sr: Int): Double {
        val c = 2 * cos(2 * PI * f / sr); var s1 = 0.0; var s2 = 0.0
        for (v in x) { val s0 = v + c * s1 - s2; s2 = s1; s1 = s0 }
        return s1 * s1 + s2 * s2 - c * s1 * s2
    }

    @Test
    fun `tones are harmonic-rich, not a single sine`() {
        val sr = 16_000
        val pcm = ToneLibrary.synthesize(TonePattern.SINGLE_LONG, sr) // 660Hz
        val h2 = power(pcm, 1320.0, sr)
        val off = power(pcm, 990.0, sr)
        assertTrue(h2 > off * 50, "2배음 에너지가 있어야 함")
    }

    @Test
    fun `elderly profile uses lower pitch for presbycusis`() {
        val high = TonePattern.entries.maxOf { p -> ToneLibrary.notes(p, lowPitch = true).maxOf { it.freqHz } }
        assertTrue(high <= 650.0, "고령자 알림음 최고 기본음 $high Hz")
        assertTrue(DriverProfile(setOf(DriverNeed.ATTENTION)).outputFor(Alert(AlertType.RED_STOP, 0)).lowPitchTones)
    }

    @Test
    fun `louder and faster as urgency rises, never above 3 flashes per second`() {
        val levels = AlertLevel.entries
        assertTrue(levels.zipWithNext().all { (a, b) -> ToneLibrary.gainFor(a) < ToneLibrary.gainFor(b) })
        assertTrue(levels.zipWithNext().all { (a, b) -> FlashPattern.forLevel(a).pulses <= FlashPattern.forLevel(b).pulses })
        assertTrue(FlashPattern.entries.all { it.flashesPerSecond <= 3f })
        assertFalse(FlashPattern.forLevel(AlertLevel.INFO).fullScreen, "안내는 테두리만")
        val p = FlashPattern.SINGLE
        assertEquals(0f, p.alphaAt(0), 0.01f)
        assertEquals(1f, p.alphaAt(p.durationMs / 2L), 0.01f)
    }

    @Test
    fun `speech is short, plain and keyword-first`() {
        for (t in AlertType.entries) {
            assertTrue(t.speechKo.length <= t.messageKo.length, "${t.name} 음성이 화면 문장보다 길면 안 됨")
            assertTrue(t.headlineKo.length <= 6, "${t.name} 깜빡임 제목은 한눈에: ${t.headlineKo}")
        }
        val signalKeywords = listOf("빨간불", "노란불", "초록불", "좌회전", "신호등")
        AlertType.entries.filter { it.category == AlertCategory.SIGNAL }.forEach { t ->
            assertTrue(signalKeywords.any { t.speechKo.startsWith(it) }, "${t.name}: ${t.speechKo}")
        }
        assertTrue(AlertType.RED_URGENT.speechKo.length <= 12)
    }

    @Test
    fun `urgent speech is faster, elderly speech slower`() {
        val urgent = DriverProfile.COLOR_VISION_ONLY.outputFor(Alert(AlertType.RED_URGENT, 0))
        val elderly = DriverProfile(setOf(DriverNeed.ATTENTION)).outputFor(Alert(AlertType.GREEN_REMINDER, 0))
        assertTrue(urgent.speechRate > 1f && elderly.speechRate < 1f)
    }

    @Test
    fun `reaction time follows the profile and moves urgent warnings earlier`() {
        assertEquals(1.0f, DriverProfile.COLOR_VISION_ONLY.reactionTimeS)
        assertEquals(1.5f, DriverProfile(setOf(DriverNeed.COLOR_VISION, DriverNeed.ATTENTION)).reactionTimeS)
        assertEquals(1.3f, DriverProfile(setOf(DriverNeed.HEARING)).reactionTimeS, 0.001f)

        fun firstUrgent(p: DriverProfile): Long {
            val e = DrivingAssistEngine(p)
            return ScenarioPlayer(Scenario.RED_LIGHT_RISK).samples()
                .flatMap { e.process(it.toInput()).alerts }.first { it.type == AlertType.RED_URGENT }.timestampMs
        }
        val normal = firstUrgent(DriverProfile.COLOR_VISION_ONLY)
        val elderly = firstUrgent(DriverProfile(setOf(DriverNeed.COLOR_VISION, DriverNeed.ATTENTION)))
        assertTrue(elderly < normal, "고령 $elderly ms < 일반 $normal ms")
    }

    @Test
    fun `ongoing hazards stay on screen until resolved`() {
        val e = DrivingAssistEngine(DriverProfile(setOf(DriverNeed.HEARING)))
        val byTime = ScenarioPlayer(Scenario.HEARING_DRIVE).samples().associate { it.timestampMs to e.process(it.toInput()).activeHazards }
        assertTrue(AlertType.SEATBELT_UNBUCKLED in byTime.getValue(5_000), "벨트 미착용 중 계속 표시")
        assertTrue(AlertType.SEATBELT_UNBUCKLED !in byTime.getValue(13_000), "벨트를 매면 사라짐")
        assertTrue(AlertType.SIREN_NEARBY in byTime.getValue(32_000), "사이렌이 들리는 동안 계속 표시 (알림은 29초에 한 번)")
        assertTrue(AlertType.SIREN_NEARBY !in byTime.getValue(34_000))
        assertTrue(AlertType.TURN_SIGNAL_LEFT_ON in byTime.getValue(38_000), "방향지시등을 끌 때까지 표시")
    }
}
