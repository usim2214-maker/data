package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlertArbiterTest {
    private val cv = DriverProfile(setOf(DriverNeed.COLOR_VISION, DriverNeed.ATTENTION))

    @Test
    fun `only the most important of simultaneous alerts interrupts`() {
        val a = AlertArbiter().arbitrate(
            listOf(Alert(AlertType.NAV_MANEUVER_SOON, 0), Alert(AlertType.HEADLIGHTS_OFF_DARK, 0)), cv, 0,
        )
        assertEquals(AlertType.HEADLIGHTS_OFF_DARK, a.first().alert.type)
        assertTrue(a.first().plan.voice)
        assertTrue(a[1].plan.silent, "두 번째 알림은 화면 표시만")
    }

    @Test
    fun `warnings are never silenced`() {
        val a = AlertArbiter().arbitrate(
            listOf(Alert(AlertType.RED_URGENT, 0), Alert(AlertType.SEATBELT_UNBUCKLED, 0)), cv, 0,
        )
        assertTrue(a.all { it.plan.voice })
    }

    @Test
    fun `quiet window after urgent`() {
        val arb = AlertArbiter()
        arb.arbitrate(listOf(Alert(AlertType.RED_URGENT, 0)), cv, 0)
        val info = arb.arbitrate(listOf(Alert(AlertType.NAV_MANEUVER_SOON, 3_000)), cv, 3_000)
        assertTrue(info.single().plan.silent)
        val later = arb.arbitrate(listOf(Alert(AlertType.NAV_MANEUVER_SOON, 5_000)), cv, 5_000)
        assertFalse(later.single().plan.silent)
    }

    @Test
    fun `voice gap for minor alerts`() {
        val arb = AlertArbiter()
        arb.arbitrate(listOf(Alert(AlertType.GREEN_GO, 0)), cv, 0)
        assertTrue(arb.arbitrate(listOf(Alert(AlertType.LEFT_ARROW_ON, 1_000)), cv, 1_000).single().plan.silent)
        assertFalse(arb.arbitrate(listOf(Alert(AlertType.LEFT_ARROW_ON, 3_500)), cv, 3_500).single().plan.silent)
    }

    @Test
    fun `flash is dimmed at night`() {
        val hearing = DriverProfile(setOf(DriverNeed.HEARING))
        val d = AlertArbiter().arbitrate(listOf(Alert(AlertType.SEATBELT_UNBUCKLED, 0)), hearing, 0, isDark = true).single()
        assertTrue(d.plan.visualFlash && d.plan.dimmed)
    }
}
