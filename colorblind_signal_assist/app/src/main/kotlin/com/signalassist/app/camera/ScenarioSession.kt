package com.signalassist.app.camera

import com.signalassist.core.Scenario
import com.signalassist.core.ScenarioPlayer
import com.signalassist.core.ScenarioSample

/**
 * 시나리오 재생 시계. 영상(FrameSource)과 차량 데이터(VehicleDataSource)가 같은 샘플을 보도록 공유한다.
 * 끝나면 처음부터 반복한다.
 */
class ScenarioSession(val scenario: Scenario, fps: Int = 10) {
    private val player = ScenarioPlayer(scenario, fps)
    private val intervalMs = 1000L / fps
    private var startMs: Long? = null
    private var cachedIndex = -1
    private var cached: ScenarioSample? = null

    @Synchronized
    fun sampleAt(nowMs: Long): ScenarioSample {
        val start = startMs ?: nowMs.also { startMs = it }
        val index = (((nowMs - start) / intervalMs) % player.frameCount).toInt()
        if (index != cachedIndex || cached == null) {
            cached = player.sample(index)
            cachedIndex = index
        }
        return cached!!
    }

    /** 시나리오 안에서의 경과 시간 */
    fun scenarioTimeMs(nowMs: Long): Long = sampleAt(nowMs).timestampMs

    @Synchronized
    fun restart() {
        startMs = null
        cachedIndex = -1
        cached = null
    }
}

class ScenarioFrameSource(private val session: ScenarioSession) : FrameSource {
    override val name = "합성 영상: ${session.scenario.name}"
    override fun nextFrame(nowMs: Long) = session.sampleAt(nowMs).frame
}
