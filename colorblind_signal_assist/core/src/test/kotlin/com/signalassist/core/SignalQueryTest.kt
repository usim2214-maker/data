package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalQueryTest {
    private fun out(c: SignalColor, conf: Float, d: Float? = 27f, arrow: Boolean = false, t: Long = 1_000) =
        EngineOutput(t, FrameDetection(c, arrow, emptyList()), SignalState(c, arrow), null, VehicleState(0f), emptyList(), d, conf)

    @Test
    fun `certain answer includes distance and arrow, with the signal's own tone`() {
        val a = SignalQuery.answer(out(SignalColor.RED, 1f, arrow = true), 1_500)
        assertEquals("지금은 빨간불입니다. 신호등까지 약 25미터. 좌회전 신호도 켜져 있습니다.", a.speech)
        assertEquals(TonePattern.FALLING_TRIPLE, a.tone)
        assertEquals("빨간불", a.headline)
    }

    @Test
    fun `uncertain answer says so and asks to check`() {
        val a = SignalQuery.answer(out(SignalColor.GREEN, 0.6f), 1_500)
        assertTrue(a.speech.startsWith("초록불로 보이지만 확실하지 않습니다"), a.speech)
        assertEquals(TonePattern.SOFT_CHIME, a.tone, "애매할 때 '진행' 소리를 내지 않음")
    }

    @Test
    fun `unknown or stale signal is never guessed`() {
        assertTrue("직접 확인" in SignalQuery.answer(null, 0).speech)
        assertTrue("직접 확인" in SignalQuery.answer(out(SignalColor.UNKNOWN, 0f), 1_500).speech)
        assertTrue("직접 확인" in SignalQuery.answer(out(SignalColor.RED, 1f, t = 1_000), 5_000).speech, "3초 넘게 지난 정보")
    }

    @Test
    fun `deaf drivers see the answer as a flash`() {
        val a = SignalQuery.answer(out(SignalColor.RED, 1f), 1_500)
        val plan = SignalQuery.plan(DriverProfile(setOf(DriverNeed.HEARING, DriverNeed.COLOR_VISION)), a)
        assertTrue(plan.visualFlash && plan.flashPattern.fullScreen)
    }

    @Test
    fun `confidence reflects agreement over recent frames in a real scenario`() {
        val e = DrivingAssistEngine(DriverProfile.COLOR_VISION_ONLY)
        val outs = ScenarioPlayer(Scenario.STOP_AND_GO).samples().map { e.process(it.toInput()) }.toList()
        val waitingAtRed = outs.first { it.input.timestampMs == 10_000L }.signal!!
        assertEquals(SignalColor.RED, waitingAtRed.state.color)
        assertTrue(waitingAtRed.confidence >= 0.8f)
        assertTrue(SignalQuery.answer(waitingAtRed, 10_000).speech.startsWith("지금은 빨간불입니다"))
    }
}
