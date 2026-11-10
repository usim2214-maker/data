package com.signalassist.core

import kotlin.math.max

/** 시간 구간별 신호. state 가 null 이면 그 구간엔 신호등이 보이지 않음 */
data class SignalPhase(val startMs: Long, val state: SignalState?)

/** 이 시각의 차속 (구간 사이는 선형 보간) */
data class SpeedKey(val timeMs: Long, val speedKmh: Float)

/** 이 시각부터 적용되는 실내 상태 (이전 값을 덮어씀) */
data class CabinKey(val timeMs: Long, val cabin: CabinState)

/** 출발점에서 [atTraveledM] 만큼 간 곳의 안내 지점 */
data class RouteManeuver(val atTraveledM: Float, val maneuver: Maneuver, val roadName: String? = null)

data class GearKey(val timeMs: Long, val gear: Gear)

/**
 * 주행 시나리오. 차속을 적분해 이동거리/신호등까지 거리를 계산하고,
 * 거리에 따라 신호등 크기/위치를 바꿔가며 합성 프레임을 만든다.
 * 실내 상태(벨트·문·방향지시등…)와 내비 경로도 함께 재생한다.
 */
data class Scenario(
    val name: String,
    val titleKo: String,
    val description: String,
    /** 이 시나리오가 보여주려는 운전자 특성 */
    val targetNeeds: Set<DriverNeed>,
    val durationMs: Long,
    val phases: List<SignalPhase>,
    val speeds: List<SpeedKey>,
    /** t=0 에서 신호등까지 거리(m) */
    val startDistanceM: Float = 60f,
    val lighting: Lighting = Lighting.DAY,
    val leadCar: Boolean = true,
    val cabin: List<CabinKey> = emptyList(),
    val route: List<RouteManeuver> = emptyList(),
    val gears: List<GearKey> = listOf(GearKey(0, Gear.DRIVE)),
    /** 긴급차량 사이렌이 들리는 구간 (ms) */
    val sirenWindows: List<LongRange> = emptyList(),
) {
    fun sirenAt(tMs: Long): Boolean = sirenWindows.any { tMs in it }

    fun signalAt(tMs: Long): SignalState? = phases.lastOrNull { it.startMs <= tMs }?.state

    fun cabinAt(tMs: Long): CabinState = cabin.lastOrNull { it.timeMs <= tMs }?.cabin ?: CabinState()

    fun gearAt(tMs: Long): Gear = gears.lastOrNull { it.timeMs <= tMs }?.gear ?: Gear.UNKNOWN

    fun navigationAt(traveledM: Float): NavigationState? {
        if (route.isEmpty()) return null
        val next = route.firstOrNull { it.atTraveledM > traveledM } ?: return NavigationState(guiding = false)
        return NavigationState(true, next.maneuver, next.atTraveledM - traveledM, next.roadName)
    }

    fun speedAt(tMs: Long): Float {
        if (tMs <= speeds.first().timeMs) return speeds.first().speedKmh
        for (i in 1 until speeds.size) {
            val a = speeds[i - 1]; val b = speeds[i]
            if (tMs <= b.timeMs) {
                val t = (tMs - a.timeMs).toFloat() / (b.timeMs - a.timeMs)
                return a.speedKmh + (b.speedKmh - a.speedKmh) * t
            }
        }
        return speeds.last().speedKmh
    }

    companion object {
        private fun s(c: SignalColor, arrow: Boolean = false) = SignalState(c, arrow)
        private val CV = setOf(DriverNeed.COLOR_VISION)

        /** 황색 -> 적색에 정상 감속·정차, 초록불로 바뀐 뒤 3초 넘게 출발하지 않음 */
        val STOP_AND_GO = Scenario(
            name = "stop_and_go",
            titleKo = "색각이상 · 정차 후 출발",
            description = "황색 신호에 감속·정차 → 적색 대기 → 초록불 전환 후 늦은 출발",
            targetNeeds = CV + DriverNeed.ATTENTION,
            durationMs = 22_000,
            phases = listOf(
                SignalPhase(0, s(SignalColor.GREEN)),
                SignalPhase(1_500, s(SignalColor.YELLOW)),
                SignalPhase(4_500, s(SignalColor.RED)),
                SignalPhase(13_000, s(SignalColor.GREEN)),
            ),
            speeds = listOf(
                SpeedKey(0, 35f), SpeedKey(1_800, 35f), SpeedKey(7_000, 0f),
                SpeedKey(17_500, 0f), SpeedKey(22_000, 30f),
            ),
            startDistanceM = 60f,
        )

        /** 운전자가 적색을 못 알아채고 고속으로 접근 -> 긴급 경고 후 급제동 */
        val RED_LIGHT_RISK = Scenario(
            name = "red_light_risk",
            titleKo = "색각이상 · 적색 신호 놓침",
            description = "적색 신호를 인지하지 못하고 45km/h 로 접근 → 긴급 경고 → 급제동",
            targetNeeds = CV,
            durationMs = 11_000,
            phases = listOf(
                SignalPhase(0, s(SignalColor.GREEN)),
                SignalPhase(1_000, s(SignalColor.YELLOW)),
                SignalPhase(3_500, s(SignalColor.RED)),
            ),
            speeds = listOf(
                SpeedKey(0, 45f), SpeedKey(7_500, 45f), SpeedKey(9_500, 0f), SpeedKey(11_000, 0f),
            ),
            startDistanceM = 120f,
        )

        /** 정지선 직전에 황색: 급정거하면 뒤차 추돌 위험 -> '정지' 대신 '주의해서 진행' */
        val YELLOW_DILEMMA = Scenario(
            name = "yellow_dilemma",
            titleKo = "색각이상 · 황색 딜레마 구간",
            description = "50km/h 로 신호등 45m 앞에서 황색 전환 → 급정거 대신 주의 진행 안내 → 교차로 통과 후 적색 (경고 없음)",
            targetNeeds = CV,
            durationMs = 9_000,
            phases = listOf(
                SignalPhase(0, s(SignalColor.GREEN)),
                SignalPhase(1_800, s(SignalColor.YELLOW)),
                SignalPhase(4_800, s(SignalColor.RED)),
            ),
            speeds = listOf(SpeedKey(0, 50f), SpeedKey(9_000, 50f)),
            startDistanceM = 70f,
        )

        /** 정지선 바로 앞에 서서 신호등이 카메라 화각을 벗어남 -> 침묵하지 않고 '직접 확인' 안내 */
        val SIGNAL_OUT_OF_VIEW = Scenario(
            name = "signal_out_of_view",
            titleKo = "색각이상 · 신호등이 화면 밖으로",
            description = "적색에 정지선 바로 앞까지 붙어 정차 → 신호등이 화각을 벗어남 → '직접 확인하세요' 안내",
            targetNeeds = CV,
            durationMs = 16_000,
            phases = listOf(SignalPhase(0, s(SignalColor.RED)), SignalPhase(12_000, s(SignalColor.GREEN))),
            speeds = listOf(SpeedKey(0, 30f), SpeedKey(2_000, 30f), SpeedKey(7_000, 0f), SpeedKey(16_000, 0f)),
            startDistanceM = 45f,
        )

        /** 야간, 적색 + 좌회전 화살표 동시 신호. 내비 경로는 좌회전 */
        val NIGHT_LEFT_TURN = Scenario(
            name = "night_left_turn",
            titleKo = "색각이상 · 야간 좌회전 신호",
            description = "야간 정차 중 적색+좌회전 화살표 점등 (내비 경로도 좌회전) → 초록불",
            targetNeeds = CV,
            durationMs = 18_000,
            phases = listOf(
                SignalPhase(0, s(SignalColor.RED)),
                SignalPhase(4_000, s(SignalColor.RED, arrow = true)),
                SignalPhase(10_000, s(SignalColor.YELLOW)),
                SignalPhase(12_000, s(SignalColor.RED)),
                SignalPhase(14_000, s(SignalColor.GREEN)),
            ),
            speeds = listOf(SpeedKey(0, 0f), SpeedKey(15_000, 0f), SpeedKey(18_000, 25f)),
            startDistanceM = 22f,
            lighting = Lighting.NIGHT,
            route = listOf(RouteManeuver(20f, Maneuver.LEFT, "테헤란로")),
        )

        /** 청각장애 운전자: 벨트 경고음·내비 음성·방향지시등 소리를 듣지 못하는 상황 */
        val HEARING_DRIVE = Scenario(
            name = "hearing_drive",
            titleKo = "청각장애 · 소리 경고 시각화",
            description = "벨트 미착용 출발 → 내비 좌회전 안내(카드) → 타이어 저압 → 뒤에서 구급차 사이렌 → 방향지시등 끄는 것을 잊음",
            targetNeeds = setOf(DriverNeed.HEARING),
            durationMs = 40_000,
            phases = listOf(SignalPhase(0, null)),
            speeds = listOf(SpeedKey(0, 0f), SpeedKey(5_000, 50f), SpeedKey(15_000, 50f), SpeedKey(18_000, 20f),
                SpeedKey(21_000, 50f), SpeedKey(40_000, 50f)),
            cabin = listOf(
                CabinKey(0, CabinState(driverSeatbeltBuckled = false, doorOpen = false, lowTirePressure = false, turnSignal = TurnSignal.NONE)),
                CabinKey(12_000, CabinState(driverSeatbeltBuckled = true, doorOpen = false, lowTirePressure = false, turnSignal = TurnSignal.NONE)),
                CabinKey(15_000, CabinState(driverSeatbeltBuckled = true, doorOpen = false, lowTirePressure = false, turnSignal = TurnSignal.LEFT)),
                CabinKey(26_000, CabinState(driverSeatbeltBuckled = true, doorOpen = false, lowTirePressure = true, turnSignal = TurnSignal.LEFT)),
            ),
            route = listOf(
                RouteManeuver(250f, Maneuver.LEFT, "강남대로"),
                RouteManeuver(1_200f, Maneuver.RIGHT, "도산대로"),
            ),
            sirenWindows = listOf(29_000L..33_000L),
        )

        /** 주의력·고령 운전자: 야간 전조등 미점등, 도착 후 뒷좌석 아이 확인 */
        val NIGHT_ARRIVAL = Scenario(
            name = "night_arrival",
            titleKo = "주의력·저시력 · 야간 도착",
            description = "전조등을 켜지 않고 출발 → 목적지 안내 → 주차(P) 후 뒷좌석 확인 알림",
            targetNeeds = setOf(DriverNeed.ATTENTION, DriverNeed.LOW_VISION),
            durationMs = 20_000,
            phases = listOf(SignalPhase(0, null)),
            speeds = listOf(SpeedKey(0, 30f), SpeedKey(8_000, 30f), SpeedKey(13_000, 0f), SpeedKey(20_000, 0f)),
            lighting = Lighting.NIGHT,
            leadCar = false,
            cabin = listOf(
                CabinKey(0, CabinState(isDark = true, headlightsOn = false, rearSeatOccupied = true, doorOpen = false, driverSeatbeltBuckled = true)),
                CabinKey(4_000, CabinState(isDark = true, headlightsOn = true, rearSeatOccupied = true, doorOpen = false, driverSeatbeltBuckled = true)),
                CabinKey(17_000, CabinState(isDark = true, headlightsOn = true, rearSeatOccupied = true, doorOpen = true, driverSeatbeltBuckled = false)),
            ),
            route = listOf(RouteManeuver(85f, Maneuver.DESTINATION)),
            gears = listOf(GearKey(0, Gear.DRIVE), GearKey(15_000, Gear.PARK)),
        )

        val ALL = listOf(STOP_AND_GO, RED_LIGHT_RISK, YELLOW_DILEMMA, SIGNAL_OUT_OF_VIEW, NIGHT_LEFT_TURN, HEARING_DRIVE, NIGHT_ARRIVAL)
    }
}

data class ScenarioSample(
    val timestampMs: Long,
    val groundTruth: SignalState?,
    val vehicle: VehicleState,
    val cabin: CabinState,
    val navigation: NavigationState?,
    val distanceM: Float,
    val frame: Frame,
    val sirenNearby: Boolean = false,
) {
    fun toInput() = DrivingInput(timestampMs, vehicle, frame, cabin, navigation, sirenNearby)
}

/**
 * 시나리오를 일정 fps 로 재생하며 (합성 프레임, 차량·실내·내비 상태)를 만든다.
 * Android 앱의 '시뮬레이션 모드'와 PC 데모·테스트가 같은 코드를 쓴다.
 */
class ScenarioPlayer(
    val scenario: Scenario,
    val fps: Int = 10,
    private val width: Int = 960,
    private val height: Int = 540,
) {
    /** 수평 화각 약 60° 카메라의 초점거리(px) */
    private val focalPx = width * 0.866f
    private val lampRadiusM = 0.15f // 300mm 등화
    private val lightHeightAboveCamM = 4.0f
    private val lightLateralM = 1.0f
    private val frameIntervalMs = 1000L / fps

    val frameCount: Int get() = (scenario.durationMs / frameIntervalMs).toInt() + 1

    private val traveled = FloatArray(frameCount)
    private val distances = FloatArray(frameCount)
    /** 신호등 바로 아래(8m 이내)를 지나면 카메라 화각에서 벗어남 */
    private val passed = BooleanArray(frameCount)

    init {
        var dist = scenario.startDistanceM
        var trav = 0f
        for (i in 0 until frameCount) {
            distances[i] = max(6f, dist)
            passed[i] = dist < 8f
            traveled[i] = trav
            val step = scenario.speedAt(i * frameIntervalMs) / 3.6f * frameIntervalMs / 1000f
            dist -= step
            trav += step
        }
    }

    fun sample(index: Int): ScenarioSample {
        val i = index.coerceIn(0, frameCount - 1)
        val t = i * frameIntervalMs
        val distance = distances[i]
        val truth = scenario.signalAt(t)
        val speed = scenario.speedAt(t)
        val horizon = height * 0.55f
        // 주행 중 카메라 흔들림
        val shake = if (speed > 1f) ((i * 7919) % 5 - 2).toFloat() else 0f
        val spec = SceneSpec(
            width = width,
            height = height,
            signal = truth ?: SignalState.UNKNOWN,
            trafficLightVisible = truth != null && !passed[i],
            lightCenterX = width / 2f + focalPx * lightLateralM / distance,
            lightCenterY = (horizon - focalPx * lightHeightAboveCamM / distance + shake).coerceAtLeast(20f),
            lampRadius = focalPx * lampRadiusM / distance,
            lighting = scenario.lighting,
            noiseSigma = if (scenario.lighting == Lighting.NIGHT) 9f else 6f,
            leadCarBrakeLights = scenario.leadCar && speed < 20f,
            distractors = true,
            seed = i,
        )
        return ScenarioSample(
            timestampMs = t,
            groundTruth = truth.takeUnless { passed[i] },
            vehicle = VehicleState(speedKmh = speed, gear = scenario.gearAt(t)),
            cabin = scenario.cabinAt(t),
            navigation = scenario.navigationAt(traveled[i]),
            distanceM = distance,
            frame = SyntheticSceneGenerator.render(spec),
            sirenNearby = scenario.sirenAt(t),
        )
    }

    fun samples(): Sequence<ScenarioSample> = (0 until frameCount).asSequence().map(::sample)
}
