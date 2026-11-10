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

/**
 * 주행 신호. 차속·기어는 Pleos Vehicle State 에 없어서 AAOS 표준 프로퍼티
 * (PERF_VEHICLE_SPEED, GEAR_SELECTION)로 읽는다. 기어를 못 읽으면 UNKNOWN 이고, 이때는 차속만으로 판단한다.
 */
data class VehicleState(
    val speedKmh: Float,
    val gear: Gear = Gear.UNKNOWN,
    /** Pleos Vehicle State `getCurrentBrakePedalStatus` */
    val brakePedal: Boolean? = null,
)

enum class TurnSignal { NONE, LEFT, RIGHT, HAZARD }

/**
 * 실내·차체 상태. 값을 못 읽는 항목은 null (해당 규칙은 꺼짐).
 * 괄호 안은 대응하는 Pleos Vehicle State API.
 */
data class CabinState(
    /** 운전석 안전벨트 체결 (`getCurrentSeatBeltBuckledState`) */
    val driverSeatbeltBuckled: Boolean? = null,
    /** 차량이 띄우는 벨트 미착용 경고 (`hasSeatBeltNotBuckledWarning`) */
    val seatbeltWarning: Boolean? = null,
    /** 문이 하나라도 열림 (`getCurrentDoorPosition`) */
    val doorOpen: Boolean? = null,
    /** 타이어 저압 경고 (`getLowTirePressure`) */
    val lowTirePressure: Boolean? = null,
    /** 방향지시등 (`getCurrentTurnSignalState`) */
    val turnSignal: TurnSignal? = null,
    /** 전조등 (`getCurrentLowBeamLightState`) */
    val headlightsOn: Boolean? = null,
    /** 주변이 어두움 (AAOS NIGHT_MODE 또는 시각 기반) */
    val isDark: Boolean? = null,
    /** 뒷좌석 탑승 감지 (`getCurrentSeatOccupancy`) */
    val rearSeatOccupied: Boolean? = null,
)

enum class Maneuver(val labelKo: String, val symbol: String) {
    LEFT("좌회전", "↰"),
    RIGHT("우회전", "↱"),
    STRAIGHT("직진", "↑"),
    UTURN("유턴", "↶"),
    EXIT("출구", "↗"),
    DESTINATION("목적지", "◎"),
    OTHER("안내", "•"),
}

/** 내비 안내 상태 (Pleos NaviHelper `getTBTInfo` / `getRouteStateInfo`) */
data class NavigationState(
    val guiding: Boolean,
    val nextManeuver: Maneuver? = null,
    val distanceToManeuverM: Float? = null,
    val roadName: String? = null,
)
