package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameSchedulerTest {
    private fun count(s: FrameScheduler, speed: Float, nav: NavigationState? = null, ticks: Int = 50) =
        (0 until ticks).count { s.shouldProcess(it * 100L, VehicleState(speed), nav) }

    @Test
    fun `cruising with no signal in sight processes 2 frames per second`() {
        assertEquals(10, count(FrameScheduler(), 60f)) // 5초, 10Hz 입력 중 10장
    }

    @Test
    fun `slow driving or near a nav maneuver processes every frame`() {
        assertEquals(50, count(FrameScheduler(), 10f))
        assertEquals(50, count(FrameScheduler(), 60f, NavigationState(true, Maneuver.LEFT, 200f)))
    }

    @Test
    fun `scenarios still behave the same with scheduling, and save work while cruising`() {
        // 신호등 없는 도로를 50km/h 로 달리는 구간이 긴 시나리오
        val engine = DrivingAssistEngine(DriverProfile.COLOR_VISION_ONLY)
        ScenarioPlayer(Scenario.HEARING_DRIVE).samples().forEach { engine.process(it.toInput()) }
        val ratio = engine.frameScheduler.processed.toFloat() / engine.frameScheduler.offered
        // 앞 22초는 안내 지점 근처라 전부 처리, 이후 18초 순항 구간은 1/5 만 처리 -> 약 65%
        assertTrue(ratio < 0.7f, "processed ratio $ratio")
    }
}
