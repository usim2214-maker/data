package com.signalassist.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** 부산 센텀시티 실제 도로망(SUMO + OpenStreetMap) 주행 기록으로 엔진 전체를 돌려 본다 */
class TraceDriveTest {
    private val file = File("../sim/data/centum_trace.csv")

    @Test
    fun `centum drive produces the expected alerts at the right moments`() {
        if (!file.exists()) return // 시뮬레이션 기록이 없으면 건너뜀
        val player = TracePlayer(TraceParser.parse(file.readLines()))
        val engine = DrivingAssistEngine(DriverProfile(setOf(DriverNeed.COLOR_VISION)))
        val alerts = player.samples().flatMap { engine.process(it.toInput()).alerts }.toList()
        val types = alerts.map { it.type }
        println(alerts.joinToString("\n") { "%.1fs %s".format(it.timestampMs / 1000.0, it.message) })
        assertTrue(AlertType.RED_URGENT in types, "신호를 못 본 장면에서 긴급 경고: $types")
        val urgent = alerts.first { it.type == AlertType.RED_URGENT && it.timestampMs in 110_000..120_000 }
        assertTrue(urgent.timestampMs < 117_300, "시뮬레이션 운전자가 제동(117.3초)하기 전에 경고: ${urgent.timestampMs}")
        assertTrue(alerts.any { it.type == AlertType.GREEN_REMINDER && it.timestampMs in 212_000..215_000 }, "늦은 출발 리마인드: $types")
        assertTrue(AlertType.GREEN_GO in types && AlertType.RED_STOP in types)
    }
}
