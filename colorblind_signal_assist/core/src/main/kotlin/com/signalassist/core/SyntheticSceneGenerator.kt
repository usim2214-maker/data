package com.signalassist.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

enum class Lighting { DAY, DUSK, NIGHT }

/**
 * 합성할 장면 설명. 실제 카메라가 없을 때 테스트/에뮬레이터 시연용.
 * 신호등은 국내 표준 가로형 4구(적색 · 황색 · 녹색 좌회전 화살표 · 녹색)를 그린다.
 */
data class SceneSpec(
    val width: Int = 960,
    val height: Int = 540,
    val signal: SignalState = SignalState(SignalColor.RED),
    /** 신호등 함체 중심 (픽셀) */
    val lightCenterX: Float = 520f,
    val lightCenterY: Float = 120f,
    /** 등화 하나의 반지름 (픽셀) */
    val lampRadius: Float = 8f,
    val lighting: Lighting = Lighting.DAY,
    /** 노출 배율 (역광/어두운 노출 흉내) */
    val exposure: Float = 1f,
    val noiseSigma: Float = 6f,
    /** 앞차 브레이크등, 가로등, 가로수 같은 방해 요소 */
    val distractors: Boolean = true,
    val leadCarBrakeLights: Boolean = true,
    val seed: Int = 0,
)

/** [SceneSpec] 을 ARGB 프레임으로 렌더링하는 단순 래스터라이저 */
object SyntheticSceneGenerator {

    private val RED_LIT = intArrayOf(255, 40, 30)
    private val YELLOW_LIT = intArrayOf(255, 185, 0)
    private val GREEN_LIT = intArrayOf(0, 230, 170)

    fun render(spec: SceneSpec): Frame {
        val w = spec.width
        val h = spec.height
        val px = IntArray(w * h)
        val horizon = (h * 0.55f).toInt()
        val night = spec.lighting == Lighting.NIGHT
        val dusk = spec.lighting == Lighting.DUSK

        // 하늘 / 도로
        for (y in 0 until h) {
            val c = if (y < horizon) {
                val t = y.toFloat() / horizon
                when {
                    night -> mix(intArrayOf(8, 10, 22), intArrayOf(28, 26, 40), t)
                    dusk -> mix(intArrayOf(70, 80, 120), intArrayOf(200, 140, 110), t)
                    else -> mix(intArrayOf(120, 165, 220), intArrayOf(205, 218, 235), t)
                }
            } else {
                val t = (y - horizon).toFloat() / (h - horizon)
                if (night) mix(intArrayOf(25, 25, 28), intArrayOf(40, 40, 42), t)
                else mix(intArrayOf(95, 95, 100), intArrayOf(70, 70, 74), t)
            }
            val packed = argb(c[0], c[1], c[2])
            for (x in 0 until w) px[y * w + x] = packed
        }
        // 차선
        val laneColor = if (night) intArrayOf(150, 150, 140) else intArrayOf(225, 225, 220)
        for (y in horizon until h step 1) {
            val t = (y - horizon).toFloat() / (h - horizon)
            if (((y / 18) % 2) == 0) {
                val half = max(1f, 4f * t)
                fillRect(px, w, h, w / 2f - 0.05f * w * t - half, y.toFloat(), w / 2f - 0.05f * w * t + half, y + 1f, laneColor)
                fillRect(px, w, h, w / 2f + 0.35f * w * t - half, y.toFloat(), w / 2f + 0.35f * w * t + half, y + 1f, laneColor)
            }
        }

        val rnd = Random(spec.seed)
        if (spec.distractors) drawDistractors(px, w, h, horizon, spec, rnd)

        drawTrafficLight(px, w, h, spec)

        // 노출 + 센서 노이즈
        val sigma = spec.noiseSigma
        for (i in px.indices) {
            val c = px[i]
            val r = (c.red * spec.exposure + gauss(rnd) * sigma).toInt()
            val g = (c.green * spec.exposure + gauss(rnd) * sigma).toInt()
            val b = (c.blue * spec.exposure + gauss(rnd) * sigma).toInt()
            px[i] = argb(r, g, b)
        }
        return Frame(w, h, px)
    }

    private fun drawDistractors(px: IntArray, w: Int, h: Int, horizon: Int, spec: SceneSpec, rnd: Random) {
        val night = spec.lighting == Lighting.NIGHT
        // 건물 실루엣
        var x = 0f
        while (x < w) {
            val bw = 60f + rnd.nextFloat() * 120f
            val bh = 40f + rnd.nextFloat() * 120f
            val shade = if (night) 15 + rnd.nextInt(10) else 110 + rnd.nextInt(50)
            fillRect(px, w, h, x, horizon - bh, x + bw, horizon.toFloat(), intArrayOf(shade, shade, shade + 5))
            if (night) { // 창문 불빛 (따뜻한 흰색, 채도 낮음)
                var wy = horizon - bh + 6
                while (wy < horizon - 6) {
                    var wx = x + 5
                    while (wx < x + bw - 8) {
                        if (rnd.nextFloat() < 0.3f) fillRect(px, w, h, wx, wy, wx + 4, wy + 5, intArrayOf(200, 190, 160))
                        wx += 10
                    }
                    wy += 12
                }
            }
            x += bw + 5f
        }
        // 가로수 (녹색이지만 어둡고 채도 낮아 등화로 오인되면 안 됨)
        val treeColor = if (night) intArrayOf(10, 25, 12) else intArrayOf(45, 105, 55)
        fillCircle(px, w, h, w * 0.08f, horizon - 50f, 55f, treeColor)
        fillCircle(px, w, h, w * 0.93f, horizon - 40f, 45f, treeColor)
        // LED 가로등 (흰색)
        val lampY = h * 0.12f
        fillRect(px, w, h, w * 0.85f, lampY, w * 0.85f + 4, horizon.toFloat(), intArrayOf(60, 60, 65))
        fillCircle(px, w, h, w * 0.85f - 12, lampY, 7f, intArrayOf(245, 242, 230))
        // 앞차 + 브레이크등 (지평선 아래 -> 무시되어야 함)
        val carY = horizon + 30f
        fillRect(px, w, h, w * 0.40f, carY, w * 0.58f, carY + 70f, intArrayOf(30, 32, 36))
        if (spec.leadCarBrakeLights) {
            fillCircle(px, w, h, w * 0.42f, carY + 20f, 7f, intArrayOf(255, 30, 25))
            fillCircle(px, w, h, w * 0.56f, carY + 20f, 7f, intArrayOf(255, 30, 25))
        }
    }

    private fun drawTrafficLight(px: IntArray, w: Int, h: Int, spec: SceneSpec) {
        val r = spec.lampRadius
        val cx = spec.lightCenterX
        val cy = spec.lightCenterY
        val pitch = 2.5f * r
        val halfW = 2f * pitch
        val halfH = 1.3f * r
        // 지지대 + 함체
        fillRect(px, w, h, cx + halfW, cy - 0.3f * r, cx + halfW + 6f * r, cy + 0.3f * r, intArrayOf(70, 70, 72))
        fillRect(px, w, h, cx - halfW, cy - halfH, cx + halfW, cy + halfH, intArrayOf(22, 22, 24))

        val night = spec.lighting == Lighting.NIGHT
        val s = spec.signal
        val lamps = listOf(
            Triple(RED_LIT, s.color == SignalColor.RED, false),
            Triple(YELLOW_LIT, s.color == SignalColor.YELLOW, false),
            Triple(GREEN_LIT, s.leftTurnArrow, true),
            Triple(GREEN_LIT, s.color == SignalColor.GREEN, false),
        )
        for ((i, lamp) in lamps.withIndex()) {
            val (rgb, lit, arrow) = lamp
            val lx = cx + (i - 1.5f) * pitch
            if (!lit) {
                fillCircle(px, w, h, lx, cy, r, intArrayOf(rgb[0] / 7 + 18, rgb[1] / 7 + 18, rgb[2] / 7 + 18))
                continue
            }
            if (arrow) {
                fillCircle(px, w, h, lx, cy, r, intArrayOf(18, 30, 26))
                drawLeftArrow(px, w, h, lx, cy, r, rgb)
            } else {
                // 빛 번짐 + 중심부 하이라이트
                val glow = if (night) 1.9f else 1.35f
                glowCircle(px, w, h, lx, cy, r * glow, r, rgb, if (night) 0.55f else 0.35f)
                fillCircle(px, w, h, lx, cy, r, rgb)
                val core = mix(rgb, intArrayOf(255, 255, 255), if (night) 0.55f else 0.3f)
                fillCircle(px, w, h, lx, cy, r * 0.45f, core)
            }
        }
    }

    private fun drawLeftArrow(px: IntArray, w: Int, h: Int, cx: Float, cy: Float, r: Float, rgb: IntArray) {
        // 몸통
        fillRect(px, w, h, cx - 0.2f * r, cy - 0.18f * r, cx + 0.75f * r, cy + 0.18f * r, rgb)
        // 머리 (왼쪽을 향한 삼각형)
        val x0 = (cx - 0.8f * r).toInt()
        val x1 = (cx - 0.1f * r).toInt()
        for (x in x0..x1) {
            val t = (x - x0).toFloat() / max(1, x1 - x0)
            val half = 0.6f * r * t
            fillRect(px, w, h, x.toFloat(), cy - half, x + 1f, cy + half, rgb)
        }
    }

    // ---- 래스터 유틸 ----

    private fun fillRect(px: IntArray, w: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float, rgb: IntArray) {
        val c = argb(rgb[0], rgb[1], rgb[2])
        val xs = max(0, x0.toInt()); val xe = min(w, x1.toInt().coerceAtLeast(x0.toInt() + 1))
        val ys = max(0, y0.toInt()); val ye = min(h, y1.toInt().coerceAtLeast(y0.toInt() + 1))
        for (y in ys until ye) for (x in xs until xe) px[y * w + x] = c
    }

    private fun fillCircle(px: IntArray, w: Int, h: Int, cx: Float, cy: Float, r: Float, rgb: IntArray) {
        val c = argb(rgb[0], rgb[1], rgb[2])
        val r2 = r * r
        val xs = max(0, (cx - r).toInt()); val xe = min(w - 1, (cx + r).toInt() + 1)
        val ys = max(0, (cy - r).toInt()); val ye = min(h - 1, (cy + r).toInt() + 1)
        for (y in ys..ye) for (x in xs..xe) {
            val dx = x + 0.5f - cx; val dy = y + 0.5f - cy
            if (dx * dx + dy * dy <= r2) px[y * w + x] = c
        }
    }

    private fun glowCircle(px: IntArray, w: Int, h: Int, cx: Float, cy: Float, outer: Float, inner: Float, rgb: IntArray, strength: Float) {
        val xs = max(0, (cx - outer).toInt()); val xe = min(w - 1, (cx + outer).toInt() + 1)
        val ys = max(0, (cy - outer).toInt()); val ye = min(h - 1, (cy + outer).toInt() + 1)
        for (y in ys..ye) for (x in xs..xe) {
            val d = sqrt((x + 0.5f - cx).let { it * it } + (y + 0.5f - cy).let { it * it })
            if (d > outer || d < inner) continue
            val a = strength * (1f - (d - inner) / (outer - inner))
            val base = px[y * w + x]
            px[y * w + x] = argb(
                (base.red + (rgb[0] - base.red) * a).toInt(),
                (base.green + (rgb[1] - base.green) * a).toInt(),
                (base.blue + (rgb[2] - base.blue) * a).toInt(),
            )
        }
    }

    private fun mix(a: IntArray, b: IntArray, t: Float) = intArrayOf(
        (a[0] + (b[0] - a[0]) * t).toInt(),
        (a[1] + (b[1] - a[1]) * t).toInt(),
        (a[2] + (b[2] - a[2]) * t).toInt(),
    )

    /** 근사 정규분포 (균등분포 3개 합) */
    private fun gauss(rnd: Random): Float =
        (rnd.nextFloat() + rnd.nextFloat() + rnd.nextFloat() - 1.5f) * 2f
}
