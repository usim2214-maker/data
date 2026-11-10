package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TrackerTest {
    private fun det(c: SignalColor) = FrameDetection(c, false, emptyList())

    @Test
    fun `single-frame glitch does not change state`() {
        val t = SignalStateTracker()
        repeat(5) { t.update(det(SignalColor.RED)) }
        assertEquals(SignalColor.RED, t.state.color)
        assertNull(t.update(det(SignalColor.GREEN)))
        assertEquals(SignalColor.RED, t.state.color)
    }

    @Test
    fun `sustained change is reported once`() {
        val t = SignalStateTracker()
        repeat(5) { t.update(det(SignalColor.RED)) }
        val changes = (1..5).mapNotNull { t.update(det(SignalColor.GREEN)) }
        assertEquals(1, changes.size)
        assertEquals(SignalColor.RED, changes[0].from.color)
        assertEquals(SignalColor.GREEN, changes[0].to.color)
    }

    @Test
    fun `brief occlusion keeps state, long loss resets`() {
        val t = SignalStateTracker(lostAfterFrames = 10)
        repeat(5) { t.update(det(SignalColor.RED)) }
        repeat(4) { t.update(det(SignalColor.UNKNOWN)) }
        assertEquals(SignalColor.RED, t.state.color)
        val change = (1..10).mapNotNull { t.update(det(SignalColor.UNKNOWN)) }.lastOrNull()
        assertNotNull(change)
        assertEquals(SignalColor.UNKNOWN, t.state.color)
    }
}
