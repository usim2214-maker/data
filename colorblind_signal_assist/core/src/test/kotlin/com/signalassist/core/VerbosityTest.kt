package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerbosityTest {
    private fun cv(v: Verbosity, muted: Set<AlertType> = emptySet()) =
        DriverProfile(setOf(DriverNeed.COLOR_VISION), AlertPreferences(verbosity = v, muted = muted))

    @Test
    fun `minimal keeps only safety-critical alerts`() {
        val p = cv(Verbosity.MINIMAL)
        assertFalse(p.accepts(Alert(AlertType.GREEN_GO, 0)))
        assertFalse(p.accepts(Alert(AlertType.NAV_MANEUVER_SOON, 0)))
        assertTrue(p.accepts(Alert(AlertType.RED_STOP, 0)))
        assertTrue(p.accepts(Alert(AlertType.YELLOW_DILEMMA, 0)))
        assertTrue(p.accepts(Alert(AlertType.SIGNAL_LOST, 0)))
        assertTrue(p.accepts(Alert(AlertType.SEATBELT_UNBUCKLED, 0)))
    }

    @Test
    fun `safety-critical alerts cannot be muted`() {
        assertFalse(AlertType.RED_URGENT.mutable)
        assertFalse(AlertType.YELLOW_CAUTION.mutable)
        assertFalse(AlertType.SIREN_NEARBY.mutable)
        assertTrue(AlertType.LOW_TIRE_PRESSURE.mutable)
        val p = cv(Verbosity.NORMAL, muted = setOf(AlertType.LOW_TIRE_PRESSURE, AlertType.RED_URGENT))
        assertFalse(p.accepts(Alert(AlertType.LOW_TIRE_PRESSURE, 0)))
        assertTrue(p.accepts(Alert(AlertType.RED_URGENT, 0)), "긴급 경고는 꺼도 무시")
    }

    @Test
    fun `normal adds the meaning of the tone for the first three times only`() {
        val h = HintTracker()
        val plan = DriverProfile.COLOR_VISION_ONLY.outputFor(Alert(AlertType.GREEN_GO, 0))
        val speeches = (1..5).map { h.decorate(listOf(Delivery(Alert(AlertType.GREEN_GO, it * 1000L), plan)), AlertPreferences()).single().alert.speech }
        assertEquals(3, speeches.count { "두 음이 올라가면" in it }, "$speeches")
        assertEquals("초록불입니다.", speeches.last())
    }

    @Test
    fun `detailed always explains, minimal never, deaf drivers get no spoken hint`() {
        val plan = DriverProfile.COLOR_VISION_ONLY.outputFor(Alert(AlertType.RED_STOP, 0))
        val d = listOf(Delivery(Alert(AlertType.RED_STOP, 0), plan))
        val detailed = HintTracker().apply { counts[AlertType.RED_STOP] = 10 }
        assertTrue("세 음이 내려가면" in detailed.decorate(d, AlertPreferences(verbosity = Verbosity.DETAILED)).single().alert.speech)
        assertFalse("세 음" in HintTracker().decorate(d, AlertPreferences(verbosity = Verbosity.MINIMAL)).single().alert.speech)
        val deafPlan = DriverProfile(setOf(DriverNeed.HEARING)).outputFor(Alert(AlertType.SEATBELT_UNBUCKLED, 0))
        val deaf = HintTracker().decorate(listOf(Delivery(Alert(AlertType.SEATBELT_UNBUCKLED, 0), deafPlan)), AlertPreferences())
        assertEquals(null, deaf.single().alert.speechSuffix)
    }

    @Test
    fun `minimal scenario run drops green-go but keeps the urgent warning`() {
        fun types(v: Verbosity, s: Scenario): List<AlertType> {
            val e = DrivingAssistEngine(cv(v))
            return ScenarioPlayer(s).samples().flatMap { e.process(it.toInput()).alerts }.map { it.type }.toList()
        }
        assertTrue(AlertType.GREEN_GO !in types(Verbosity.MINIMAL, Scenario.STOP_AND_GO))
        assertTrue(AlertType.YELLOW_CAUTION in types(Verbosity.MINIMAL, Scenario.STOP_AND_GO))
        assertTrue(AlertType.RED_URGENT in types(Verbosity.MINIMAL, Scenario.RED_LIGHT_RISK))
    }
}
