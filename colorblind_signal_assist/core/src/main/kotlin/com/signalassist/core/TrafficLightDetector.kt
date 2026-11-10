package com.signalassist.core

import kotlin.math.max
import kotlin.math.sqrt

data class DetectorConfig(
    /** 처리 해상도(가로). 입력이 이보다 크면 정수 배 간격으로 샘플링한다. */
    val workingWidth: Int = 960,
    /** 화면 위쪽 몇 %까지만 본다. 신호등은 카메라보다 높이 있으므로 지평선 아래는 버린다. */
    val roiBottomFraction: Float = 0.55f,
    /** 이 높이보다 아래에 있는 후보는 점수를 깎는다 (앞차 브레이크등 등 오검출 억제) */
    val positionPriorStart: Float = 0.35f,
    val minValue: Float = 0.55f,
    val minSaturation: Float = 0.40f,
    /** 가운데가 하얗게 포화된 등화 중심부 */
    val coreMinValue: Float = 0.90f,
    val minAreaPx: Int = 5,
    val maxAreaFraction: Float = 0.02f,
    val minAspect: Float = 0.5f,
    val maxAspect: Float = 2.0f,
    val minFill: Float = 0.30f,
    /** 채움 비율이 이보다 낮으면 원이 아니라 화살표로 본다 (원의 이론값 ≈ 0.785) */
    val arrowFillThreshold: Float = 0.62f,
    val minScore: Float = 0.12f,
)

/**
 * 색 기반 신호등 등화 검출기 (카메라 프레임 -> 켜진 등화 목록).
 *
 * 1. 관심영역(화면 상단)의 각 픽셀을 HSV 로 바꿔 '밝고 채도가 높은' 적/황/녹 픽셀을 표시
 * 2. 같은 색(+하얗게 포화된 중심부) 픽셀끼리 연결 요소로 묶음
 * 3. 크기·종횡비·채움률·주변 어두움(신호등 함체)·화면 위치로 점수화
 *
 * 머신러닝 모델로 교체하더라도 [detect] 의 입출력만 지키면 나머지 파이프라인은 그대로 쓸 수 있다.
 */
class TrafficLightDetector(private val config: DetectorConfig = DetectorConfig()) {

    private companion object {
        const val NONE: Byte = 0
        const val RED: Byte = 1
        const val YELLOW: Byte = 2
        const val GREEN: Byte = 3
        const val CORE: Byte = 4
    }

    fun detect(frame: Frame): FrameDetection {
        val step = max(1, frame.width / config.workingWidth)
        val w = frame.width / step
        val h = ((frame.height * config.roiBottomFraction).toInt() / step).coerceAtLeast(1)
        val n = w * h
        val labels = ByteArray(n)
        val values = FloatArray(n)
        val hsv = FloatArray(3)

        for (y in 0 until h) {
            val row = (y * step) * frame.width
            for (x in 0 until w) {
                val px = frame.argb[row + x * step]
                rgbToHsv(px.red, px.green, px.blue, hsv)
                val i = y * w + x
                values[i] = hsv[2]
                labels[i] = classifyPixel(hsv[0], hsv[1], hsv[2])
            }
        }

        val visited = BooleanArray(n)
        val queue = IntArray(n)
        val lamps = ArrayList<LampDetection>()
        val maxArea = (n * config.maxAreaFraction).toInt()

        for (seed in 0 until n) {
            val seedLabel = labels[seed]
            if (visited[seed] || seedLabel == NONE || seedLabel == CORE) continue

            // BFS: 같은 색 + 포화 중심부를 하나의 덩어리로
            var head = 0
            var tail = 0
            queue[tail++] = seed
            visited[seed] = true
            var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
            var area = 0
            val colorCount = IntArray(4)
            while (head < tail) {
                val p = queue[head++]
                val px = p % w
                val py = p / w
                area++
                colorCount[labels[p].toInt().coerceAtMost(3)]++
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                if (area > maxArea) break
                fun visit(q: Int) {
                    if (!visited[q]) {
                        val l = labels[q]
                        if (l == seedLabel || l == CORE) {
                            visited[q] = true
                            queue[tail++] = q
                        }
                    }
                }
                if (px > 0) visit(p - 1)
                if (px < w - 1) visit(p + 1)
                if (py > 0) visit(p - w)
                if (py < h - 1) visit(p + w)
            }
            if (area > maxArea || area < config.minAreaPx) continue

            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val aspect = bw.toFloat() / bh
            if (aspect < config.minAspect || aspect > config.maxAspect) continue
            val fill = area.toFloat() / (bw * bh)
            if (fill < config.minFill) continue

            val colored = colorCount[1] + colorCount[2] + colorCount[3]
            if (colored == 0) continue
            val purity = colorCount[seedLabel.toInt()].toFloat() / colored
            // 중심부만 하얗고 색이 거의 없는 덩어리(가로등, 흰 차 등)는 버림
            if (colored < area * 0.25f) continue

            val darkness = 1f - surroundingValue(values, w, h, minX, maxX, minY, maxY)
            val cyFull = ((minY + maxY) / 2f + 0.5f) * step / frame.height
            val positionPrior = positionPrior(cyFull)
            val sizeScore = (area / 30f).coerceAtMost(1f)
            val fillScore = (fill / 0.785f).coerceAtMost(1f)
            val score = purity * darkness * positionPrior * (0.5f + 0.5f * sizeScore) * (0.6f + 0.4f * fillScore)
            if (score < config.minScore) continue

            val color = when (seedLabel) {
                RED -> SignalColor.RED
                YELLOW -> SignalColor.YELLOW
                else -> SignalColor.GREEN
            }
            val shape = if (color == SignalColor.GREEN && fill < config.arrowFillThreshold && area >= 12) {
                LampShape.ARROW
            } else {
                LampShape.CIRCLE
            }
            lamps += LampDetection(
                color = color,
                shape = shape,
                centerX = ((minX + maxX) / 2f + 0.5f) * step,
                centerY = ((minY + maxY) / 2f + 0.5f) * step,
                radius = sqrt(area / Math.PI.toFloat()) * step,
                score = score.coerceIn(0f, 1f),
            )
        }

        lamps.sortByDescending { it.score }
        val primary = lamps.firstOrNull { it.shape == LampShape.CIRCLE }
        return FrameDetection(
            color = primary?.color ?: SignalColor.UNKNOWN,
            leftTurnArrow = lamps.any { it.shape == LampShape.ARROW },
            lamps = lamps,
        )
    }

    private fun classifyPixel(hue: Float, sat: Float, value: Float): Byte {
        if (value < config.minValue) return NONE
        if (sat < config.minSaturation) {
            return if (value >= config.coreMinValue && sat < 0.40f) CORE else NONE
        }
        return when {
            hue < 12f || hue >= 330f -> RED
            hue in 18f..62f -> YELLOW
            hue in 90f..190f -> GREEN // 국내 녹색등은 청록 계열
            else -> NONE
        }
    }

    /** 덩어리 바깥 테두리 영역의 평균 밝기. 신호등은 검은 함체 안에 있으므로 낮을수록 좋다. */
    private fun surroundingValue(v: FloatArray, w: Int, h: Int, minX: Int, maxX: Int, minY: Int, maxY: Int): Float {
        val padX = max(2, (maxX - minX + 1) / 2)
        val padY = max(2, (maxY - minY + 1) / 2)
        val x0 = (minX - padX).coerceAtLeast(0)
        val x1 = (maxX + padX).coerceAtMost(w - 1)
        val y0 = (minY - padY).coerceAtLeast(0)
        val y1 = (maxY + padY).coerceAtMost(h - 1)
        var sum = 0f
        var count = 0
        for (y in y0..y1) {
            for (x in x0..x1) {
                val inside = x in (minX - 1)..(maxX + 1) && y in (minY - 1)..(maxY + 1)
                if (inside) continue
                sum += v[y * w + x]
                count++
            }
        }
        return if (count == 0) 0.5f else sum / count
    }

    private fun positionPrior(cy: Float): Float {
        val start = config.positionPriorStart
        val end = config.roiBottomFraction
        if (cy <= start) return 1f
        return (1f - 0.7f * (cy - start) / (end - start)).coerceIn(0.3f, 1f)
    }
}
