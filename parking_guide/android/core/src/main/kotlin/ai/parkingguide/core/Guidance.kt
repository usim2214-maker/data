package ai.parkingguide.core

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/*
 * 부호 규칙
 * - 핸들 각도(핸들 회전각): 오른쪽(시계방향) +. Pleos SteeringWheel은 '앞바퀴 각도'(왼쪽 -)를 주므로
 *   VehicleSpec.steeringWheelFromFrontWheel 로 바꿔서 넣는다.
 * - 후진할 때 핸들을 오른쪽으로 돌리면 차 뒤쪽이 오른쪽(+X)으로 휘어 간다.
 */

const val LOW_CONFIDENCE = 0.45 // 이보다 낮으면 핸들 안내를 하지 않는다
const val GHOST_CONFIDENCE = 0.5 // 고스트카(예측)는 이 이상일 때만
const val FINAL_ZONE_CM = 250.0 // 끝선까지 이 거리 안쪽을 마무리 구간으로 본다

fun wheelToCurvature(steerWheelDeg: Double, v: VehicleSpec): Double {
    val delta = rad((steerWheelDeg / v.steeringRatio).coerceIn(-v.maxWheelAngleDeg, v.maxWheelAngleDeg))
    return tan(delta) / v.wheelbase
}

fun curvatureToWheel(kappa: Double, v: VehicleSpec): Double {
    val delta = deg(atan(kappa * v.wheelbase)).coerceIn(-v.maxWheelAngleDeg, v.maxWheelAngleDeg)
    return delta * v.steeringRatio
}

/** 차체 위 점들을 '뒷바퀴 축이 s만큼 후진(곡률 k)'한 뒤의 위치로 옮긴다. */
fun movePoints(pts: List<Point>, k: Double, s: Double, ya: Double): List<Point> {
    if (abs(k) < 1e-7) return pts.map { Point(it.x, it.y + s) }
    val r = 1.0 / k
    val phi = s * k
    return pts.map {
        val vx = it.x - r
        val vy = it.y - ya
        Point(r + vx * cos(phi) + vy * sin(phi), ya - vx * sin(phi) + vy * cos(phi))
    }
}

data class PredictedPath(val left: List<Point>, val right: List<Point>, val center: List<Point>)

/** 지금 핸들 각도로 [distCm] 만큼 후진할 때 뒷범퍼 좌/우 모서리와 중앙이 지나는 자리. */
fun predictPath(v: VehicleSpec, steerWheelDeg: Double, distCm: Double, n: Int = 40): PredictedPath {
    val k = wheelToCurvature(steerWheelDeg, v)
    val ya = -v.rearOverhangCm
    fun trace(x0: Double) = (0 until n).map { i ->
        val s = distCm * i / (n - 1)
        movePoints(listOf(Point(x0, 0.0)), k, s, ya)[0]
    }
    return PredictedPath(trace(-v.widthCm / 2), trace(v.widthCm / 2), trace(0.0))
}

/** 지금 핸들 각도를 그대로 유지했을 때 끝선 앞에서 멈춘 모습 (고스트카). */
data class FinalPrediction(
    val corners: List<Point>, // 뒤왼쪽, 뒤오른쪽, 앞오른쪽, 앞왼쪽 (현재 차량 좌표계)
    val clearLeftCm: Double,
    val clearRightCm: Double,
    val offsetCm: Double,
    val yawDeg: Double,
    val travelCm: Double,
    val frontOffsetCm: Double,
) {
    /** 마지막 1~2m에선 차를 옆으로 옮길 수 없으므로 비뚤어짐에 더 큰 가중치를 준다. */
    val cost: Double
        get() {
            val center = 0.5 * (offsetCm + frontOffsetCm)
            val skew = frontOffsetCm - offsetCm
            val pen = maxOf(0.0, -clearLeftCm) + maxOf(0.0, -clearRightCm)
            return center * center + 4.0 * skew * skew + 20.0 * pen * pen
        }

    val status: AlignStatus
        get() = when {
            minOf(clearLeftCm, clearRightCm) < 0 -> AlignStatus.DANGER
            abs(offsetCm) < 8 && abs(yawDeg) < 1.5 -> AlignStatus.GOOD
            else -> AlignStatus.ADJUST
        }
}

fun predictFinal(
    slot: SlotEstimate, v: VehicleSpec, steerDeg: Double, endDistCm: Double?,
    stopMarginCm: Double = 25.0, defaultTravelCm: Double = 300.0,
): FinalPrediction {
    val k = wheelToCurvature(steerDeg, v)
    val ya = -v.rearOverhangCm
    val travel = if (endDistCm == null) defaultTravelCm else maxOf(0.0, endDistCm - stopMarginCm)
    val hw = v.widthCm / 2
    val l = v.lengthCm
    val body = listOf(Point(-hw, 0.0), Point(hw, 0.0), Point(hw, -l), Point(-hw, -l), Point(0.0, 0.0), Point(0.0, -l))
    val moved = movePoints(body, k, travel, ya)
    val corners = moved.subList(0, 4)
    val rearC = moved[4]
    val frontC = moved[5]
    val cosT = cos(atan(slot.slope))
    fun leftClear(p: Point) = (p.x - (slot.left.xAt(p.y) + slot.left.widthCm / 2)) * cosT
    fun rightClear(p: Point) = ((slot.right.xAt(p.y) - slot.right.widthCm / 2) - p.x) * cosT
    val cl = minOf(leftClear(corners[0]), leftClear(corners[3]))
    val cr = minOf(rightClear(corners[1]), rightClear(corners[2]))
    val offset = (rearC.x - slot.targetXAt(rearC.y)) * cosT
    val frontOff = (frontC.x - slot.targetXAt(frontC.y)) * cosT
    val carAng = deg(atan2(rearC.x - frontC.x, rearC.y - frontC.y))
    return FinalPrediction(corners, cl, cr, offset, slot.yawDeg - carAng, travel, frontOff)
}

/** 핸들을 그 각도로 고정한 채 끝까지 갔을 때 가장 반듯하게 목표에 멈추는 핸들 각도. */
fun bestConstantSteering(slot: SlotEstimate, v: VehicleSpec, endDistCm: Double?): Double {
    val full = v.maxSteerWheelDeg
    fun cost(w: Double) = predictFinal(slot, v, w, endDistCm).cost
    var best = generateSequence(-full) { it + 15.0 }.takeWhile { it <= full + 1e-6 }.minBy(::cost)
    for (step in listOf(5.0, 1.0)) {
        best = (-3..3).map { best + it * step }.filter { abs(it) <= full }.minBy(::cost)
    }
    return best
}

/** 뒷바퀴 축이 목표선 위의 점(lookahead 앞)을 향하게 하는 핸들 각도 (pure pursuit). S자 조작을 만들어 낸다. */
fun desiredSteering(slot: SlotEstimate, v: VehicleSpec, lookaheadCm: Double = 250.0): Double {
    val ya = -v.rearOverhangCm
    val yt = ya + lookaheadCm
    val xt = slot.targetXAt(yt)
    val ly = yt - ya
    return curvatureToWheel(2.0 * xt / (xt * xt + ly * ly), v)
}

/** 핸들 회전량을 초보도 감이 오는 '바퀴' 단위로. */
fun turnsPhraseKo(deg: Double): String {
    val a = abs(deg)
    return when {
        a < 60 -> "살짝"
        a < 135 -> "1/4바퀴"
        a < 225 -> "반 바퀴"
        a < 315 -> "3/4바퀴"
        a < 450 -> "한 바퀴"
        else -> "한 바퀴 반"
    }
}

enum class GuidanceLevel { OK, WARN, STOP, INFO }

data class Guidance(
    val level: GuidanceLevel,
    val key: String, // 문구 종류 (흔들림 방지용)
    val text: String,
    val arrow: Int = 0, // -1 왼쪽, +1 오른쪽
    val sub: String = "",
    val desiredSteerDeg: Double? = null,
    val currentSteerDeg: Double? = null,
)

private fun fmt1(v: Double) = String.format(java.util.Locale.ROOT, "%.1f", v) // 기기 언어 설정과 무관하게 "1.8"

fun buildGuidance(
    align: Alignment?, slot: SlotEstimate?, v: VehicleSpec, steerDeg: Double? = null,
    stopCm: Double = 30.0, slowCm: Double = 100.0, toleranceDeg: Double = 30.0,
): Guidance {
    if (align == null || slot == null) return Guidance(GuidanceLevel.INFO, "search", "주차선을 찾고 있어요")
    val end = align.endDistCm
    var sub = if (end != null) "끝선까지 ${fmt1(maxOf(end, 0.0) / 100)}m" else ""
    if (end != null && end <= stopCm) return Guidance(GuidanceLevel.STOP, "stop", "멈추세요", 0, sub)
    if (slot.confidence < LOW_CONFIDENCE) {
        // 확실하지 않을 땐 확실하지 않다고 말한다
        return Guidance(GuidanceLevel.INFO, "lowconf", "주차선이 희미해요", 0, "직접 확인하며 천천히 후진하세요")
    }
    val near = end != null && end <= slowCm
    val finalZone = end != null && end <= FINAL_ZONE_CM
    val desired: Double
    if (finalZone) {
        desired = bestConstantSteering(slot, v, end)
        if (predictFinal(slot, v, desired, end).status == AlignStatus.DANGER) {
            return Guidance(GuidanceLevel.WARN, "retry", "앞으로 나갔다가 다시 넣으세요", 0, sub, desired, steerDeg)
        }
    } else {
        desired = desiredSteering(slot, v, 250.0)
    }

    val g: Guidance
    if (steerDeg != null) {
        val diff = desired - steerDeg
        val landingOk = finalZone && predictFinal(slot, v, steerDeg, end).status == AlignStatus.GOOD
        g = if (abs(diff) < toleranceDeg || landingOk) {
            if (near) Guidance(GuidanceLevel.OK, "slow", "천천히, 거의 다 왔어요")
            else Guidance(GuidanceLevel.OK, "keep", "좋아요, 그대로 후진하세요")
        } else {
            val arrow = if (diff > 0) 1 else -1
            val side = if (arrow > 0) "오른쪽" else "왼쪽"
            if (abs(desired) < toleranceDeg && abs(steerDeg) >= toleranceDeg) {
                Guidance(GuidanceLevel.WARN, "unwind$arrow", "핸들을 풀어 주세요", arrow)
            } else {
                Guidance(GuidanceLevel.WARN, "turn$arrow${if (abs(diff) < 150) "s" else ""}",
                    "핸들을 ${side}으로 ${turnsPhraseKo(diff)} 더", arrow)
            }
        }
    } else {
        g = if (align.status == AlignStatus.GOOD) {
            Guidance(GuidanceLevel.OK, "centered", "좌우 균형이 좋아요")
        } else {
            val arrow = if (desired > 0) 1 else -1
            Guidance(GuidanceLevel.WARN, "hint$arrow", "차 뒤를 ${if (arrow > 0) "오른쪽" else "왼쪽"}으로 보내세요", arrow)
        }
    }
    if (finalZone && steerDeg != null && g.level == GuidanceLevel.OK) {
        val landing = predictFinal(slot, v, steerDeg, end)
        if (landing.status != AlignStatus.GOOD && abs(landing.offsetCm) >= 5) {
            // 더 고칠 수 없더라도 결과를 솔직하게 알려 준다
            val side = if (landing.offsetCm > 0) "오른쪽" else "왼쪽"
            val ref = if (slot.targetBiasCm != 0.0) "목표보다 " else ""
            sub += " · $ref$side ${abs(landing.offsetCm).roundToInt()}cm"
        }
    }
    return g.copy(sub = sub, desiredSteerDeg = desired, currentSteerDeg = steerDeg)
}

/** 안내 문구가 깜빡이지 않도록 새 문구가 몇 프레임 이어질 때만 바꾼다. '멈추세요'는 즉시. */
class GuidanceStabilizer(private val holdFrames: Int = 4) {
    private var current: Guidance? = null
    private var pendingKey: String? = null
    private var count = 0

    fun update(g: Guidance): Guidance {
        val cur = current
        if (cur == null || g.level == GuidanceLevel.STOP || g.key == cur.key) {
            current = g; pendingKey = null; count = 0
            return g
        }
        if (g.key == pendingKey) count++ else { pendingKey = g.key; count = 1 }
        if (count >= holdFrames) {
            current = g; pendingKey = null; count = 0
            return g
        }
        // 문구는 유지하되 거리 같은 보조 정보는 최신 값으로
        return cur.copy(sub = g.sub).also { current = it }
    }
}
