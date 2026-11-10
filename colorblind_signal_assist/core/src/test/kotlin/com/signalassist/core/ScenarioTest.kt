package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertTrue

/** 합성 시나리오 전체를 엔진에 흘려 기대 알림이 나오는지 확인하는 E2E 테스트 */
class ScenarioTest {
    private fun run(s: Scenario): List<Alert> {
        val engine = SignalAssistEngine()
        return ScenarioPlayer(s).samples().flatMap { engine.process(it.frame, it.vehicle, it.timestampMs).alerts }.toList()
    }

    private fun List<Alert>.types() = map { it.type }

    @Test
    fun `stop and go`() {
        val alerts = run(Scenario.STOP_AND_GO)
        val types = alerts.types()
        assertTrue(AlertType.YELLOW_CAUTION in types, "$types")
        assertTrue(AlertType.GREEN_GO in types, "$types")
        assertTrue(AlertType.GREEN_REMINDER in types, "$types")
        assertTrue(AlertType.RED_URGENT !in types, "$types")
        // 초록불(13s) 이후 1초 이내 안내
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
    fun `night left turn`() {
        val types = run(Scenario.NIGHT_LEFT_TURN).types()
        assertTrue(AlertType.LEFT_ARROW_ON in types, "$types")
        assertTrue(AlertType.GREEN_GO in types, "$types")
        assertTrue(AlertType.RED_URGENT !in types, "$types")
    }
}
