package com.signalassist.core

/**
 * 플랫폼 독립 영상 프레임 (ARGB_8888, Android Bitmap.getPixels 와 같은 포맷).
 * 실제 차량 카메라 / 합성 영상 / 테스트 이미지 모두 이 형태로 변환해 넣는다.
 */
class Frame(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0) { "frame size must be positive" }
        require(argb.size == width * height) { "pixel buffer size mismatch" }
    }
}

/** 원형 등화의 색. 좌회전 화살표는 [SignalState.leftTurnArrow] 로 따로 다룬다. */
enum class SignalColor(val labelKo: String) {
    RED("정지"),
    YELLOW("주의"),
    GREEN("진행"),
    UNKNOWN("인식 안 됨"),
}

enum class LampShape { CIRCLE, ARROW }

/** 한 프레임에서 찾은 켜진 등화 하나 */
data class LampDetection(
    val color: SignalColor,
    val shape: LampShape,
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    /** 0..1 */
    val score: Float,
)

/** 한 프레임의 인식 결과 */
data class FrameDetection(
    /** 가장 신뢰도 높은 원형 등화의 색 (없으면 UNKNOWN) */
    val color: SignalColor,
    /** 녹색 좌회전 화살표가 켜져 있는지 */
    val leftTurnArrow: Boolean,
    val lamps: List<LampDetection>,
) {
    val primary: LampDetection? get() = lamps.firstOrNull { it.shape == LampShape.CIRCLE }
}

/** 시간적으로 안정화된 신호 상태 */
data class SignalState(
    val color: SignalColor,
    val leftTurnArrow: Boolean = false,
) {
    companion object {
        val UNKNOWN = SignalState(SignalColor.UNKNOWN, false)
    }
}

data class StateChange(val from: SignalState, val to: SignalState)

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 차량 신호 (Pleos Vehicle SDK / Android Car API / 시나리오에서 들어옴) */
data class VehicleState(
    val speedKmh: Float,
    val gear: Gear = Gear.DRIVE,
    val brakePedal: Boolean? = null,
)
