package com.signalassist.core

/**
 * 카메라 모델. 신호등 등화의 실제 크기(국내 300mm 등화)를 알고 있으므로
 * 화면 속 반지름(px)으로 거리를 잴 수 있다: 거리 = 초점거리(px) × 실제 반지름(m) / 화면 반지름(px)
 */
data class CameraModel(
    /** 초점거리 / 영상 가로폭. 수평 화각 60° ≈ 0.866 */
    val focalPerWidth: Float = 0.866f,
    val lampRadiusM: Float = 0.15f,
)

/** 등화 크기로 신호등까지 거리를 추정하고, 최근 값의 중앙값으로 흔들림을 줄인다 */
class DistanceEstimator(private val camera: CameraModel = CameraModel(), private val window: Int = 5) {
    private val recent = ArrayDeque<Float>()

    fun reset() = recent.clear()

    /** 한 등화의 거리(m). 너무 작은 등화(반지름 1.5px 미만)는 오차가 커서 버린다 */
    fun rawDistanceM(lamp: LampDetection, frameWidth: Int): Float? {
        if (lamp.radius < 1.5f) return null
        return camera.focalPerWidth * frameWidth * camera.lampRadiusM / lamp.radius
    }

    /** 이번 프레임의 대표 등화로 갱신하고, 평활된 거리를 돌려준다 */
    fun update(detection: FrameDetection, frameWidth: Int, stateColor: SignalColor): Float? {
        val lamp = detection.primary?.takeIf { it.color == stateColor }
        val d = lamp?.let { rawDistanceM(it, frameWidth) }
        if (d != null) {
            recent.addLast(d)
            while (recent.size > window) recent.removeFirst()
        } else if (stateColor == SignalColor.UNKNOWN) {
            recent.clear()
        }
        if (recent.isEmpty()) return null
        return recent.sorted()[recent.size / 2]
    }
}
