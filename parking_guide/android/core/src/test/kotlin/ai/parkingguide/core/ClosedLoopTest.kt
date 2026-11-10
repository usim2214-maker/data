package ai.parkingguide.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 운전자가 안내(목표 핸들 각도)를 늦게 따라가도 반듯하게 주차되는지 (Python test_round2/3 와 같은 시험). */
class ClosedLoopTest {
    private val v = VehicleSpec.DEFAULT

    private fun simulate(bx0: Double, by0: Double, h0: Double, mode: DoorMode = DoorMode.CENTER): Triple<Double, Double, Double> {
        var bx = bx0
        var by = by0
        var h = h0
        var steer = 0.0
        val stopBy = SLOT_L - LINE_W - 25.0
        repeat(1000) {
            if (by >= stopBy) return Triple(bx, h, steer)
            var slot = groundTruthSlot(poseFromBumper(bx, by, h))
            slot = slot.copy(targetBiasCm = doorBiasCm(mode, slot, v))
            val g = buildGuidance(computeAlignment(slot, v), slot, v, steer)
            if (g.level == GuidanceLevel.WARN) steer += (g.desiredSteerDeg!! - steer) * 0.25
            val n = stepCar(bx, by, h, steer, 4.0, v)
            bx = n.first; by = n.second; h = n.third
        }
        return Triple(bx, h, steer)
    }

    @Test fun parksCenteredFromVariousStarts() {
        for ((bx, by, h) in listOf(Triple(60.0, -300.0, -12.0), Triple(-50.0, -250.0, 10.0), Triple(30.0, -150.0, 0.0), Triple(-25.0, -350.0, -5.0))) {
            val (off, yaw, _) = simulate(bx, by, h)
            assertTrue(abs(off) < 10 && abs(yaw) < 3, "start=($bx,$by,$h) → offset=$off yaw=$yaw")
        }
    }

    @Test fun reachesDoorTarget() {
        val target = doorBiasCm(DoorMode.DRIVER, groundTruthSlot(CarPose(0.0, 0.0, 100.0)), v)
        val (off, yaw, _) = simulate(40.0, -300.0, -8.0, DoorMode.DRIVER)
        assertEquals(target, off, 8.0)
        assertTrue(abs(yaw) < 3)
    }
}
