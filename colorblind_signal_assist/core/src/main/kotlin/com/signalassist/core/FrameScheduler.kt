package com.signalassist.core

/**
 * 영상 처리 빈도 조절 (주행 중 CPU 사용률 기준 대응).
 *
 * 신호 인식이 필요한 순간에만 모든 프레임을 처리하고, 그 외에는 [idleIntervalMs] 마다 한 장만 본다.
 * 모든 프레임을 처리하는 경우:
 *  - 최근 [recentMs] 안에 신호등(또는 켜진 등화 후보)을 봤을 때
 *  - 저속([slowKmh] 미만)일 때: 교차로 접근·정차 중일 가능성이 큼
 *  - 내비 안내 지점이 [navNearM] 안일 때
 * 신호등이 없는 구간을 일정 속도로 달릴 때는 영상 처리가 1/5 로 줄어든다.
 */
class FrameScheduler(
    private val idleIntervalMs: Long = 500,
    private val recentMs: Long = 10_000,
    private val slowKmh: Float = 20f,
    private val navNearM: Float = 300f,
) {
    private var lastProcessedMs: Long? = null
    private var lastSeenMs: Long? = null

    var processed = 0
        private set
    var offered = 0
        private set

    fun reset() { lastProcessedMs = null; lastSeenMs = null; processed = 0; offered = 0 }

    fun shouldProcess(nowMs: Long, vehicle: VehicleState, navigation: NavigationState?): Boolean {
        offered++
        val seenRecently = lastSeenMs?.let { nowMs - it <= recentMs } ?: false
        val navNear = navigation?.guiding == true && (navigation.distanceToManeuverM ?: Float.MAX_VALUE) <= navNearM
        val active = seenRecently || vehicle.speedKmh < slowKmh || navNear
        val last = lastProcessedMs
        val go = active || last == null || nowMs - last >= idleIntervalMs
        if (go) { processed++; lastProcessedMs = nowMs }
        return go
    }

    fun observe(nowMs: Long, out: EngineOutput) {
        if (out.detection.lamps.isNotEmpty() || out.state.color != SignalColor.UNKNOWN) lastSeenMs = nowMs
    }
}
