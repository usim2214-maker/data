package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SignalAlertPolicyTest {
    private fun st(c: SignalColor, arrow: Boolean = false) = SignalState(c, arrow)
    private fun v(speed: Float, gear: Gear = Gear.DRIVE) = VehicleState(speed, gear)

    @Test
    fun `green after red while stopped announces go, then reminds once`() {
        val p = SignalAlertPolicy()
        p.evaluate(st(SignalColor.RED), null, v(0f), 0)
        val a = p.evaluate(st(SignalColor.GREEN), StateChange(st(SignalColor.RED), st(SignalColor.GREEN)), v(0f), 1_000)
        assertEquals(listOf(AlertType.GREEN_GO), a.map { it.type })
        val later = (1..50).flatMap { p.evaluate(st(SignalColor.GREEN), null, v(0f), 1_000L + it * 100) }
        assertEquals(listOf(AlertType.GREEN_REMINDER), later.map { it.type })
    }

    @Test
    fun `no reminder when driver already pulled away`() {
        val p = SignalAlertPolicy()
        p.evaluate(st(SignalColor.GREEN), StateChange(st(SignalColor.RED), st(SignalColor.GREEN)), v(0f), 0)
        val later = (1..50).flatMap { p.evaluate(st(SignalColor.GREEN), null, v(10f), it * 100L) }
        assertTrue(later.isEmpty())
    }

    @Test
    fun `red at speed without braking repeats urgent warning`() {
        val p = SignalAlertPolicy()
        val first = p.evaluate(st(SignalColor.RED), StateChange(st(SignalColor.YELLOW), st(SignalColor.RED)), v(45f), 0)
        assertEquals(listOf(AlertType.RED_URGENT), first.map { it.type })
        val repeats = (1..70).flatMap { p.evaluate(st(SignalColor.RED), null, v(45f), it * 100L) }
        assertEquals(2, repeats.size) // 3s, 6s
    }

    @Test
    fun `urgent warning stops once driver brakes hard`() {
        val p = SignalAlertPolicy()
        p.evaluate(st(SignalColor.RED), StateChange(st(SignalColor.YELLOW), st(SignalColor.RED)), v(45f), 0)
        // 1초에 15km/h 씩 감속
        val alerts = (1..30).flatMap { p.evaluate(st(SignalColor.RED), null, v((45f - it * 1.5f).coerceAtLeast(0f)), it * 100L) }
        assertTrue(alerts.none { it.type == AlertType.RED_URGENT })
    }

    @Test
    fun `stopped at red is silent`() {
        val p = SignalAlertPolicy()
        val a = p.evaluate(st(SignalColor.RED), StateChange(st(SignalColor.GREEN), st(SignalColor.RED)), v(0f), 0)
        assertTrue(a.isEmpty())
    }

    @Test
    fun `parked suppresses everything`() {
        val p = SignalAlertPolicy()
        val a = p.evaluate(st(SignalColor.RED), StateChange(st(SignalColor.GREEN), st(SignalColor.RED)), v(40f, Gear.PARK), 0)
        assertTrue(a.isEmpty())
    }

    @Test
    fun `left arrow announced while stopped`() {
        val p = SignalAlertPolicy()
        val a = p.evaluate(st(SignalColor.RED, true), StateChange(st(SignalColor.RED), st(SignalColor.RED, true)), v(0f), 0)
        assertEquals(listOf(AlertType.LEFT_ARROW_ON), a.map { it.type })
    }

    @Test
    fun `unknown gear (Pleos has no gear API) still warns by speed`() {
        val p = SignalAlertPolicy()
        val a = p.evaluate(st(SignalColor.RED), StateChange(st(SignalColor.YELLOW), st(SignalColor.RED)), v(45f, Gear.UNKNOWN), 0)
        assertEquals(listOf(AlertType.RED_URGENT), a.map { it.type })
    }

    @Test
    fun `left arrow is interpreted against the planned route`() {
        val change = StateChange(st(SignalColor.RED), st(SignalColor.RED, true))
        fun typeFor(nav: NavigationState?) =
            SignalAlertPolicy().evaluate(st(SignalColor.RED, true), change, v(0f), 0, nav).single().type
        assertEquals(AlertType.LEFT_ARROW_FOR_ROUTE, typeFor(NavigationState(true, Maneuver.LEFT, 40f)))
        assertEquals(AlertType.LEFT_ARROW_NOT_FOR_ROUTE, typeFor(NavigationState(true, Maneuver.LEFT, 900f)))
        assertEquals(AlertType.LEFT_ARROW_NOT_FOR_ROUTE, typeFor(NavigationState(true, Maneuver.RIGHT, 40f)))
        assertEquals(AlertType.LEFT_ARROW_ON, typeFor(null))
    }

    @Test
    fun `distance-aware - far red at speed is a normal stop warning, close red is urgent`() {
        val change = StateChange(st(SignalColor.YELLOW), st(SignalColor.RED))
        // 45km/h, 신호등 90m (정지선 78m): 필요한 감속 1.0 m/s² -> 일반 정지 안내
        val far = SignalAlertPolicy().evaluate(st(SignalColor.RED), change, v(45f), 0, null, 90f)
        assertEquals(listOf(AlertType.RED_STOP), far.map { it.type })
        // 45km/h, 신호등 40m (정지선 28m): 필요한 감속 2.8 m/s² -> 긴급
        val near = SignalAlertPolicy().evaluate(st(SignalColor.RED), change, v(45f), 0, null, 40f)
        assertEquals(listOf(AlertType.RED_URGENT), near.map { it.type })
        // 20km/h 인데 15m 앞 (정지선 3m): 저속이어도 필요한 감속 5.1 m/s² -> 긴급
        val slowButClose = SignalAlertPolicy().evaluate(st(SignalColor.RED), change, v(20f), 0, null, 15f)
        assertEquals(listOf(AlertType.RED_URGENT), slowButClose.map { it.type })
    }
}
