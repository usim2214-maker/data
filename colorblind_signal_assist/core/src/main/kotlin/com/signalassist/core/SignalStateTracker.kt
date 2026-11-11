package com.signalassist.core

/**
 * 프레임 단위 인식 결과를 시간적으로 안정화한다.
 *
 * **안전 우선 비대칭 확정 (fail-safe)**
 * 색각이상 운전자에게 가장 위험한 오류는 "빨간불인데 초록불이라고 알려주는 것"이다.
 * 그래서 '진행' 쪽 신호(녹색, 좌회전 화살표)는 더 많은 프레임이 일치해야 확정하고,
 * '정지' 쪽 신호(적색, 황색)는 더 빨리 확정한다. 화살표가 꺼지는 것도 빨리 반영한다.
 * 신뢰도가 낮은 '진행' 인식은 투표에서 빼서, 흐릿한 오검출이 초록불 안내로 이어지지 않게 한다.
 */
class SignalStateTracker(
    private val windowSize: Int = 5,
    /** 적색·황색 확정에 필요한 표 */
    private val stopVotes: Int = 3,
    /** 녹색·좌회전 화살표 확정에 필요한 표 (더 엄격) */
    private val goVotes: Int = 4,
    /** '진행' 쪽(녹색·화살표) 등화는 이 점수 이상일 때만 투표. 정지 쪽은 검출기 기준 그대로 (멀리서도 빨리) */
    private val minGoScore: Float = 0.25f,
    /** 이만큼 연속으로 아무것도 안 보이면 UNKNOWN 으로 전환 */
    private val lostAfterFrames: Int = 10,
) {
    init {
        require(stopVotes in 1..windowSize && goVotes in stopVotes..windowSize)
    }

    private val colors = ArrayDeque<SignalColor>()
    private val arrows = ArrayDeque<Boolean>()
    private var unknownStreak = 0

    var state: SignalState = SignalState.UNKNOWN
        private set

    /** 최근 프레임 중 현재 상태와 같은 색으로 본 비율 (0..1). '지금 신호?' 질문에 답할 때 확신 정도로 쓴다 */
    val confidence: Float
        get() = if (state.color == SignalColor.UNKNOWN || colors.isEmpty()) 0f
        else colors.count { it == state.color }.toFloat() / windowSize

    fun reset() {
        colors.clear(); arrows.clear(); unknownStreak = 0
        state = SignalState.UNKNOWN
    }

    private fun required(c: SignalColor) = if (c == SignalColor.GREEN) goVotes else stopVotes

    /** 안전 순위: 같은 표 수면 정지 쪽을 택한다 */
    private fun safetyRank(c: SignalColor) = when (c) {
        SignalColor.RED -> 3
        SignalColor.YELLOW -> 2
        SignalColor.GREEN -> 1
        SignalColor.UNKNOWN -> 0
    }

    /** @return 상태가 바뀌었으면 변화 내용, 아니면 null */
    fun update(detection: FrameDetection): StateChange? {
        val primaryScore = detection.primary?.score
        val weakGo = detection.color == SignalColor.GREEN && primaryScore != null && primaryScore < minGoScore
        val color = if (weakGo) SignalColor.UNKNOWN else detection.color
        val arrowScore = detection.lamps.filter { it.shape == LampShape.ARROW }.maxOfOrNull { it.score }
        val arrow = detection.leftTurnArrow && (arrowScore == null || arrowScore >= minGoScore)

        colors.addLast(color)
        arrows.addLast(arrow)
        while (colors.size > windowSize) colors.removeFirst()
        while (arrows.size > windowSize) arrows.removeFirst()
        unknownStreak = if (color == SignalColor.UNKNOWN && !arrow) unknownStreak + 1 else 0

        val votes = colors.filter { it != SignalColor.UNKNOWN }.groupingBy { it }.eachCount()
        val confirmed = votes.filter { (c, n) -> n >= required(c) }
            .maxWithOrNull(compareBy<Map.Entry<SignalColor, Int>> { it.value }.thenBy { safetyRank(it.key) })
        val newColor = when {
            confirmed != null -> confirmed.key
            unknownStreak >= lostAfterFrames -> SignalColor.UNKNOWN
            else -> state.color
        }
        val arrowVotes = arrows.count { it }
        val newArrow = when {
            arrows.size - arrowVotes >= stopVotes -> false // 꺼짐은 빠르게
            arrowVotes >= goVotes -> true                  // 켜짐은 엄격하게
            else -> state.leftTurnArrow
        }

        val next = SignalState(newColor, newArrow)
        if (next == state) return null
        val change = StateChange(state, next)
        state = next
        return change
    }
}
