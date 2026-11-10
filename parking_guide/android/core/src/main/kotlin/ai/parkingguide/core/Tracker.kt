package ai.parkingguide.core

import kotlin.math.abs

/**
 * 한 번 고른 주차칸을 고정하고 매 프레임 예측 → 검증 → 융합한다.
 * - 예측: 이동 거리와 핸들 각도로 지난 주차칸을 옮긴다 (선이 가려져도 이어 감)
 * - 검증: 예측과 너무 다른 검출(그림자, 옆 칸, 지나가는 사람)은 무시한다
 * - 같은 '다른 칸'이 몇 프레임 계속 보이면 그때 새 칸으로 바꾼다
 * - 검출이 없으면 신뢰도를 조금씩 낮추고, 오래 없으면 놓는다
 */
class SlotTracker(
    private val gateCm: Double = 30.0,
    private val gateDeg: Double = 6.0,
    private val alpha: Double = 0.5,
    private val coastDecay: Double = 0.97,
    private val maxCoast: Int = 150,
    private val switchAfter: Int = 3,
) {
    var slot: SlotEstimate? = null
        private set
    var lastRejected = false
        private set
    private var coast = 0
    private var outliers = 0

    fun reset() {
        slot = null; coast = 0; outliers = 0
    }

    fun update(det: SlotEstimate?, dsCm: Double = 0.0, kappa: Double = 0.0, axleY: Double = 0.0): SlotEstimate? {
        lastRejected = false
        if (dsCm != 0.0) slot = slot?.moved(dsCm, kappa, axleY)
        if (det == null) return coastStep(dsCm)
        val p = slot ?: run {
            slot = det; coast = 0; outliers = 0
            return det
        }
        val yRef = 150.0
        val dc = abs(det.centerXAt(yRef) - p.centerXAt(yRef))
        val dyaw = abs(det.yawDeg - p.yawDeg)
        if (dc > gateCm || dyaw > gateDeg) {
            outliers++
            lastRejected = true
            if (outliers >= switchAfter) { // 정말로 다른 칸이 계속 보이면 갈아탄다
                slot = det; coast = 0; outliers = 0
                return det
            }
            return coastStep(dsCm)
        }
        outliers = 0; coast = 0
        fun mix(pl: LineEstimate, dl: LineEstimate) =
            dl.copy(a = (1 - alpha) * pl.xAt(0.0) + alpha * dl.a, b = (1 - alpha) * pl.b + alpha * dl.b)
        val fused = det.copy(
            left = mix(p.left, det.left), right = mix(p.right, det.right),
            end = det.end ?: p.end,
            confidence = (1 - alpha) * p.confidence + alpha * det.confidence,
        )
        slot = fused
        return fused
    }

    /** 신뢰도는 움직인 거리만큼만 낮춘다 (5cm마다 coastDecay배). 멈춰 있으면 그대로. 프레임 상한은 안전장치. */
    private fun coastStep(dsCm: Double): SlotEstimate? {
        val s = slot ?: return null
        coast++
        if (coast > maxCoast) {
            slot = null
            return null
        }
        return s.copy(confidence = s.confidence * Math.pow(coastDecay, abs(dsCm) / 5.0)).also { slot = it }
    }
}
