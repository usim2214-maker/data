package ai.parkingguide.core

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * 후방카메라 모델 (핀홀 / 어안). 어안은 OpenCV fisheye(등거리 + k1~k4)와 같은 식.
 * 실제 차량에서는 체커보드 보정으로 focal/dist, 설치 높이·각도를 구해 넣는다.
 */
data class CameraModel(
    val width: Int = 1280,
    val height: Int = 720,
    val focalPx: Double = 560.0,
    val mountHeightCm: Double = 100.0,
    val pitchDeg: Double = 30.0, // 아래로 숙인 각도
    val fisheye: Boolean = false,
    val dist: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0, 0.0),
) {
    val cx get() = width / 2.0
    val cy get() = height / 2.0

    private val s get() = sin(rad(pitchDeg))
    private val c get() = cos(rad(pitchDeg))

    /** 정규화 좌표(z=1 평면) → 영상 픽셀. */
    private fun distort(xn: Double, yn: Double): Pair<Double, Double> {
        var x = xn
        var y = yn
        if (fisheye) {
            val r = hypot(xn, yn)
            val th = atan(r)
            val th2 = th * th
            val thd = th * (1 + dist[0] * th2 + dist[1] * th2 * th2 + dist[2] * th2 * th2 * th2 + dist[3] * th2 * th2 * th2 * th2)
            val scale = if (r > 1e-9) thd / r else 1.0
            x *= scale; y *= scale
        }
        return (cx + focalPx * x) to (cy + focalPx * y)
    }

    /** 영상 픽셀 → 정규화 좌표. null이면 시야 밖. */
    fun pixelToRay(u: Double, v: Double): Pair<Double, Double>? {
        val xd = (u - cx) / focalPx
        val yd = (v - cy) / focalPx
        if (!fisheye) return xd to yd
        val thd = hypot(xd, yd)
        var th = thd
        repeat(8) { // 뉴턴법으로 θd → θ
            val th2 = th * th
            val f = th * (1 + dist[0] * th2 + dist[1] * th2 * th2 + dist[2] * th2 * th2 * th2 + dist[3] * th2 * th2 * th2 * th2) - thd
            val df = 1 + 3 * dist[0] * th2 + 5 * dist[1] * th2 * th2 + 7 * dist[2] * th2 * th2 * th2 + 9 * dist[3] * th2 * th2 * th2 * th2
            th -= f / df
        }
        if (th < -1e-6 || th >= rad(88.0)) return null
        val scale = if (thd > 1e-9) tan(th) / thd else 1.0
        return xd * scale to yd * scale
    }

    /** 3D 점 (X, Y, Z) → 영상 (u, v). 카메라 뒤쪽이면 null. */
    fun project(x: Double, y: Double, z: Double): Pair<Double, Double>? {
        val dz = z - mountHeightCm
        val yc = -y * s - dz * c
        val zc = y * c - dz * s
        if (zc <= 1e-3) return null
        return distort(x / zc, yc / zc)
    }

    fun projectGround(x: Double, y: Double) = project(x, y, 0.0)

    /** 영상 맨 아래 가운데 픽셀에 찍히는 지면까지의 거리. */
    fun visibleYMin(): Double {
        val (_, yn) = pixelToRay(cx, height - 1.0) ?: return 50.0
        // 월드 방향 = xn·right + yn·down + fwd
        val dy = -yn * s + c
        val dz = -yn * c - s
        val t = mountHeightCm / -dz
        return t * dy
    }

    override fun equals(other: Any?) = other is CameraModel && other.width == width && other.height == height &&
        other.focalPx == focalPx && other.mountHeightCm == mountHeightCm && other.pitchDeg == pitchDeg &&
        other.fisheye == fisheye && other.dist.contentEquals(dist)

    override fun hashCode() = listOf(width, height, focalPx, mountHeightCm, pitchDeg, fisheye, dist.toList()).hashCode()

    companion object {
        /** 일반적인 후방카메라와 비슷한 광각(어안) 설정. Python 프로토타입의 CameraModel.wide_angle()과 같다. */
        fun wideAngle(width: Int = 1280, height: Int = 720) = CameraModel(
            width, height, focalPx = 430.0 * width / 1280.0, fisheye = true,
            dist = doubleArrayOf(0.02, -0.01, 0.0, 0.0),
        )
    }
}

/** 조감도(Bird's-Eye View) 영역. 위쪽이 차량에서 먼 쪽. */
data class BevConfig(
    val xMinCm: Double = -320.0,
    val xMaxCm: Double = 320.0,
    val yMinCm: Double = 40.0,
    val yMaxCm: Double = 750.0,
    val pxPerCm: Double = 2.0,
) {
    val widthPx get() = ((xMaxCm - xMinCm) * pxPerCm).roundToInt()
    val heightPx get() = ((yMaxCm - yMinCm) * pxPerCm).roundToInt()
    fun colToX(col: Double) = xMinCm + col / pxPerCm
    fun rowToY(row: Double) = yMaxCm - row / pxPerCm
}
