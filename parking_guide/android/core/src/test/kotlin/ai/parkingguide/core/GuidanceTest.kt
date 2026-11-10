package ai.parkingguide.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuidanceTest {
    private val v = VehicleSpec.DEFAULT

    private fun slot(cx: Double = 0.0, slope: Double = 0.0) = SlotEstimate(
        LineEstimate(cx - 125, slope, 15.0, 60.0, 500.0), LineEstimate(cx + 125, slope, 15.0, 60.0, 500.0))

    @Test fun curvatureRoundTrip() {
        for (w in listOf(-300.0, 0.0, 120.0, 400.0)) assertEquals(w, curvatureToWheel(wheelToCurvature(w, v), v), 1e-6)
    }

    @Test fun pleosFrontWheelAngleIsConvertedToSteeringWheel() {
        // Pleos SteeringWheel API는 앞바퀴 각도(왼쪽 -)를 준다
        assertEquals(-140.0, v.steeringWheelFromFrontWheel(-10.0), 1e-9)
    }

    @Test fun pathTurnsTowardSteering() {
        assertEquals(0.0, predictPath(v, 0.0, 300.0).center.last().x, 1e-9)
        assertTrue(predictPath(v, 270.0, 300.0).center.last().x > 30)
        assertTrue(predictPath(v, -270.0, 300.0).center.last().x < -30)
    }

    @Test fun desiredSteeringSign() {
        assertTrue(desiredSteering(slot(30.0), v) > 0)
        assertTrue(desiredSteering(slot(-30.0), v) < 0)
        assertEquals(0.0, desiredSteering(slot(), v), 1e-9)
    }

    @Test fun turnsPhrase() {
        assertEquals("살짝", turnsPhraseKo(30.0))
        assertEquals("1/4바퀴", turnsPhraseKo(100.0))
        assertEquals("반 바퀴", turnsPhraseKo(-180.0))
        assertEquals("한 바퀴 반", turnsPhraseKo(600.0))
    }

    @Test fun guidanceMessages() {
        val s = slot(40.0)
        val a = computeAlignment(s, v)
        assertTrue(buildGuidance(a, s, v, 0.0).text.startsWith("핸들을 오른쪽으로"))
        assertEquals(GuidanceLevel.OK, buildGuidance(a, s, v, desiredSteering(s, v)).level)
        assertEquals(GuidanceLevel.STOP, buildGuidance(a.copy(endDistCm = 20.0), s, v, 0.0).level)
        assertEquals("lowconf", buildGuidance(a, s.copy(confidence = 0.3), v, 0.0).key)
        assertEquals(GuidanceLevel.INFO, buildGuidance(null, null, v).level)
    }

    @Test fun retryWhenCannotFit() {
        val s = groundTruthSlot(CarPose(60.0, 0.0, -300.0))
        assertEquals("retry", buildGuidance(computeAlignment(s, v), s, v, 0.0).key)
    }

    @Test fun finalPredictionStraightCentered() {
        val s = groundTruthSlot(CarPose(0.0, 0.0, 100.0))
        val f = predictFinal(s, v, 0.0, s.endDistCm)
        assertEquals(0.0, f.offsetCm, 0.5)
        assertEquals((SLOT_W - LINE_W - v.widthCm) / 2, f.clearLeftCm, 0.5)
        assertEquals(AlignStatus.GOOD, f.status)
    }

    @Test fun stabilizerNeedsRepeats() {
        val st = GuidanceStabilizer(3)
        val s = slot()
        val a = computeAlignment(s, v)
        val ok = buildGuidance(a, s, v, 0.0)
        val warn = buildGuidance(a, s, v, 300.0)
        assertEquals(ok.key, st.update(ok).key)
        assertEquals(ok.key, st.update(warn).key)
        assertEquals(ok.key, st.update(warn).key)
        assertEquals(warn.key, st.update(warn).key)
    }

    @Test fun voiceAndBeep() {
        assertEquals(null, beepIntervalS(200.0))
        assertTrue(beepIntervalS(140.0)!! > beepIntervalS(40.0)!!)
        assertEquals(0.0, beepIntervalS(20.0))
        val vp = VoicePrompter()
        val s = slot(60.0)
        val a = computeAlignment(s, v)
        val turn = buildGuidance(a, s, v, 0.0)
        assertEquals(turn.text, vp.update(turn, null, 0.0))
        assertEquals(null, vp.update(turn, null, 0.5))
        assertEquals("1미터 남았어요", vp.update(turn, 95.0, 3.0))
        val stop = buildGuidance(a.copy(endDistCm = 20.0), s, v, 0.0)
        assertEquals("멈추세요", vp.update(stop, 20.0, 3.1))
        assertEquals(null, vp.update(stop, 20.0, 3.5))
        assertEquals("멈추세요", vp.update(stop, 20.0, 5.2))
    }

    @Test fun doorBias() {
        val s = groundTruthSlot(CarPose(0.0, 0.0, 100.0))
        val b = doorBiasCm(DoorMode.DRIVER, s, v)
        assertTrue(b > 0 && b <= 15)
        assertEquals(-b, doorBiasCm(DoorMode.PASSENGER, s, v))
        val a = computeAlignment(s.copy(targetBiasCm = 15.0), v)
        assertEquals(-15.0, a.offsetCm, 0.1)
        assertEquals(a.clearLeftRearCm, a.clearRightRearCm, 1e-9)
        assertTrue(abs(a.offsetCm) > 8)
    }
}
