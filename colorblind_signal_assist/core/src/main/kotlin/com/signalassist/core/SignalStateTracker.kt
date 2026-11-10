package com.signalassist.core

/**
 * 프레임 단위 인식 결과를 시간적으로 안정화한다.
 * 한두 프레임의 오검출/깜빡임으로 알림이 튀지 않도록 최근 [windowSize] 프레임 다수결을 쓴다.
 */
class SignalStateTracker(
    private val windowSize: Int = 5,
    private val minVotes: Int = 3,
    /** 이만큼 연속으로 아무것도 안 보이면 UNKNOWN 으로 전환 */
    private val lostAfterFrames: Int = 10,
) {
    init {
        require(minVotes in 1..windowSize)
    }

    private val colors = ArrayDeque<SignalColor>()
    private val arrows = ArrayDeque<Boolean>()
    private var unknownStreak = 0

    var state: SignalState = SignalState.UNKNOWN
        private set

    fun reset() {
        colors.clear(); arrows.clear(); unknownStreak = 0
        state = SignalState.UNKNOWN
    }

    /** @return 상태가 바뀌었으면 변화 내용, 아니면 null */
    fun update(detection: FrameDetection): StateChange? {
        colors.addLast(detection.color)
        arrows.addLast(detection.leftTurnArrow)
        while (colors.size > windowSize) colors.removeFirst()
        while (arrows.size > windowSize) arrows.removeFirst()
        unknownStreak = if (detection.color == SignalColor.UNKNOWN && !detection.leftTurnArrow) unknownStreak + 1 else 0

        val votes = colors.filter { it != SignalColor.UNKNOWN }.groupingBy { it }.eachCount()
        val top = votes.maxByOrNull { it.value }
        val newColor = when {
            top != null && top.value >= minVotes -> top.key
            unknownStreak >= lostAfterFrames -> SignalColor.UNKNOWN
            else -> state.color
        }
        val arrowVotes = arrows.count { it }
        val newArrow = when {
            arrowVotes >= minVotes -> true
            arrows.size - arrowVotes >= minVotes -> false
            else -> state.leftTurnArrow
        }

        val next = SignalState(newColor, newArrow)
        if (next == state) return null
        val change = StateChange(state, next)
        state = next
        return change
    }
}
