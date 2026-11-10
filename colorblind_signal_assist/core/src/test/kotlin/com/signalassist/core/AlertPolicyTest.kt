package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertPolicyTest {
    private fun st(c: SignalColor, arrow: Boolean = false) = SignalState(c, arrow)
    private fun v(speed: Float, gear: Gear = Gear.DRIVE) = VehicleState(speed, gear)

    @Test
    fun `green after red while stopped announces go, then reminds once`() {
        val p = AlertPolicy()
        p.evaluate(st(SignalColor.RED), null, v(0f), 0)
        val a = p.evaluate(st(SignalColor.GREEN), StateChange(st(SignalColor.RED), st(SignalColor.GREEN)), v(0f), 1_000)
        assertEquals(listOf(AlertType.GREEN_GO), a.map { it.type })
        val later = (1..50).flatMap { p.evaluate(st(SignalColor.GREEN), null, v(0f), 1_000L + it * 100) }
        assertEquals(listOf(AlertType.GREEN_REMINDER), later.map { it.type })
    }

    @Test
    fun `no reminder when driver already pulled away`() {
        val p = AlertPolicy()
        p.evaluate(st(SignalColor.GREEN), StateChange(st(SignalColor.RED), st(SignalColor.GREEN)), v(0f), 0)
        val later = (1..50).flatMap { p.evaluate(st(SignalColor.GREEN), null, v(10f), it * 100L) }
        assertTrue(later.isEmpty())
    }

    @Test
    fun `red at speed without braking repeats urgent warning`() {
        val p = AlertPolicy()
        val first = p.evaluate(st(SignalColor.RED), StateChange(st(SignalColor.YELLOW), st(SignalColor.RED)), v(45f), 0)
        assertEquals(listOf(AlertType.RED_URGENT), first.map { it.type })
        val repeats = (1..70).flatMap { p.evaluate(st(SignalColor.RED), null, v(45f), it * 100L) }
        assertEquals(2, repeats.size) // 3s, 6s
    }

    @Test
    fun `urgent warning stops once driver brakes hard`() {
        val p = AlertPolicy()
        p.evaluate(st(SignalColor.RED), StateChange(st(SignalColor.YELLOW), st(SignalColor.RED)), v(45f), 0)
        // 1초에 15km/h 씩 감속
        val alerts = (1..30).flatMap { p.evaluate(st(SignalColor.RED), null, v((45f - it * 1.5f).coerceAtLeast(0f)), it * 100L) }
        assertTrue(alerts.none { it.type == AlertType.RED_URGENT })
    }

    @Test
    fun `stopped at red is silent`() {
        val p = AlertPolicy()
        val a = p.evaluate(st(SignalColor.RED), StateChange(st(SignalColor.GREEN), st(SignalColor.RED)), v(0f), 0)
        assertTrue(a.isEmpty())
    }

    @Test
    fun `parked suppresses everything`() {
        val p = AlertPolicy()
        val a = p.evaluate(st(SignalColor.RED), StateChange(st(SignalColor.GREEN), st(SignalColor.RED)), v(40f, Gear.PARK), 0)
        assertTrue(a.isEmpty())
    }

    @Test
    fun `left arrow announced while stopped`() {
        val p = AlertPolicy()
        val a = p.evaluate(st(SignalColor.RED, true), StateChange(st(SignalColor.RED), st(SignalColor.RED, true)), v(0f), 0)
        assertEquals(listOf(AlertType.LEFT_ARROW_ON), a.map { it.type })
    }
}
