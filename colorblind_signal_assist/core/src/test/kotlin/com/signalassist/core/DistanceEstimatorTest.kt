package com.signalassist.core

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class DistanceEstimatorTest {
    @Test
    fun `distance from lamp size is within 10 percent (median) on synthetic scenarios`() {
        val detector = TrafficLightDetector()
        val est = DistanceEstimator()
        val errors = ArrayList<Float>()
        for (s in listOf(Scenario.STOP_AND_GO, Scenario.RED_LIGHT_RISK)) {
            val player = ScenarioPlayer(s)
            for (sample in player.samples()) {
                val det = detector.detect(sample.frame)
                val lamp = det.primary ?: continue
                val d = est.rawDistanceM(lamp, sample.frame.width) ?: continue
                errors += abs(d - sample.distanceM) / sample.distanceM
            }
        }
        val sorted = errors.sorted()
        val median = sorted[sorted.size / 2]
        val p90 = sorted[(sorted.size * 0.9).toInt()]
        println("distance error median=%.3f p90=%.3f n=%d".format(median, p90, sorted.size))
        assertTrue(median < 0.1f, "median error $median")
    }
}
