package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertGuideTest {
    @Test
    fun `lessons follow the profile`() {
        val cv = AlertGuide.lessonsFor(DriverProfile.COLOR_VISION_ONLY).map { it.type }
        assertTrue(AlertType.GREEN_GO in cv && AlertType.RED_STOP in cv && AlertType.SIGNAL_LOST in cv)
        assertFalse(AlertType.SIREN_NEARBY in cv)
        val hearing = AlertGuide.lessonsFor(DriverProfile(setOf(DriverNeed.HEARING))).map { it.type }
        assertTrue(AlertType.SIREN_NEARBY in hearing && AlertType.SEATBELT_UNBUCKLED in hearing)
        assertFalse(AlertType.GREEN_GO in hearing)
    }

    @Test
    fun `every lesson explains its cue in one sentence`() {
        val all = AlertGuide.lessonsFor(DriverProfile.ALL)
        assertTrue(all.all { it.explanationKo.endsWith("다.") && it.explanationKo.length < 60 })
    }

    @Test
    fun `replay brings back a silenced alert with full output`() {
        val h = AlertHistory()
        assertNull(h.replayLast(DriverProfile.COLOR_VISION_ONLY, 0))
        val silenced = OutputPlan(tone = false, voice = false, visualFlash = false, largeText = false)
        h.record(listOf(Delivery(Alert(AlertType.LEFT_ARROW_ON, 100), silenced)))
        val r = h.replayLast(DriverProfile.COLOR_VISION_ONLY, 5_000)!!
        assertEquals(AlertType.LEFT_ARROW_ON, r.alert.type)
        assertTrue(r.plan.voice && r.plan.tone)
        assertEquals(5_000, r.alert.timestampMs)
        val deaf = h.replayLast(DriverProfile(setOf(DriverNeed.HEARING)), 6_000)!!
        assertTrue(deaf.plan.visualFlash && !deaf.plan.voice)
    }
}
