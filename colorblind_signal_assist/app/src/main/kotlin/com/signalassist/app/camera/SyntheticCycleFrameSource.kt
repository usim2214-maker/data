package com.signalassist.app.camera

import com.signalassist.core.Lighting
import com.signalassist.core.SceneSpec
import com.signalassist.core.SignalColor
import com.signalassist.core.SignalState
import com.signalassist.core.SyntheticSceneGenerator

/**
 * 실제 차량 데이터와 함께 쓰는 '가상 신호등'. 적색 -> 녹색 -> 황색 주기를 반복하는 장면을 합성한다.
 * 에뮬레이터에서 차속/기어를 바꿔가며 알림 정책을 시험할 때 쓴다.
 */
class SyntheticCycleFrameSource(
    private val redMs: Long = 10_000,
    private val greenMs: Long = 8_000,
    private val yellowMs: Long = 3_000,
    private val lighting: Lighting = Lighting.DAY,
) : FrameSource {
    override val name = "가상 신호 주기 (적 ${redMs / 1000}s / 녹 ${greenMs / 1000}s / 황 ${yellowMs / 1000}s)"
    private var startMs: Long? = null
    private var frameIndex = 0

    override fun nextFrame(nowMs: Long): com.signalassist.core.Frame {
        val start = startMs ?: nowMs.also { startMs = it }
        val t = (nowMs - start) % (redMs + greenMs + yellowMs)
        val color = when {
            t < redMs -> SignalColor.RED
            t < redMs + greenMs -> SignalColor.GREEN
            else -> SignalColor.YELLOW
        }
        return SyntheticSceneGenerator.render(
            SceneSpec(signal = SignalState(color), lampRadius = 10f, lighting = lighting, seed = frameIndex++),
        )
    }
}
