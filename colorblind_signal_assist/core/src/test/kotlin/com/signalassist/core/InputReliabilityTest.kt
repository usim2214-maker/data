package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class InputReliabilityTest {
    @Test fun `missing and expired telemetry are not stopped vehicles`() {
        val telemetry = VehicleTelemetry()
        assertEquals(InputStatus.UNAVAILABLE, telemetry.current(0).speedStatus)
        assertFalse(telemetry.current(0).hasValidSpeed)
        telemetry.updateSpeed(42f, 100)
        assertEquals(42f, telemetry.current(200).speedKmh)
        assertEquals(InputStatus.STALE, telemetry.current(3_101).speedStatus)
        assertTrue(telemetry.current(3_101).speedKmh.isNaN())
        telemetry.updateSpeed(0f, 4_000)
        assertTrue(telemetry.current(4_000).hasValidSpeed)
        telemetry.updateSpeed(Float.NaN, 4_100)
        assertFalse(telemetry.current(4_100).hasValidSpeed)
        telemetry.updateGear(Gear.PARK)
        telemetry.reset()
        assertEquals(Gear.UNKNOWN, telemetry.current(4_200).gear)
    }

    @Test fun `video loss expires cached signal and clears old urgent hazard`() {
        val engine = DrivingAssistEngine(DriverProfile.COLOR_VISION_ONLY)
        val inputs = ScenarioPlayer(Scenario.STOP_AND_GO).samples().takeWhile { it.timestampMs <= 10_000 }.toList()
        var output = engine.process(inputs.first().toInput())
        for (sample in inputs.drop(1)) output = engine.process(sample.toInput())
        assertEquals(SignalColor.RED, output.signalState.color)
        val original = output.signal!!.timestampMs
        val shortGap = engine.process(DrivingInput(original + 200, VehicleState(0f)))
        assertEquals(original, shortGap.signal!!.timestampMs)
        val expired = engine.process(DrivingInput(original + 2_001, VehicleState(0f)))
        assertNull(expired.signal)
        assertEquals(SignalState.UNKNOWN, expired.signalState)
        assertTrue(AlertType.SIGNAL_LOST in expired.activeHazards)
        assertTrue(expired.alerts.any { it.type == AlertType.SIGNAL_LOST })
        val stillLost = engine.process(DrivingInput(original + 2_100, VehicleState(0f)))
        assertTrue(stillLost.alerts.none { it.type == AlertType.SIGNAL_LOST })
        assertFalse(AlertType.RED_URGENT in expired.activeHazards)
        assertTrue("직접 확인" in SignalQuery.answer(expired.signal, original + 2_001).speech)
    }

    @Test fun `signal replay answers current red instead of previous green`() {
        val history = AlertHistory()
        val profile = DriverProfile.COLOR_VISION_ONLY
        val green = Alert(AlertType.GREEN_GO, 100)
        history.record(listOf(Delivery(green, profile.outputFor(green))))
        val red = EngineOutput(1_000, FrameDetection(SignalColor.RED, false, emptyList()),
            SignalState(SignalColor.RED), null, VehicleState(0f), emptyList(), confidence = 1f)
        val replay = history.replayLast(profile, 1_100, red)!!
        assertEquals(AlertType.SIGNAL_STATUS, replay.alert.type)
        assertTrue("빨간불" in replay.alert.speech)
        assertEquals(TonePattern.FALLING_TRIPLE, replay.alert.tone)
        assertTrue("직접 확인" in history.replayLast(profile, 4_000, red)!!.alert.speech)
        history.clear()
        assertNull(history.replayLast(profile, 4_100, red))
    }

    @Test fun `missing speed never triggers green departure or trip ending`() {
        val missing = VehicleState(0f, Gear.UNKNOWN, speedStatus = InputStatus.UNAVAILABLE)
        val signal = SignalAlertPolicy().evaluate(SignalState(SignalColor.GREEN),
            StateChange(SignalState(SignalColor.RED), SignalState(SignalColor.GREEN)), missing, 1_000)
        assertTrue(signal.isEmpty())
        val policy = VehicleAlertPolicy()
        val occupied = CabinState(rearSeatOccupied = true)
        policy.evaluate(occupied, VehicleState(30f), 0)
        assertTrue(policy.evaluate(occupied.copy(doorOpen = true), missing, 1_000).isEmpty())
        val recorder = TripRecorder(endAfterStoppedMs = 1_000)
        fun output(t: Long, v: VehicleState) = DrivingOutput(DrivingInput(t, v), null, emptyList())
        recorder.update(output(0, VehicleState(30f)))
        assertNull(recorder.update(output(2_000, missing.copy(gear = Gear.PARK))))
        assertTrue(recorder.inTrip)
        assertNull(recorder.update(output(3_000, VehicleState(0f))))
    }

    @Test fun `future observation timestamps cannot be treated as fresh`() {
        val observation = EngineOutput(2_000, FrameDetection(SignalColor.GREEN, false, emptyList()),
            SignalState(SignalColor.GREEN), null, VehicleState(0f), emptyList(), confidence = 1f)
        assertTrue("직접 확인" in SignalQuery.answer(observation, 1_000).speech)
    }
}
