package com.signalassist.core

/**
 * 실제 도로에서 돌린 교통 시뮬레이션(SUMO, OpenStreetMap 도로망)의 '내 차' 주행 기록 한 줄 (0.1초 간격).
 * sim/run_centum.py 가 만든 centum_trace.csv 를 읽는다.
 */
data class TraceStep(
    val tSec: Float,
    val speedKmh: Float,
    /** 다음 신호의 정지선까지 거리(m). 다음 신호가 없으면 null */
    val tlsDistM: Float?,
    /** 내 차가 지나는 연결의 신호 상태 (SUMO: r/y/G/g) */
    val tlsState: Char?,
    val leadGapM: Float?,
    val leadBraking: Boolean,
    val road: String,
    val navManeuver: String,
    val navDistM: Float,
    val navRoad: String,
    val x: Float,
    val y: Float,
    val angleDeg: Float,
    val lat: Double,
    val lon: Double,
    /** 경로상 신호들의 현재 상태 (지도 표시용, 경로 순서) */
    val routeTls: String,
    /** 다음 신호 교차로 이름 (예: "해운대로 · 센텀5로") */
    val junction: String,
    /** 주변 차량 (x, y, 방향) */
    val others: List<Triple<Float, Float, Float>>,
) {
    val signalColor: SignalColor
        get() = when (tlsState) {
            'r', 'R', 's' -> SignalColor.RED
            'y', 'Y' -> SignalColor.YELLOW
            'G', 'g' -> SignalColor.GREEN
            else -> SignalColor.UNKNOWN
        }
}

object TraceParser {
    fun parse(lines: List<String>): List<TraceStep> = lines.drop(1).filter { it.isNotBlank() }.map { line ->
        val c = line.split("|")
        fun f(i: Int) = c[i].toFloatOrNull()
        TraceStep(
            tSec = c[0].toFloat(),
            speedKmh = c[1].toFloat(),
            tlsDistM = f(2)?.takeIf { it >= 0f },
            tlsState = c[3].firstOrNull(),
            leadGapM = f(4),
            leadBraking = c[5] == "1",
            road = c[6],
            navManeuver = c[7],
            navDistM = c[8].toFloat(),
            navRoad = c[9],
            x = c[10].toFloat(), y = c[11].toFloat(), angleDeg = c[12].toFloat(),
            lat = c[13].toDouble(), lon = c[14].toDouble(),
            routeTls = c[15],
            junction = c[16],
            others = if (c.size > 17 && c[17].isNotBlank()) c[17].split(" ").mapNotNull { o ->
                val p = o.split(":"); if (p.size == 3) Triple(p[0].toFloat(), p[1].toFloat(), p[2].toFloat()) else null
            } else emptyList(),
        )
    }
}

/**
 * 실제 도로 주행 기록 -> 엔진 입력. 신호 상태와 거리는 시뮬레이션 값으로 전방 카메라 영상을 합성한다.
 * (카메라가 열리면 이 부분이 실제 영상으로 바뀐다)
 */
class TracePlayer(
    val steps: List<TraceStep>,
    private val width: Int = 960,
    private val height: Int = 540,
    /** 이 거리(정지선 기준) 안의 신호만 카메라에 보인다고 본다 */
    private val visibleRangeM: Float = 150f,
    private val stopLineToLightM: Float = 12f,
    private val lighting: Lighting = Lighting.DAY,
    /**
     * 프레임을 직접 그리는 함수 (예: 실제 건물로 만든 3D 시점). null 이면 단순 합성 장면.
     * 인자: (인덱스, 주행 기록, 보이는 신호 또는 null, 신호등까지 거리 m)
     */
    private val frameRenderer: ((Int, TraceStep, SignalState?, Float) -> Frame)? = null,
) : SampleSource {
    private val focalPx = width * 0.866f

    override val frameCount: Int get() = steps.size

    fun step(index: Int) = steps[index.coerceIn(0, steps.size - 1)]

    override fun sample(index: Int): ScenarioSample {
        val s = step(index)
        val tlsDist = s.tlsDistM
        val visible = tlsDist != null && tlsDist <= visibleRangeM && s.signalColor != SignalColor.UNKNOWN
        val lightDist = ((tlsDist ?: 200f) + stopLineToLightM).coerceAtLeast(6f)
        val truth = if (visible) SignalState(s.signalColor) else null
        val custom = frameRenderer?.invoke(index, s, truth, lightDist)
        val horizon = height * 0.55f
        val shake = if (s.speedKmh > 1f) ((index * 7919) % 5 - 2).toFloat() else 0f
        val spec = SceneSpec(
            width = width, height = height,
            signal = truth ?: SignalState.UNKNOWN,
            trafficLightVisible = visible,
            lightCenterX = width / 2f + focalPx * 1.0f / lightDist,
            lightCenterY = (horizon - focalPx * 4.0f / lightDist + shake).coerceAtLeast(20f),
            lampRadius = focalPx * 0.15f / lightDist,
            lighting = lighting,
            noiseSigma = 6f,
            leadCarVisible = s.leadGapM != null && s.leadGapM < 60f,
            leadCarBrakeLights = s.leadBraking,
            seed = index,
        )
        val nav = NavigationState(
            guiding = true,
            nextManeuver = when (s.navManeuver) { "LEFT" -> Maneuver.LEFT; "RIGHT" -> Maneuver.RIGHT; "DESTINATION" -> Maneuver.DESTINATION; else -> Maneuver.OTHER },
            distanceToManeuverM = s.navDistM,
            roadName = s.navRoad.ifBlank { null },
        )
        return ScenarioSample(
            timestampMs = (s.tSec * 1000).toLong(),
            groundTruth = truth,
            vehicle = VehicleState(s.speedKmh, Gear.DRIVE),
            cabin = CabinState(driverSeatbeltBuckled = true, doorOpen = false),
            navigation = nav,
            distanceM = lightDist,
            frame = custom ?: SyntheticSceneGenerator.render(spec),
        )
    }
}
