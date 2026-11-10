package ai.parkingguide.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackerReportTest {
    private val v = VehicleSpec.DEFAULT

    @Test fun movedMatchesGeometry() {
        for (steer in listOf(0.0, 250.0, -400.0)) {
            val bx = 30.0; val by = -250.0; val h = -5.0
            val before = groundTruthSlot(poseFromBumper(bx, by, h))
            val (bx2, by2, h2) = stepCar(bx, by, h, steer, 60.0, v)
            val after = groundTruthSlot(poseFromBumper(bx2, by2, h2))
            val pred = before.moved(60.0, wheelToCurvature(steer, v), -v.rearOverhangCm)
            for (y in listOf(50.0, 300.0)) assertEquals(after.centerXAt(y), pred.centerXAt(y), 0.5)
            assertEquals(after.yawDeg, pred.yawDeg, 0.05)
            assertEquals(after.endDistCm!!, pred.endDistCm!!, 0.5)
        }
    }

    @Test fun trackerRejectsOutlierThenSwitches() {
        val tr = SlotTracker(switchAfter = 3)
        val a = groundTruthSlot(CarPose(0.0, 0.0, 100.0))
        val other = groundTruthSlot(CarPose(250.0, 0.0, 100.0))
        tr.update(a)
        assertEquals(a.centerXAt(150.0), tr.update(other)!!.centerXAt(150.0), 1.0)
        assertTrue(tr.lastRejected)
        tr.update(other)
        assertEquals(other.centerXAt(150.0), tr.update(other)!!.centerXAt(150.0), 1.0)
    }

    @Test fun trackerCoastsAndDrops() {
        val tr = SlotTracker(maxCoast = 5)
        val a = groundTruthSlot(CarPose(0.0, 0.0, 100.0))
        tr.update(a)
        val s1 = tr.update(null, dsCm = 40.0)!!
        assertEquals(a.endDistCm!! - 40, s1.endDistCm!!, 0.5)
        assertTrue(s1.confidence < a.confidence)
        repeat(5) { tr.update(null) }
        assertNull(tr.slot)
    }

    @Test fun scoreAndGrades() {
        assertEquals(100, scoreParking(0.0, 0.0, 20.0, 2, 0))
        assertTrue(scoreParking(10.0, 0.0, 20.0, 2, 0) < 100)
        assertEquals(0, scoreParking(80.0, 10.0, -30.0, 30, 5))
        assertEquals("완벽해요", grade(95))
        assertTrue(makeTips(0.0, 3.0, 2, 0, 0.0, 0.0)[0].contains("반듯"))
    }

    @Test fun sessionCountsCorrectionsRetriesTime() {
        val s = ParkingSession()
        val slot = groundTruthSlot(CarPose(0.0, 0.0, 100.0))
        val al = Alignment(0.0, 0.0, 20.0, 20.0, 20.0, 20.0, AlignStatus.GOOD, 30.0)
        val steer = listOf(0.0, 100.0, 200.0, 150.0, 50.0, -60.0, -40.0, 80.0)
        val keys = listOf("turn1", "retry", "retry", "keep", "retry", "keep", "keep", "keep")
        steer.indices.forEach { s.add(al, slot, Guidance(GuidanceLevel.OK, keys[it], ""), steer[it], it * 0.5, slot) }
        val r = s.finish()!!
        assertEquals(2, r.corrections)
        assertEquals(2, r.retries)
        assertEquals(3.5, r.durationS, 1e-9)
        assertEquals(8, r.line!!.samples)
    }

    @Test fun municipalPayloadIsAnonymous() {
        val s = ParkingSession()
        val slot = groundTruthSlot(CarPose(0.0, 0.0, 100.0))
        s.add(Alignment(0.0, 0.0, 20.0, 20.0, 20.0, 20.0, AlignStatus.GOOD, 30.0), slot, null, 0.0, 0.0, slot)
        val json = municipalPayload(s.finish()!!, 36.446612, 127.119034, 93.0)
        assertTrue(json.contains("\"lat\":36.447") && json.contains("\"lon\":127.119"))
        assertTrue(json.contains("\"slot_heading_deg\":90"))
        listOf("vin", "image", "user").forEach { assertTrue(!json.lowercase().contains(it)) }
        assertEquals("첫 기록이에요", trendText(listOf(70)))
        assertTrue(trendText(List(5) { 50 } + List(5) { 70 }).contains("올랐어요"))
    }
}
