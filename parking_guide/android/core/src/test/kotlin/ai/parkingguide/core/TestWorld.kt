package ai.parkingguide.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Python synth.py 의 정답 주차칸 / 자세 변환을 옮긴 시험 도구. */
data class CarPose(val lateralOffsetCm: Double, val yawDeg: Double, val entryDistCm: Double)

const val SLOT_W = 250.0
const val SLOT_L = 500.0
const val LINE_W = 15.0

fun groundTruthSlot(p: CarPose): SlotEstimate {
    val psi = rad(p.yawDeg)
    val c = cos(psi)
    val s = sin(psi)
    val b = tan(psi)
    fun line(sx: Double) = LineEstimate(-p.lateralOffsetCm + sx / c, b, LINE_W, 0.0, (p.entryDistCm + SLOT_L) * c, 1000)
    val along = p.entryDistCm + SLOT_L - LINE_W / 2
    val end = EndLine((along - s * p.lateralOffsetCm) / c, -b, LINE_W, -400.0, 400.0)
    return SlotEstimate(line(-SLOT_W / 2), line(SLOT_W / 2), end)
}

/** 주차칸 좌표계에서 뒷범퍼 위치와 후진 방향 각도 → 자세. */
fun poseFromBumper(bx: Double, by: Double, headingDeg: Double): CarPose {
    val psi = -rad(headingDeg)
    return CarPose(bx / cos(psi), deg(psi), bx * tan(psi) - by)
}

/** 자전거 모델 후진 (1cm 단위 적분). 반환: (bx, by, heading) */
fun stepCar(bx: Double, by: Double, hDeg: Double, steer: Double, ds: Double, v: VehicleSpec): Triple<Double, Double, Double> {
    val ov = v.rearOverhangCm
    val k = wheelToCurvature(steer, v)
    var h = rad(hDeg)
    var ax = bx - ov * sin(h)
    var ay = by - ov * cos(h)
    val n = maxOf(1, ds.toInt())
    repeat(n) {
        ax += ds / n * sin(h); ay += ds / n * cos(h); h += ds / n * k
    }
    return Triple(ax + ov * sin(h), ay + ov * cos(h), deg(h))
}
