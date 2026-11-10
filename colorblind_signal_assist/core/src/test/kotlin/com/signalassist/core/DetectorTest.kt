package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DetectorTest {
    private val detector = TrafficLightDetector()

    private fun detect(color: SignalColor, lighting: Lighting = Lighting.DAY, arrow: Boolean = false, radius: Float = 9f) =
        detector.detect(
            SyntheticSceneGenerator.render(
                SceneSpec(signal = SignalState(color, arrow), lighting = lighting, lampRadius = radius, seed = 7),
            ),
        )

    @Test
    fun `detects each color in each lighting`() {
        for (lighting in Lighting.entries) {
            for (color in listOf(SignalColor.RED, SignalColor.YELLOW, SignalColor.GREEN)) {
                assertEquals(color, detect(color, lighting).color, "$color @ $lighting")
            }
        }
    }

    @Test
    fun `detects small distant lamp`() {
        assertEquals(SignalColor.GREEN, detect(SignalColor.GREEN, radius = 3.5f).color)
    }

    @Test
    fun `detects red plus left arrow`() {
        val det = detect(SignalColor.RED, arrow = true, radius = 10f)
        assertEquals(SignalColor.RED, det.color)
        assertTrue(det.leftTurnArrow)
    }

    @Test
    fun `brake lights below horizon are not a signal`() {
        val det = detector.detect(
            SyntheticSceneGenerator.render(
                SceneSpec(signal = SignalState.UNKNOWN, leadCarBrakeLights = true, seed = 3),
            ),
        )
        assertEquals(SignalColor.UNKNOWN, det.color)
        assertFalse(det.leftTurnArrow)
    }

    @Test
    fun `brake lights do not override a green signal`() {
        val det = detector.detect(
            SyntheticSceneGenerator.render(
                SceneSpec(signal = SignalState(SignalColor.GREEN), leadCarBrakeLights = true, seed = 5),
            ),
        )
        assertEquals(SignalColor.GREEN, det.color)
    }
}
