package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationAndProfileTest {

    @Test
    fun `announces at 300m and 100m once per maneuver`() {
        val p = NavigationAlertPolicy()
        val msgs = (0..60).flatMap { i ->
            val d = 500f - i * 10f // 500m -> -100m
            val nav = if (d > 0) NavigationState(true, Maneuver.LEFT, d) else NavigationState(true, Maneuver.RIGHT, 800f + d)
            p.evaluate(nav, i * 500L)
        }.map { it.message }
        assertEquals(listOf("300m 앞 좌회전", "100m 앞 좌회전"), msgs)
    }

    @Test
    fun `first seen inside both thresholds announces once`() {
        val p = NavigationAlertPolicy()
        val a = p.evaluate(NavigationState(true, Maneuver.DESTINATION, 85f), 0) +
            p.evaluate(NavigationState(true, Maneuver.DESTINATION, 80f), 100)
        assertEquals(listOf("90m 앞 목적지"), a.map { it.message })
    }

    @Test
    fun `hearing-only profile uses visual output instead of sound`() {
        val alert = Alert(AlertType.SEATBELT_UNBUCKLED, 0)
        val plan = DriverProfile(setOf(DriverNeed.HEARING)).outputFor(alert)
        assertFalse(plan.tone); assertFalse(plan.voice); assertTrue(plan.visualFlash)
        val cv = DriverProfile.COLOR_VISION_ONLY.outputFor(Alert(AlertType.GREEN_GO, 0))
        assertTrue(cv.tone); assertTrue(cv.voice); assertFalse(cv.visualFlash)
        assertTrue(DriverProfile(setOf(DriverNeed.LOW_VISION)).outputFor(alert).largeText)
    }

    @Test
    fun `attention-only profile keeps only risk and reminder signal alerts`() {
        val p = DriverProfile(setOf(DriverNeed.ATTENTION))
        assertFalse(p.accepts(Alert(AlertType.GREEN_GO, 0)))
        assertFalse(p.accepts(Alert(AlertType.YELLOW_CAUTION, 0)))
        assertTrue(p.accepts(Alert(AlertType.GREEN_REMINDER, 0)))
        assertTrue(p.accepts(Alert(AlertType.RED_URGENT, 0)))
        assertTrue(p.accepts(Alert(AlertType.SEATBELT_UNBUCKLED, 0)))
    }

    @Test
    fun `feature switches follow needs`() {
        val cv = DriverProfile.COLOR_VISION_ONLY
        assertTrue(cv.signalAssist); assertFalse(cv.navigationCards)
        assertEquals(setOf(VehicleFeature.REAR_SEAT), cv.vehicleFeatures)
        val hearing = DriverProfile(setOf(DriverNeed.HEARING))
        assertFalse(hearing.signalAssist); assertTrue(hearing.navigationCards)
        assertTrue(VehicleFeature.TURN_SIGNAL in hearing.vehicleFeatures)
    }
}
