package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 합성 시나리오 전체를 엔진에 흘려 기대 알림이 나오는지 확인하는 E2E 테스트 */
class ScenarioTest {
    private fun run(s: Scenario, profile: DriverProfile = DriverProfile(s.targetNeeds)): List<Alert> {
        val engine = DrivingAssistEngine(profile)
        return ScenarioPlayer(s).samples().flatMap { engine.process(it.toInput()).alerts }.toList()
    }

    private fun List<Alert>.types() = map { it.type }

    @Test
    fun `stop and go`() {
        val alerts = run(Scenario.STOP_AND_GO, DriverProfile.COLOR_VISION_ONLY)
        val types = alerts.types()
        assertTrue(AlertType.YELLOW_CAUTION in types, "$types")
        assertTrue(AlertType.GREEN_GO in types, "$types")
        assertTrue(AlertType.GREEN_REMINDER in types, "$types")
        assertTrue(AlertType.RED_URGENT !in types, "$types")
        val go = alerts.first { it.type == AlertType.GREEN_GO }
        assertTrue(go.timestampMs in 13_000..14_000, "GREEN_GO at ${go.timestampMs}")
    }

    @Test
    fun `red light risk triggers urgent warning before braking`() {
        val alerts = run(Scenario.RED_LIGHT_RISK)
        val urgent = alerts.filter { it.type == AlertType.RED_URGENT }
        assertTrue(urgent.isNotEmpty(), "${alerts.types()}")
        assertTrue(urgent.first().timestampMs < 5_000, "first urgent at ${urgent.first().timestampMs}")
        assertTrue(alerts.none { it.type == AlertType.GREEN_GO })
    }

    @Test
    fun `night left turn matches the planned route`() {
        val types = run(Scenario.NIGHT_LEFT_TURN).types()
        assertTrue(AlertType.LEFT_ARROW_FOR_ROUTE in types, "$types")
        assertTrue(AlertType.GREEN_GO in types, "$types")
        assertTrue(AlertType.RED_URGENT !in types, "$types")
    }

    @Test
    fun `hearing drive turns sounds into visual alerts`() {
        val profile = DriverProfile(setOf(DriverNeed.HEARING))
        val alerts = run(Scenario.HEARING_DRIVE, profile)
        val types = alerts.types()
        assertEquals(1, types.count { it == AlertType.SEATBELT_UNBUCKLED }, "$types")
        assertTrue(alerts.any { it.message == "100m 앞 좌회전 (강남대로)" }, alerts.map { it.message }.toString())
        val turn = alerts.single { it.type == AlertType.TURN_SIGNAL_LEFT_ON }
        assertTrue(turn.timestampMs in 34_000..36_000, "turn signal at ${turn.timestampMs}")
        assertTrue(AlertType.LOW_TIRE_PRESSURE in types, "$types")
        // 신호등이 없는 구간이고 색각 기능도 꺼져 있으므로 신호 알림은 없어야 함
        assertTrue(alerts.none { it.category == AlertCategory.SIGNAL }, "$types")
        assertTrue(alerts.all { !profile.outputFor(it).tone && profile.outputFor(it).visualFlash })
    }

    @Test
    fun `night arrival reminds headlights and rear seat`() {
        val alerts = run(Scenario.NIGHT_ARRIVAL)
        val types = alerts.types()
        assertTrue(AlertType.HEADLIGHTS_OFF_DARK in types, "$types")
        val rear = alerts.single { it.type == AlertType.REAR_SEAT_CHECK }
        assertTrue(rear.timestampMs in 15_000..15_500, "rear at ${rear.timestampMs}")
        assertTrue(alerts.any { it.message.endsWith("앞 목적지") }, alerts.map { it.message }.toString())
    }
}
