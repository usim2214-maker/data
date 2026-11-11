package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingTest {
    private val all = HearingProbe.entries.associateWith { true }
    private fun ans(vararg yes: OnboardingQuestion) = OnboardingQuestion.entries.associateWith { it in yes }

    @Test
    fun `answers become needs`() {
        val p = Onboarding.build(ans(OnboardingQuestion.COLOR, OnboardingQuestion.ATTENTION), all)
        assertEquals(setOf(DriverNeed.COLOR_VISION, DriverNeed.ATTENTION), p.needs)
        assertEquals(AlertPreferences(), p.prefs)
    }

    @Test
    fun `fully deaf driver gets visual-only alerts`() {
        val p = Onboarding.build(ans(OnboardingQuestion.HEARING), HearingProbe.entries.associateWith { false })
        val plan = p.outputFor(Alert(AlertType.SEATBELT_UNBUCKLED, 0))
        assertFalse(plan.tone); assertFalse(plan.voice); assertTrue(plan.visualFlash)
    }

    @Test
    fun `partially deaf driver who hears low loud tones keeps them, plus flashes`() {
        val heard = mapOf(HearingProbe.NORMAL to false, HearingProbe.LOW to true, HearingProbe.QUIET to false)
        val p = Onboarding.build(ans(OnboardingQuestion.HEARING), heard)
        val plan = p.outputFor(Alert(AlertType.SIREN_NEARBY, 0))
        assertTrue(plan.tone && plan.lowPitchTones && plan.visualFlash)
        assertFalse(plan.voice, "말소리는 알아듣기 어려우므로 음성은 끔")
        assertTrue(plan.toneGain > DriverProfile(setOf(DriverNeed.HEARING)).copy(prefs = AlertPreferences(soundForHearing = true))
            .outputFor(Alert(AlertType.SIREN_NEARBY, 0)).toneGain, "작은 소리를 못 들었으니 크게")
        assertEquals(1.0f, p.reactionTimeS, "소리로도 받으므로 화면 전용 지연(+0.3초)은 없음")
    }

    @Test
    fun `boosted volume never exceeds full scale and keeps urgency order`() {
        val p = DriverProfile(setOf(DriverNeed.COLOR_VISION), AlertPreferences(volumeBoost = 1.25f))
        val gains = AlertLevel.entries.map { lvl -> p.outputFor(Alert(AlertType.entries.first { it.level == lvl }, 0)).toneGain }
        assertTrue(gains.all { it <= 1f } && gains.zipWithNext().all { (a, b) -> a < b }, "$gains")
    }

    @Test
    fun `driver who heard nothing gets visual alerts even without saying so`() {
        val p = Onboarding.build(ans(OnboardingQuestion.COLOR), HearingProbe.entries.associateWith { false })
        assertTrue(p.has(DriverNeed.HEARING))
    }
}
