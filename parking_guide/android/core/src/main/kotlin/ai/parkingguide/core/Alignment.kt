package ai.parkingguide.core

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sqrt

data class AlignmentThresholds(
    val goodOffsetCm: Double = 8.0,
    val goodYawDeg: Double = 1.5,
    val warnClearanceCm: Double = 15.0,
)

enum class AlignStatus { GOOD, ADJUST, DANGER }

data class Alignment(
    val offsetCm: Double, // + = 차가 목표보다 오른쪽 (목표선 기준)
    val yawDeg: Double, // + = 주차칸이 차 기준 시계방향으로 틀어짐
    val clearLeftRearCm: Double,
    val clearRightRearCm: Double,
    val clearLeftFrontCm: Double,
    val clearRightFrontCm: Double,
    val status: AlignStatus,
    val endDistCm: Double? = null,
    val targetBiasCm: Double = 0.0,
) {
    val clearLeftCm get() = minOf(clearLeftRearCm, clearLeftFrontCm)
    val clearRightCm get() = minOf(clearRightRearCm, clearRightFrontCm)
}

fun classify(offset: Double, yaw: Double, clears: List<Double>, thr: AlignmentThresholds): AlignStatus = when {
    clears.min() < 0 -> AlignStatus.DANGER
    abs(offset) < thr.goodOffsetCm && abs(yaw) < thr.goodYawDeg -> AlignStatus.GOOD
    else -> AlignStatus.ADJUST
}

fun computeAlignment(slot: SlotEstimate, v: VehicleSpec, thr: AlignmentThresholds = AlignmentThresholds()): Alignment {
    val halfW = v.widthCm / 2
    val cosT = cos(atan(slot.slope))
    fun clearances(y: Double): Pair<Double, Double> {
        val leftInner = slot.left.xAt(y) + slot.left.widthCm / 2
        val rightInner = slot.right.xAt(y) - slot.right.widthCm / 2
        return (-halfW - leftInner) * cosT to (rightInner - halfW) * cosT
    }
    val (lr, rr) = clearances(0.0)
    val (lf, rf) = clearances(-v.lengthCm)
    val offset = -slot.targetXAt(0.0) * cosT
    val yaw = slot.yawDeg
    return Alignment(offset, yaw, lr, rr, lf, rf, classify(offset, yaw, listOf(lr, rr, lf, rf), thr),
        slot.endDistCm, slot.targetBiasCm)
}

/** 하차 모드: 문콕 방지를 위해 목표선을 옮긴다. */
enum class DoorMode { CENTER, DRIVER, PASSENGER }

/**
 * 국내 차량은 운전석이 왼쪽이므로 운전석 여유 = 차를 오른쪽(+)으로, 동승석 여유 = 왼쪽(-)으로.
 * 반대쪽에도 [keepCm] 이상 남긴다.
 */
fun doorBiasCm(mode: DoorMode, slot: SlotEstimate, v: VehicleSpec, maxBiasCm: Double = 15.0, keepCm: Double = 8.0): Double {
    if (mode == DoorMode.CENTER) return 0.0
    val margin = slot.halfInnerWidthCm - v.widthCm / 2
    val b = (margin - keepCm).coerceIn(0.0, maxBiasCm)
    return if (mode == DoorMode.DRIVER) b else -b
}

/**
 * 인식 신뢰도 0~1 (평행하게 맞추기 전의 좌우 선으로 계산).
 * 보이는 길이, 페인트가 남은 비율, 좌우 폭 일치, 평행도, 주차칸 폭이 흔한 범위(2.2~3.0m)인지.
 */
fun slotConfidence(slot: SlotEstimate): Double {
    val l = slot.left
    val r = slot.right
    val length = (minOf(l.lengthCm, r.lengthCm) / 150.0).coerceIn(0.0, 1.0)
    val cover = ((minOf(l.coverage, r.coverage) - 0.3) / 0.5).coerceIn(0.0, 1.0)
    val widthMatch = 1.0 - 0.5 * (abs(l.widthCm - r.widthCm) / 10.0).coerceIn(0.0, 1.0)
    val parallel = 1.0 - (abs(l.angleDeg - r.angleDeg) / 6.0).coerceIn(0.0, 1.0)
    val slotW = if (slot.widthCm in 220.0..300.0) 1.0 else 0.6
    return (sqrt(length) * sqrt(cover) * widthMatch * (0.5 + 0.5 * parallel) * slotW).coerceIn(0.0, 1.0)
}
