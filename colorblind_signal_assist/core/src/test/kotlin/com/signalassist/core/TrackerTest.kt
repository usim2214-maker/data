package com.signalassist.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TrackerTest {
    private fun det(c: SignalColor, score: Float = 0.8f) = FrameDetection(
        c, false,
        if (c == SignalColor.UNKNOWN) emptyList() else listOf(LampDetection(c, LampShape.CIRCLE, 0f, 0f, 5f, score)),
    )

    /** 상태가 [target] 이 될 때까지 걸린 프레임 수 */
    private fun framesUntil(t: SignalStateTracker, input: SignalColor, target: SignalColor): Int {
        repeat(10) { i -> t.update(det(input)); if (t.state.color == target) return i + 1 }
        return -1
    }

    @Test
    fun `single-frame glitch does not change state`() {
        val t = SignalStateTracker()
        repeat(5) { t.update(det(SignalColor.RED)) }
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
    fun `fail-safe - green needs more evidence than red`() {
        val toGreen = SignalStateTracker().also { t -> repeat(5) { t.update(det(SignalColor.RED)) } }
        val toRed = SignalStateTracker().also { t -> repeat(5) { t.update(det(SignalColor.GREEN)) } }
        val greenFrames = framesUntil(toGreen, SignalColor.GREEN, SignalColor.GREEN)
        val redFrames = framesUntil(toRed, SignalColor.RED, SignalColor.RED)
        assertEquals(4, greenFrames)
        assertEquals(3, redFrames)
    }

    @Test
    fun `fail-safe - flickering green among red never confirms green`() {
        val t = SignalStateTracker()
        repeat(5) { t.update(det(SignalColor.RED)) }
        // 녹·적이 번갈아 나오는 불안정한 인식
        repeat(20) { i -> t.update(det(if (i % 2 == 0) SignalColor.GREEN else SignalColor.RED)) }
        assertEquals(SignalColor.RED, t.state.color)
    }

    @Test
    fun `low confidence green does not vote`() {
        val t = SignalStateTracker()
        repeat(5) { t.update(det(SignalColor.RED)) }
        repeat(8) { t.update(det(SignalColor.GREEN, score = 0.15f)) }
        assertEquals(SignalColor.RED, t.state.color)
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
