package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VehicleAlertPolicyTest {
    private fun v(speed: Float, gear: Gear = Gear.UNKNOWN) = VehicleState(speed, gear)

    /** 0.1초 간격으로 [seconds] 초 동안 같은 상태를 흘려 나온 알림 */
    private fun run(p: VehicleAlertPolicy, cabin: CabinState, vehicle: VehicleState, seconds: Int, start: Long = 0,
                    nav: NavigationState? = null) =
        (0 until seconds * 10).flatMap { p.evaluate(cabin, vehicle, start + it * 100L, nav) }

    @Test
    fun `seatbelt warning only while moving, repeats every 15s`() {
        val p = VehicleAlertPolicy()
        assertTrue(run(p, CabinState(driverSeatbeltBuckled = false), v(0f), 5).isEmpty())
        val moving = run(p, CabinState(driverSeatbeltBuckled = false), v(40f), 31, start = 10_000)
        assertEquals(3, moving.count { it.type == AlertType.SEATBELT_UNBUCKLED }) // 0s, 15s, 30s
    }

    @Test
    fun `door open while moving is urgent`() {
        val a = run(VehicleAlertPolicy(), CabinState(doorOpen = true), v(20f), 1)
        assertEquals(AlertLevel.URGENT, a.single().level)
    }

    @Test
    fun `low tire pressure announced once until cleared`() {
        val p = VehicleAlertPolicy()
        assertEquals(1, run(p, CabinState(lowTirePressure = true), v(50f), 30).size)
        run(p, CabinState(lowTirePressure = false), v(50f), 1, start = 30_000)
        assertEquals(1, run(p, CabinState(lowTirePressure = true), v(50f), 5, start = 31_000).size)
    }

    @Test
    fun `forgotten turn signal after 20s at speed, but not near a nav maneuver`() {
        val p = VehicleAlertPolicy()
        val a = run(p, CabinState(turnSignal = TurnSignal.LEFT), v(60f), 25)
        assertEquals(listOf(AlertType.TURN_SIGNAL_LEFT_ON), a.map { it.type })
        assertTrue(a.single().timestampMs in 19_900..20_100)

        val nearTurn = NavigationState(true, Maneuver.LEFT, 150f)
        assertTrue(run(VehicleAlertPolicy(), CabinState(turnSignal = TurnSignal.LEFT), v(60f), 25, nav = nearTurn).isEmpty())
        // 저속 (정체 중 차로 변경 대기)에는 알리지 않음
        assertTrue(run(VehicleAlertPolicy(), CabinState(turnSignal = TurnSignal.RIGHT), v(15f), 25).isEmpty())
    }

    @Test
    fun `headlights off in the dark`() {
        val a = run(VehicleAlertPolicy(), CabinState(isDark = true, headlightsOn = false), v(30f), 10)
        assertEquals(listOf(AlertType.HEADLIGHTS_OFF_DARK), a.map { it.type })
        assertTrue(run(VehicleAlertPolicy(), CabinState(isDark = false, headlightsOn = false), v(30f), 10).isEmpty())
    }

    @Test
    fun `rear seat reminder when trip ends by park or door open`() {
        val p = VehicleAlertPolicy()
        run(p, CabinState(rearSeatOccupied = true, doorOpen = false), v(40f), 5)
        val parked = run(p, CabinState(rearSeatOccupied = true, doorOpen = false), v(0f, Gear.PARK), 3, start = 5_000)
        assertEquals(listOf(AlertType.REAR_SEAT_CHECK), parked.map { it.type })

        // 기어를 모를 때는 운전석 문이 열리는 순간으로 판단
        val q = VehicleAlertPolicy()
        run(q, CabinState(rearSeatOccupied = true, doorOpen = false), v(40f), 5)
        assertTrue(run(q, CabinState(rearSeatOccupied = true, doorOpen = false), v(0f), 3, start = 5_000).isEmpty())
        val door = run(q, CabinState(rearSeatOccupied = true, doorOpen = true), v(0f), 3, start = 8_000)
        assertEquals(listOf(AlertType.REAR_SEAT_CHECK), door.map { it.type })
    }

    @Test
    fun `features can be disabled by profile`() {
        val a = VehicleAlertPolicy().evaluate(
            CabinState(driverSeatbeltBuckled = false), v(40f), 0, null, setOf(VehicleFeature.REAR_SEAT),
        )
        assertTrue(a.isEmpty())
    }
}
