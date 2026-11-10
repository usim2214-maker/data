package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TripRecorderTest {
    private fun summarize(s: Scenario): List<TripSummary> {
        val engine = DrivingAssistEngine(DriverProfile(s.targetNeeds))
        val rec = TripRecorder()
        return ScenarioPlayer(s).samples().mapNotNull { rec.update(engine.process(it.toInput())) }.toList()
    }

    @Test
    fun `trip ends on park and counts alerts`() {
        val trips = summarize(Scenario.NIGHT_ARRIVAL)
        assertEquals(1, trips.size)
        val t = trips.single()
        assertEquals(1, t.alertCounts[AlertType.REAR_SEAT_CHECK])
        assertEquals(1, t.alertCounts[AlertType.HEADLIGHTS_OFF_DARK])
        assertTrue(t.distanceKm in 0.07f..0.11f, "distance ${t.distanceKm}")
        val text = t.summaryKo()
        assertTrue("뒷좌석 확인 1회" in text && "긴급 경고 없이" in text, text)
    }

    @Test
    fun `urgent warnings are reflected in the summary`() {
        val engine = DrivingAssistEngine(DriverProfile.COLOR_VISION_ONLY)
        val rec = TripRecorder()
        ScenarioPlayer(Scenario.RED_LIGHT_RISK).samples().forEach { rec.update(engine.process(it.toInput())) }
        val t = assertNotNull(rec.finish(11_000))
        assertTrue(t.urgentCount >= 1)
        assertTrue("긴급 경고 ${t.urgentCount}회" in t.summaryKo())
    }
}
