package ai.parkingguide.core

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 차량 좌표계 (단위 cm)
 * - 원점: 뒷범퍼 중앙 바로 아래 지면
 * - X: 화면 오른쪽(+). 후방카메라 화면은 거울처럼 보이므로 화면 왼쪽 = 운전석 쪽(국내 차량)
 * - Y: 차량 뒤쪽(+). 차체는 Y ∈ [-전장, 0]
 */
data class Point(val x: Double, val y: Double)

fun deg(rad: Double) = Math.toDegrees(rad)
fun rad(deg: Double) = Math.toRadians(deg)

/** 좌우 주차선: X = a + b·Y (선 중심). */
data class LineEstimate(
    val a: Double,
    val b: Double,
    val widthCm: Double,
    val yMin: Double,
    val yMax: Double,
    val support: Int = 0,
    val coverage: Double = 1.0, // 선 길이 중 페인트가 보이는 비율
) {
    fun xAt(y: Double) = a + b * y
    val angleDeg get() = deg(atan(b))
    val lengthCm get() = (yMax - yMin) * sqrt(1 + b * b)
}

/** 주차칸 끝 가로선: Y = c + d·X. */
data class EndLine(val c: Double, val d: Double, val widthCm: Double, val xMin: Double, val xMax: Double) {
    fun yAt(x: Double) = c + d * x
    val lengthCm get() = (xMax - xMin) * sqrt(1 + d * d)
}

data class SlotEstimate(
    val left: LineEstimate,
    val right: LineEstimate,
    val end: EndLine? = null,
    val targetBiasCm: Double = 0.0, // 하차 모드로 옮긴 목표 (+ = 오른쪽)
    val confidence: Double = 1.0,
) {
    fun centerXAt(y: Double) = 0.5 * (left.xAt(y) + right.xAt(y))
    val slope get() = 0.5 * (left.b + right.b)
    val yawDeg get() = deg(atan(slope))
    fun targetXAt(y: Double) = centerXAt(y) + targetBiasCm / cos(atan(slope))

    /** 선 중심 간 거리 (주차칸 축에 수직). */
    val widthCm get() = (right.xAt(0.0) - left.xAt(0.0)) * cos(atan(slope))
    val halfInnerWidthCm get() = 0.5 * (widthCm - 0.5 * (left.widthCm + right.widthCm))

    fun endYFor(line: LineEstimate): Double {
        val e = end ?: return line.yMax
        return (e.c + e.d * line.a) / (1 - e.d * line.b)
    }

    val farEndY get() = maxOf(endYFor(left), endYFor(right))

    /** 뒷범퍼 중앙에서 끝선 안쪽 가장자리까지 수직 거리. */
    val endDistCm: Double?
        get() = end?.let { it.c / sqrt(1 + it.d * it.d) - it.widthCm / 2 }

    /**
     * 차가 뒷바퀴 축 기준 [dsCm] 만큼 곡률 [kappa]로 후진한 뒤, 새 차량 좌표계에서 본 주차칸.
     * 지면에 고정된 점은 차와 반대로 움직이므로 같은 원호를 -ds 만큼 되돌린 것과 같다.
     */
    fun moved(dsCm: Double, kappa: Double = 0.0, axleY: Double = 0.0): SlotEstimate {
        fun tf(p: Point): Point {
            if (abs(kappa) < 1e-9) return Point(p.x, p.y - dsCm)
            val r = 1.0 / kappa
            val phi = -dsCm * kappa
            val vx = p.x - r
            val vy = p.y - axleY
            return Point(r + vx * cos(phi) + vy * sin(phi), axleY - vx * sin(phi) + vy * cos(phi))
        }

        fun mv(l: LineEstimate): LineEstimate {
            val p0 = tf(Point(l.xAt(l.yMin), l.yMin))
            val p1 = tf(Point(l.xAt(l.yMax), l.yMax))
            val b = (p1.x - p0.x) / (p1.y - p0.y)
            return l.copy(a = p0.x - b * p0.y, b = b, yMin = minOf(p0.y, p1.y), yMax = maxOf(p0.y, p1.y))
        }

        val newEnd = end?.let { e ->
            val p0 = tf(Point(e.xMin, e.yAt(e.xMin)))
            val p1 = tf(Point(e.xMax, e.yAt(e.xMax)))
            val d = (p1.y - p0.y) / (p1.x - p0.x)
            EndLine(p0.y - d * p0.x, d, e.widthCm, minOf(p0.x, p1.x), maxOf(p0.x, p1.x))
        }
        return copy(left = mv(left), right = mv(right), end = newEnd)
    }
}
