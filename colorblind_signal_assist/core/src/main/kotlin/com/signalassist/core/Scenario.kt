package com.signalassist.core

import kotlin.math.max

/** 시간 구간별 신호 */
data class SignalPhase(val startMs: Long, val state: SignalState)

/** 이 시각의 차속 (구간 사이는 선형 보간) */
data class SpeedKey(val timeMs: Long, val speedKmh: Float)

/**
 * 교차로 접근 시나리오. 차속을 적분해 신호등까지 거리를 계산하고,
 * 거리에 따라 신호등 크기/위치를 바꿔가며 합성 프레임을 만든다.
 */
data class Scenario(
    val name: String,
    val description: String,
    val durationMs: Long,
    val phases: List<SignalPhase>,
    val speeds: List<SpeedKey>,
    /** t=0 에서 신호등까지 거리(m) */
    val startDistanceM: Float,
    val lighting: Lighting = Lighting.DAY,
    val leadCar: Boolean = true,
) {
    fun signalAt(tMs: Long): SignalState =
        phases.lastOrNull { it.startMs <= tMs }?.state ?: SignalState.UNKNOWN

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

        /** 황색 -> 적색에 정상 감속·정차, 초록불로 바뀐 뒤 3초 넘게 출발하지 않음 */
        val STOP_AND_GO = Scenario(
            name = "stop_and_go",
            description = "황색 신호에 감속·정차 → 적색 대기 → 초록불 전환 후 늦은 출발",
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
            description = "적색 신호를 인지하지 못하고 45km/h 로 접근 → 긴급 경고 → 급제동",
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

        /** 야간, 적색 + 좌회전 화살표 동시 신호 */
        val NIGHT_LEFT_TURN = Scenario(
            name = "night_left_turn",
            description = "야간 정차 중 적색+좌회전 화살표 점등 → 소등 → 초록불",
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
            leadCar = true,
        )

        val ALL = listOf(STOP_AND_GO, RED_LIGHT_RISK, NIGHT_LEFT_TURN)
    }
}

data class ScenarioSample(
    val timestampMs: Long,
    val groundTruth: SignalState,
    val vehicle: VehicleState,
    val distanceM: Float,
    val frame: Frame,
)

/**
 * 시나리오를 일정 fps 로 재생하며 (합성 프레임, 차량 상태) 쌍을 만든다.
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

    private val distances: FloatArray = FloatArray(frameCount).also { d ->
        var dist = scenario.startDistanceM
        for (i in d.indices) {
            d[i] = dist
            val v = scenario.speedAt(i * frameIntervalMs) / 3.6f
            dist = max(6f, dist - v * frameIntervalMs / 1000f)
        }
    }

    fun sample(index: Int): ScenarioSample {
        val t = index * frameIntervalMs
        val distance = distances[index.coerceIn(0, frameCount - 1)]
        val truth = scenario.signalAt(t)
        val speed = scenario.speedAt(t)
        val horizon = height * 0.55f
        // 주행 중 카메라 흔들림
        val shake = if (speed > 1f) ((index * 7919) % 5 - 2).toFloat() else 0f
        val spec = SceneSpec(
            width = width,
            height = height,
            signal = truth,
            lightCenterX = width / 2f + focalPx * lightLateralM / distance,
            lightCenterY = (horizon - focalPx * lightHeightAboveCamM / distance + shake).coerceAtLeast(20f),
            lampRadius = focalPx * lampRadiusM / distance,
            lighting = scenario.lighting,
            noiseSigma = if (scenario.lighting == Lighting.NIGHT) 9f else 6f,
            leadCarBrakeLights = scenario.leadCar && speed < 20f,
            seed = index,
        )
        return ScenarioSample(
            timestampMs = t,
            groundTruth = truth,
            vehicle = VehicleState(speedKmh = speed, gear = Gear.DRIVE),
            distanceM = distance,
            frame = SyntheticSceneGenerator.render(spec),
        )
    }

    fun samples(): Sequence<ScenarioSample> = (0 until frameCount).asSequence().map(::sample)
}
