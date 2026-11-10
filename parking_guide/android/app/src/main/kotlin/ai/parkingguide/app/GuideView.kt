package ai.parkingguide.app

import ai.parkingguide.core.AlignStatus
import ai.parkingguide.core.Alignment
import ai.parkingguide.core.AlignmentThresholds
import ai.parkingguide.core.CameraModel
import ai.parkingguide.core.FINAL_ZONE_CM
import ai.parkingguide.core.FinalPrediction
import ai.parkingguide.core.GHOST_CONFIDENCE
import ai.parkingguide.core.Guidance
import ai.parkingguide.core.GuidanceLevel
import ai.parkingguide.core.LOW_CONFIDENCE
import ai.parkingguide.core.Point
import ai.parkingguide.core.SlotEstimate
import ai.parkingguide.core.VehicleSpec
import ai.parkingguide.core.predictFinal
import ai.parkingguide.core.predictPath
import ai.parkingguide.core.turnsPhraseKo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 후방카메라 화면 위 주차 가이드 (Python parking_guide/overlay.py 와 같은 디자인).
 * - 위: 안내 배지 (지금 할 행동 하나) / 왼쪽: 핸들 맞추기 다이얼 / 아래: 균형 게이지 / 오른쪽 위: 미니맵
 * - 영상 위: 가상 벽 수직선, 주차선 강조, 차폭 통로, 고스트카
 * 색만으로 구분하지 않도록 아이콘·문구를 항상 함께 쓴다.
 */
class GuideView(context: Context) : View(context) {
    var camera: CameraModel = CameraModel.wideAngle(640, 360)
    var vehicle: VehicleSpec = VehicleSpec.DEFAULT
    private val thr = AlignmentThresholds()

    private var frame: Bitmap? = null
    private var slot: SlotEstimate? = null
    private var align: Alignment? = null
    private var guidance: Guidance? = null
    private var steer: Double? = null

    fun update(frame: Bitmap?, slot: SlotEstimate?, align: Alignment?, guidance: Guidance?, steer: Double?) {
        this.frame = frame; this.slot = slot; this.align = align; this.guidance = guidance; this.steer = steer
        invalidate()
    }

    // ------------------------------------------------------------------ 색, 붓
    private val green = Color.rgb(72, 200, 96)
    private val amber = Color.rgb(255, 176, 20)
    private val red = Color.rgb(232, 64, 64)
    private val blue = Color.rgb(60, 170, 235)
    private val soft = Color.rgb(200, 200, 200)
    private val dark = Color.argb(160, 30, 28, 28)

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dash = DashPathEffect(floatArrayOf(18f, 14f), 0f)

    private fun levelColor(l: GuidanceLevel) = when (l) {
        GuidanceLevel.OK -> green
        GuidanceLevel.WARN -> amber
        GuidanceLevel.STOP -> red
        GuidanceLevel.INFO -> Color.GRAY
    }

    private fun statusColor(s: AlignStatus) = when (s) {
        AlignStatus.GOOD -> green
        AlignStatus.ADJUST -> amber
        AlignStatus.DANGER -> red
    }

    private fun clearColor(c: Double) = when {
        c < 0 -> red
        c < thr.warnClearanceCm -> amber
        else -> green
    }

    /** 접근 중엔 뒷범퍼 기준, 끝선 2.5m 안쪽부터는 앞부분까지 포함한 최소 여유. */
    private fun displayClear(a: Alignment): Pair<Double, Double> =
        if (a.endDistCm != null && a.endDistCm!! <= FINAL_ZONE_CM) a.clearLeftCm to a.clearRightCm
        else a.clearLeftRearCm to a.clearRightRearCm

    private fun displayStatus(a: Alignment): AlignStatus {
        val (dl, dr) = displayClear(a)
        if (minOf(dl, dr) < 0) return AlignStatus.DANGER
        val dev = (dl - dr) / 2 - a.targetBiasCm
        return if (abs(a.offsetCm) < thr.goodOffsetCm && abs(a.yawDeg) < thr.goodYawDeg && abs(dev) < thr.goodOffsetCm)
            AlignStatus.GOOD else AlignStatus.ADJUST
    }

    // ------------------------------------------------------------------ 투영
    private val k get() = width / 1280f // UI 배율
    private fun proj(x: Double, y: Double, z: Double = 0.0): PointF? {
        val uv = camera.project(x, y, z) ?: return null
        return PointF((uv.first * width / camera.width).toFloat(), (uv.second * height / camera.height).toFloat())
    }

    private val yNear get() = camera.visibleYMin() + 5.0

    private fun groundPath(pts: List<Point>): Path? {
        val path = Path()
        var started = false
        for (p in pts) {
            if (p.y < yNear) continue
            val q = proj(p.x, p.y) ?: continue
            if (!started) { path.moveTo(q.x, q.y); started = true } else path.lineTo(q.x, q.y)
        }
        return if (started) path else null
    }

    private fun groundLine(c: Canvas, x0: Double, y0: Double, x1: Double, y1: Double, color: Int, w: Float, dashed: Boolean = false, n: Int = 24) {
        val a = maxOf(y0, yNear)
        val b = maxOf(y1, yNear)
        val pts = (0 until n).map { i -> val t = i / (n - 1.0); Point(x0 + (x1 - x0) * t, a + (b - a) * t) }
        val path = groundPath(pts) ?: return
        stroke.color = color; stroke.strokeWidth = w; stroke.pathEffect = if (dashed) dash else null
        c.drawPath(path, stroke)
        stroke.pathEffect = null
    }

    private fun verticalBar(c: Canvas, x: Double, y: Double, z1: Double, color: Int, widthCm: Double, nx: Double, ny: Double) {
        val hw = widthCm / 2
        val pts = (0..11).map { i -> Triple(x - hw * nx, y - hw * ny, z1 * i / 11) } +
            (11 downTo 0).map { i -> Triple(x + hw * nx, y + hw * ny, z1 * i / 11) }
        val path = Path()
        var first = true
        for ((px, py, pz) in pts) {
            val q = proj(px, py, pz) ?: return
            if (first) { path.moveTo(q.x, q.y); first = false } else path.lineTo(q.x, q.y)
        }
        path.close()
        fill.color = color; fill.alpha = 230
        c.drawPath(path, fill)
        fill.alpha = 255
    }

    // ------------------------------------------------------------------ 그리기
    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        frame?.let { c.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), null) }
        val s = slot
        val a = align
        val g = guidance
        val weak = s != null && s.confidence < LOW_CONFIDENCE
        val dist = (a?.endDistCm ?: 350.0).coerceIn(60.0, 350.0)
        val path = predictPath(vehicle, steer ?: 0.0, dist)
        drawCorridor(c, path.left, path.right, path.center, g)
        if (s != null && a != null) drawSlot(c, s, a, weak)
        val final = if (s != null && a != null && steer != null && s.confidence >= GHOST_CONFIDENCE &&
            a.endDistCm != null && a.endDistCm!! <= FINAL_ZONE_CM) predictFinal(s, vehicle, steer!!, a.endDistCm) else null
        final?.let { drawGhost(c, it) }
        g?.let { drawBadge(c, it) }
        if (a != null) {
            drawBalance(c, a, final, weak)
            drawMinimap(c, s, a, path.left, path.right, weak)
        }
        g?.let { drawDial(c, it) }
        s?.let { drawConfidence(c, it.confidence) }
        text.textSize = 15 * k; text.color = soft
        c.drawText("${vehicle.name} · 폭 ${vehicle.widthCm.roundToInt()}cm   ·   주변을 직접 확인하세요", 16 * k, height - 16 * k, text)
    }

    private fun drawCorridor(c: Canvas, left: List<Point>, right: List<Point>, center: List<Point>, g: Guidance?) {
        val lp = left.filter { it.y >= yNear }.mapNotNull { proj(it.x, it.y) }
        val rp = right.filter { it.y >= yNear }.mapNotNull { proj(it.x, it.y) }
        if (lp.size > 1 && rp.size > 1) {
            val p = Path()
            p.moveTo(lp[0].x, lp[0].y); lp.drop(1).forEach { p.lineTo(it.x, it.y) }
            rp.reversed().forEach { p.lineTo(it.x, it.y) }; p.close()
            fill.color = g?.let { levelColor(it.level) } ?: blue; fill.alpha = 46
            c.drawPath(p, fill); fill.alpha = 255
        }
        for (pts in listOf(left, right)) groundPath(pts)?.let {
            stroke.color = Color.WHITE; stroke.strokeWidth = 3 * k; c.drawPath(it, stroke)
        }
        // 거리 눈금 0.5m / 1m / 2m
        var acc = 0.0
        val cum = center.indices.map { i -> if (i > 0) acc += hypot(center[i].x - center[i - 1].x, center[i].y - center[i - 1].y); acc }
        for ((d, col) in listOf(50.0 to red, 100.0 to amber, 200.0 to green)) {
            val i = cum.indexOfFirst { it >= d }
            if (i < 0) continue
            val l = left[i]
            val r = right[i]
            if (minOf(l.y, r.y) < yNear) continue
            groundLine(c, l.x, l.y, l.x + (r.x - l.x) * 0.18, l.y + (r.y - l.y) * 0.18, col, 5 * k, n = 4)
            groundLine(c, r.x, r.y, r.x + (l.x - r.x) * 0.18, r.y + (l.y - r.y) * 0.18, col, 5 * k, n = 4)
        }
    }

    private fun drawSlot(c: Canvas, s: SlotEstimate, a: Alignment, weak: Boolean) {
        val (dl, dr) = displayClear(a)
        val cl = if (weak) soft else clearColor(dl)
        val cr = if (weak) soft else clearColor(dr)
        val y0 = yNear
        val ends = listOf(s.left, s.right).map { ln -> val ye = s.endYFor(ln); Point(ln.xAt(ye), ye) }
        for ((ln, col) in listOf(s.left to cl, s.right to cr)) {
            val ye = s.endYFor(ln)
            groundLine(c, ln.xAt(y0), y0, ln.xAt(ye), ye, col, 7 * k, dashed = weak)
        }
        if (s.end != null) {
            val d = a.endDistCm ?: 999.0
            groundLine(c, ends[0].x, ends[0].y, ends[1].x, ends[1].y, if (d <= 30) red else if (d <= 100) amber else Color.WHITE, 7 * k)
        }
        val ye = s.farEndY
        groundLine(c, s.targetXAt(y0), y0, s.targetXAt(ye), ye, amber, 3 * k, dashed = true)
        // 가상 벽 수직선: 공주시 벽 페인트 선을 대신한다. 벽이 아주 가까우면 생략
        val h = minOf(110.0, 0.45 * ye)
        if (h < 30) return
        val norm = sqrt(1 + s.slope * s.slope)
        val nx = 1 / norm
        val ny = -s.slope / norm
        verticalBar(c, ends[0].x, ends[0].y, h, cl, 14.0, nx, ny)
        verticalBar(c, ends[1].x, ends[1].y, h, cr, 14.0, nx, ny)
        verticalBar(c, s.targetXAt(ye), 0.5 * (ends[0].y + ends[1].y), h * 0.7, amber, 6.0, nx, ny)
        val p = proj(ends[0].x, ends[0].y, h)
        val q = proj(ends[1].x, ends[1].y, h)
        if (p != null && q != null) { stroke.color = Color.WHITE; stroke.strokeWidth = 3 * k; c.drawLine(p.x, p.y, q.x, q.y, stroke) }
    }

    /** 고스트카: 지금 핸들 그대로 가면 멈출 자리 (차 뒷면 + 바닥 점선). */
    private fun drawGhost(c: Canvas, f: FinalPrediction) {
        val col = statusColor(f.status)
        val (rl, rr, fr, fl) = f.corners
        if (minOf(rl.y, rr.y) > yNear + 20) {
            val h = 95.0
            val face = listOf(Triple(rl.x, rl.y, 0.0), Triple(rr.x, rr.y, 0.0), Triple(rr.x, rr.y, h), Triple(rl.x, rl.y, h))
            val path = Path()
            var ok = true
            for (i in 0 until 4) for (t in 0..9) {
                val a0 = face[i]
                val a1 = face[(i + 1) % 4]
                val q = proj(a0.first + (a1.first - a0.first) * t / 9, a0.second + (a1.second - a0.second) * t / 9,
                    a0.third + (a1.third - a0.third) * t / 9) ?: run { ok = false; null } ?: continue
                if (i == 0 && t == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
            }
            if (ok) { path.close(); stroke.color = col; stroke.strokeWidth = 3 * k; c.drawPath(path, stroke) }
        }
        groundLine(c, rl.x, rl.y, fl.x, fl.y, col, 3 * k, dashed = true)
        groundLine(c, rr.x, rr.y, fr.x, fr.y, col, 3 * k, dashed = true)
    }

    private fun roundRect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float, color: Int) {
        fill.color = color
        c.drawRoundRect(RectF(l, t, r, b), rad, rad, fill)
    }

    private fun drawBadge(c: Canvas, g: Guidance) {
        val size = 34 * k
        text.textSize = size; text.isFakeBoldText = true
        val icon = 56 * k
        val pad = 22 * k
        val bw = icon + pad * 3 + text.measureText(g.text)
        val bh = 74 * k
        val x0 = (width - bw) / 2
        val y0 = 16 * k
        roundRect(c, x0 + 3, y0 + 5, x0 + bw + 3, y0 + bh + 5, bh / 2, Color.argb(90, 0, 0, 0))
        roundRect(c, x0, y0, x0 + bw, y0 + bh, bh / 2, levelColor(g.level))
        val cx = x0 + pad + icon / 2
        val cy = y0 + bh / 2
        stroke.color = Color.WHITE; stroke.strokeWidth = 5 * k
        when {
            g.level == GuidanceLevel.STOP -> drawStop(c, cx, cy, icon * 0.45f)
            g.level == GuidanceLevel.OK -> { val p = Path(); p.moveTo(cx - icon * 0.25f, cy); p.lineTo(cx - icon * 0.06f, cy + icon * 0.19f); p.lineTo(cx + icon * 0.27f, cy - icon * 0.19f); c.drawPath(p, stroke) }
            g.key == "retry" -> { c.drawArc(RectF(cx - icon * 0.34f, cy - icon * 0.34f, cx + icon * 0.34f, cy + icon * 0.34f), -60f, 290f, false, stroke) }
            g.arrow != 0 -> drawWheelIcon(c, cx, cy + 6 * k, icon * 0.28f, Color.WHITE, 0.0, g.arrow)
            else -> { fill.color = Color.WHITE; c.drawCircle(cx, cy, icon * 0.12f, fill) }
        }
        text.color = Color.WHITE
        c.drawText(g.text, x0 + pad * 2 + icon, cy + size * 0.36f, text)
        text.isFakeBoldText = false
        if (g.sub.isNotEmpty()) {
            text.textSize = 22 * k
            val sw = text.measureText(g.sub)
            val sx = (width - sw) / 2
            val sy = y0 + bh + 12 * k
            roundRect(c, sx - 16 * k, sy - 5 * k, sx + sw + 16 * k, sy + 32 * k, 18 * k, dark)
            c.drawText(g.sub, sx, sy + 20 * k, text)
        }
    }

    private fun drawStop(c: Canvas, cx: Float, cy: Float, r: Float) {
        val p = Path()
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0 + 22.5)
            val x = cx + r * cos(a).toFloat()
            val y = cy + r * sin(a).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        c.drawPath(p, stroke)
        c.drawLine(cx - r / 2, cy, cx + r / 2, cy, stroke)
    }

    /** 핸들 아이콘. rotDeg: 실제 회전각(바퀴 수가 보이도록 12시 점), arrow: 돌릴 방향 */
    private fun drawWheelIcon(c: Canvas, cx: Float, cy: Float, r: Float, col: Int, rotDeg: Double, arrow: Int = 0) {
        stroke.color = col; stroke.strokeWidth = maxOf(2f, r / 5)
        c.drawCircle(cx, cy, r, stroke)
        for (base in listOf(180.0, 0.0, 90.0)) {
            val a = Math.toRadians(base + rotDeg)
            c.drawLine(cx, cy, cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), stroke)
        }
        if (rotDeg != 0.0 || arrow == 0) {
            val a = Math.toRadians(-90 + rotDeg)
            fill.color = amber
            c.drawCircle(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), maxOf(3f, r / 5), fill)
        }
        if (arrow != 0) {
            val big = r * 1.55f
            c.drawArc(RectF(cx - big, cy - big, cx + big, cy + big), -150f, 120f, false, stroke)
            val tip = Math.toRadians(if (arrow > 0) -30.0 else -150.0)
            val tx = cx + big * cos(tip).toFloat()
            val ty = cy + big * sin(tip).toFloat()
            val dir = if (arrow > 0) 1f else -1f
            val tanX = (-sin(tip)).toFloat() * dir
            val tanY = cos(tip).toFloat() * dir
            val nx = cos(tip).toFloat()
            val ny = sin(tip).toFloat()
            val l = r * 0.7f
            val p = Path()
            p.moveTo(tx + tanX * l * 0.6f, ty + tanY * l * 0.6f)
            p.lineTo(tx + nx * l * 0.55f, ty + ny * l * 0.55f)
            p.lineTo(tx - nx * l * 0.55f, ty - ny * l * 0.55f)
            p.close()
            fill.color = col
            c.drawPath(p, fill)
        }
    }

    /** 핸들 맞추기 다이얼: 흰 바늘(지금 핸들)을 초록 구간(목표)에 넣으면 된다. */
    private fun drawDial(c: Canvas, g: Guidance) {
        val d = g.desiredSteerDeg ?: return
        val cur = g.currentSteerDeg ?: return
        if (g.level == GuidanceLevel.STOP || g.key == "retry" || g.key == "lowconf") return
        val pw = 200 * k
        val ph = 214 * k
        val x0 = 16 * k
        val y0 = height * 0.40f
        roundRect(c, x0, y0, x0 + pw, y0 + ph, 20 * k, dark)
        val cx = x0 + pw / 2
        val cy = y0 + 118 * k
        val r = 66 * k
        val full = vehicle.maxSteerWheelDeg
        fun ang(w: Double) = (-90.0 + w.coerceIn(-full, full) / full * 135.0).toFloat()
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        stroke.strokeWidth = 10 * k
        stroke.color = Color.rgb(90, 90, 90); c.drawArc(oval, -225f, 270f, false, stroke)
        stroke.color = green; stroke.strokeWidth = 12 * k
        c.drawArc(oval, ang(d - 30), ang(d + 30) - ang(d - 30), false, stroke)
        drawWheelIcon(c, cx, cy, r * 0.55f, soft, cur)
        val a = Math.toRadians(ang(cur).toDouble())
        stroke.color = Color.WHITE; stroke.strokeWidth = 5 * k
        c.drawLine(cx + (r - 16 * k) * cos(a).toFloat(), cy + (r - 16 * k) * sin(a).toFloat(),
            cx + (r + 10 * k) * cos(a).toFloat(), cy + (r + 10 * k) * sin(a).toFloat(), stroke)
        text.textSize = 17 * k; text.color = soft
        c.drawText("핸들 맞추기", x0 + 18 * k, y0 + 28 * k, text)
        val diff = d - cur
        val (msg, col) = if (abs(diff) < 30) "딱 맞아요" to green
        else "${if (diff > 0) "오른쪽" else "왼쪽"} ${turnsPhraseKo(diff)}" to amber
        text.textSize = 20 * k; text.color = col
        c.drawText(msg, cx - text.measureText(msg) / 2, y0 + ph - 18 * k, text)
    }

    /** 수평계 모양 균형 게이지. 왼쪽 = 운전석 쪽 (후방카메라는 거울처럼 보임). */
    private fun drawBalance(c: Canvas, a: Alignment, final: FinalPrediction?, weak: Boolean) {
        val pw = 660 * k
        val ph = 104 * k
        val x0 = (width - pw) / 2
        val y0 = height - ph - 56 * k
        roundRect(c, x0, y0, x0 + pw, y0 + ph, 24 * k, dark)
        val (dl, dr) = displayClear(a)
        val cl = if (weak) soft else clearColor(dl)
        val cr = if (weak) soft else clearColor(dr)
        val pad = 24 * k
        text.textSize = 17 * k; text.color = soft
        c.drawText("운전석 쪽", x0 + pad, y0 + 30 * k, text)
        val rl = "동승석 쪽"
        c.drawText(rl, x0 + pw - pad - text.measureText(rl), y0 + 30 * k, text)
        text.textSize = 34 * k
        text.color = cl; c.drawText("${dl.roundToInt()}cm", x0 + pad, y0 + 72 * k, text)
        val rv = "${dr.roundToInt()}cm"
        text.color = cr; c.drawText(rv, x0 + pw - pad - text.measureText(rv), y0 + 72 * k, text)

        val tx0 = x0 + 175 * k
        val tx1 = x0 + pw - 175 * k
        val ty = y0 + 28 * k
        val th = 24 * k
        val half = vehicle.widthCm / 2
        val inner = maxOf(vehicle.widthCm + maxOf(a.clearLeftRearCm, -60.0) + maxOf(a.clearRightRearCm, -60.0), vehicle.widthCm + 1)
        val scale = (tx1 - tx0) / (inner + 60.0)
        val mid = (tx0 + tx1) / 2
        fun X(cm: Double) = (mid + cm * scale).toFloat()
        roundRect(c, tx0, ty, tx1, ty + th, th / 2, Color.rgb(75, 75, 75))
        fill.color = Color.rgb(120, 120, 120); c.drawRect(X(-inner / 2), ty, X(inner / 2), ty + th, fill)
        for ((xs, col) in listOf(X(-inner / 2) to cl, X(inner / 2) to cr)) { fill.color = col; c.drawRect(xs - 3 * k, ty - 9 * k, xs + 3 * k, ty + th + 9 * k, fill) }
        stroke.color = Color.WHITE; stroke.strokeWidth = 2 * k; c.drawLine(mid, ty - 12 * k, mid, ty + th + 12 * k, stroke)
        if (a.targetBiasCm != 0.0) {
            val tx = X(a.targetBiasCm)
            val p = Path(); p.moveTo(tx, ty - 4 * k); p.lineTo(tx - 9 * k, ty - 18 * k); p.lineTo(tx + 9 * k, ty - 18 * k); p.close()
            fill.color = amber; c.drawPath(p, fill)
            val side = if (a.targetBiasCm > 0) "운전석" else "동승석"
            val chip = "문콕 방지 · $side 쪽 여유 +${abs(a.targetBiasCm).roundToInt()}cm"
            text.textSize = 16 * k
            val cw = text.measureText(chip)
            roundRect(c, mid - cw / 2 - 12 * k, y0 - 34 * k, mid + cw / 2 + 12 * k, y0 - 6 * k, 14 * k, amber)
            text.color = Color.rgb(28, 28, 30); c.drawText(chip, mid - cw / 2, y0 - 14 * k, text)
        }
        val status = displayStatus(a)
        val sc = if (weak) soft else statusColor(status)
        fun carBox(centerCm: Double, yaw: Double, col: Int, filled: Boolean, w: Float) {
            val cxp = (X(centerCm - half) + X(centerCm + half)) / 2
            val cw = X(centerCm + half) - X(centerCm - half)
            c.save(); c.rotate((yaw * 2).coerceIn(-10.0, 10.0).toFloat(), cxp, ty + th / 2)
            val rect = RectF(cxp - cw / 2, ty - 5 * k, cxp + cw / 2, ty + th + 5 * k)
            if (filled) { fill.color = Color.rgb(62, 62, 64); c.drawRect(rect, fill) }
            stroke.color = col; stroke.strokeWidth = w; c.drawRect(rect, stroke)
            c.restore()
        }
        final?.let { carBox((it.clearLeftCm - it.clearRightCm) / 2, it.yawDeg, statusColor(it.status), false, 2 * k) }
        carBox((a.clearLeftRearCm - a.clearRightRearCm) / 2, a.yawDeg, sc, true, 3 * k)

        val info = when {
            weak -> "인식이 불확실해요 · 참고만 하세요"
            final != null -> "이대로 가면  운전석 쪽 ${final.clearLeftCm.roundToInt()}cm · 동승석 쪽 ${final.clearRightCm.roundToInt()}cm"
            else -> buildList {
                if (abs(a.offsetCm) >= thr.goodOffsetCm) add("${if (a.offsetCm > 0) "오른쪽" else "왼쪽"}으로 ${abs(a.offsetCm).roundToInt()}cm 치우침")
                if (abs(a.yawDeg) >= thr.goodYawDeg) add("각도 ${"%.1f".format(java.util.Locale.ROOT, abs(a.yawDeg))}°")
            }.joinToString("  ·  ").ifEmpty { if (status == AlignStatus.GOOD) (if (a.targetBiasCm != 0.0) "목표 위치에 맞음" else "가운데 정렬됨") else "앞부분 여유를 확인하세요" }
        }
        text.textSize = 17 * k; text.color = soft
        c.drawText(info, mid - text.measureText(info) / 2, ty + th + 38 * k, text)
    }

    /** 위에서 내려다본 모습. 위쪽이 차 뒤쪽(벽 방향). */
    private fun drawMinimap(c: Canvas, s: SlotEstimate?, a: Alignment, left: List<Point>, right: List<Point>, weak: Boolean) {
        val l = vehicle.lengthCm
        val wv = vehicle.widthCm
        val sc = 0.36f * k
        val back = 330.0
        val pw = 190 * k
        val ph = ((l + back + 40) * 0.36 * k).toFloat()
        val x0 = width - pw - 16 * k
        val y0 = 16 * k
        roundRect(c, x0, y0, x0 + pw, y0 + ph, 18 * k, dark)
        val ox = x0 + pw / 2
        val oy = y0 + (back * sc).toFloat() + 10 * k
        fun P(x: Double, y: Double) = PointF(ox + (x * sc).toFloat(), oy - (y * sc).toFloat())
        c.save(); c.clipRect(x0 + 4, y0 + 4, x0 + pw - 4, y0 + ph - 4)
        stroke.strokeWidth = 3 * k
        if (s != null) {
            val (dl, dr) = displayClear(a)
            val yb = -l - 40
            for ((ln, col) in listOf(s.left to (if (weak) soft else clearColor(dl)), s.right to (if (weak) soft else clearColor(dr)))) {
                val ye = minOf(s.endYFor(ln), back)
                val p = P(ln.xAt(yb), yb); val q = P(ln.xAt(ye), ye)
                stroke.color = col; c.drawLine(p.x, p.y, q.x, q.y, stroke)
            }
            if (s.end != null && s.farEndY <= back) {
                val yl = s.endYFor(s.left); val yr = s.endYFor(s.right)
                val p = P(s.left.xAt(yl), yl); val q = P(s.right.xAt(yr), yr)
                stroke.color = Color.WHITE; c.drawLine(p.x, p.y, q.x, q.y, stroke)
            }
        }
        stroke.color = blue; stroke.strokeWidth = 2 * k
        for (pts in listOf(left, right)) {
            val pp = pts.filter { it.y <= back }.map { P(it.x, it.y) }
            for (i in 1 until pp.size) c.drawLine(pp[i - 1].x, pp[i - 1].y, pp[i].x, pp[i].y, stroke)
        }
        val tl = P(-wv / 2, 0.0); val br = P(wv / 2, -l)
        fill.color = Color.rgb(88, 88, 92); c.drawRect(tl.x, tl.y, br.x, br.y, fill)
        stroke.color = if (weak) soft else statusColor(displayStatus(a)); c.drawRect(tl.x, tl.y, br.x, br.y, stroke)
        val t1 = P(-wv / 2 + 18, -8.0); val t2 = P(wv / 2 - 18, -8.0)
        stroke.color = Color.rgb(225, 60, 60); stroke.strokeWidth = 4 * k; c.drawLine(t1.x, t1.y, t2.x, t2.y, stroke)
        c.restore()
    }

    /** 오른쪽 아래: 인식 신뢰도 막대 4칸. */
    private fun drawConfidence(c: Canvas, conf: Double) {
        val n = (kotlin.math.ceil(conf * 4 - 1e-6)).toInt().coerceIn(0, 4)
        val col = if (n >= 3) green else if (n == 2) amber else red
        val bx = width - 16 * k - 44 * k
        val by = height - 16 * k
        for (i in 0 until 4) {
            val h = (6 + i * 4) * k
            val x = bx + i * 11 * k
            fill.color = if (i < n) col else Color.rgb(90, 90, 90)
            c.drawRect(x, by - h, x + 7 * k, by, fill)
        }
        text.textSize = 15 * k; text.color = soft
        c.drawText("인식", bx - text.measureText("인식") - 8 * k, by - 2 * k, text)
    }
}
