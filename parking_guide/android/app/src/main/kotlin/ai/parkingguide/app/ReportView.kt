package ai.parkingguide.app

import ai.parkingguide.core.ParkingReport
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/** 주차가 끝나면 보여 주는 리포트 카드 (Python overlay.render_report 와 같은 구성). 화면을 누르면 닫힌다. */
class ReportView(context: Context) : View(context) {
    var report: ParkingReport? = null
        set(value) { field = value; invalidate() }

    private val green = Color.rgb(72, 200, 96)
    private val amber = Color.rgb(255, 176, 20)
    private val red = Color.rgb(232, 64, 64)
    private val soft = Color.rgb(200, 200, 200)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val r = report ?: return
        val k = width / 1280f
        c.drawColor(Color.argb(235, 24, 24, 26))
        val cw = 1040 * k
        val ch = 560 * k
        val x0 = (width - cw) / 2
        val y0 = (height - ch) / 2
        p.style = Paint.Style.FILL; p.color = Color.rgb(40, 40, 44)
        c.drawRoundRect(RectF(x0, y0, x0 + cw, y0 + ch), 28 * k, 28 * k, p)

        // 점수 링
        val cx = x0 + 200 * k
        val cy = y0 + 230 * k
        val rr = 120 * k
        val col = if (r.score >= 75) green else if (r.score >= 55) amber else red
        p.style = Paint.Style.STROKE; p.strokeWidth = 18 * k; p.strokeCap = Paint.Cap.ROUND
        p.color = Color.rgb(70, 70, 74); c.drawCircle(cx, cy, rr, p)
        p.color = col; c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), -90f, 360f * r.score / 100, false, p)
        p.style = Paint.Style.FILL
        text(c, "${r.score}", cx, cy + 30 * k, 84 * k, Color.WHITE, center = true, bold = true)
        text(c, r.grade, cx, cy + rr + 56 * k, 34 * k, col, center = true)
        if (r.trend.isNotEmpty()) text(c, r.trend, cx, cy + rr + 96 * k, 18 * k, soft, center = true)

        // 항목
        val mx = x0 + 420 * k
        val right = x0 + cw - 48 * k
        text(c, "이번 주차", mx, y0 + 62 * k, 26 * k, Color.WHITE)
        val side = if (r.offsetCm > 0) "오른쪽" else "왼쪽"
        val ref = if (r.targetBiasCm != 0.0) "목표 " else "가운데 "
        val rows = listOf(
            Triple("${ref}치우침", if (abs(r.offsetCm) < 3) "딱 맞음" else "$side ${abs(r.offsetCm).roundToInt()}cm", abs(r.offsetCm) < 8),
            Triple("각도", "%.1f°".format(java.util.Locale.ROOT, abs(r.yawDeg)), abs(r.yawDeg) < 1.5),
            Triple("운전석 쪽 / 동승석 쪽", "${r.clearDriverCm.roundToInt()}cm / ${r.clearPassengerCm.roundToInt()}cm", minOf(r.clearDriverCm, r.clearPassengerCm) >= 5),
            Triple("걸린 시간 · 핸들 수정", "${r.durationS.roundToInt()}초 · ${r.corrections}번", r.corrections <= 6),
        )
        var ry = y0 + 112 * k
        for ((label, value, ok) in rows) {
            text(c, label, mx, ry, 19 * k, soft)
            p.textSize = 24 * k
            text(c, value, right - p.measureText(value), ry, 24 * k, if (ok) green else amber)
            p.color = Color.rgb(64, 64, 68); p.strokeWidth = 1f
            c.drawLine(mx, ry + 20 * k, right, ry + 20 * k, p)
            ry += 56 * k
        }
        // TIP
        val tip = r.tips.firstOrNull() ?: ""
        p.color = Color.rgb(54, 74, 60)
        c.drawRoundRect(RectF(mx, ry, right, ry + 88 * k), 16 * k, 16 * k, p)
        text(c, "TIP", mx + 18 * k, ry + 26 * k, 16 * k, green)
        val lines = wrap(tip, 19 * k, right - mx - 36 * k)
        lines.take(2).forEachIndexed { i, line -> text(c, line, mx + 18 * k, ry + 52 * k + i * 25 * k, 19 * k, Color.WHITE) }

        // 주차선 상태 (지자체 공유)
        r.line?.let { ln ->
            val lc = when (ln.label) { "양호" -> green; "약간 닳음" -> amber; else -> red }
            val chip = "이 주차칸의 선 상태: ${ln.label} (페인트 ${(ln.worstCoverage * 100).roundToInt()}%)"
            p.textSize = 17 * k
            val w = p.measureText(chip)
            val by = y0 + ch - 56 * k
            p.color = lc
            c.drawRoundRect(RectF(x0 + 40 * k, by, x0 + 76 * k + w, by + 34 * k), 16 * k, 16 * k, p)
            text(c, chip, x0 + 58 * k, by + 23 * k, 17 * k, Color.rgb(28, 28, 30), bold = true)
            text(c, "동의하면 익명으로 지자체 주차선 관리에 보탤 수 있어요", x0 + 96 * k + w, by + 23 * k, 15 * k, soft)
        }
    }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, center: Boolean = false, bold: Boolean = false) {
        p.style = Paint.Style.FILL; p.textSize = size; p.color = color; p.isFakeBoldText = bold
        c.drawText(s, if (center) x - p.measureText(s) / 2 else x, y, p)
        p.isFakeBoldText = false
    }

    private fun wrap(s: String, size: Float, maxW: Float): List<String> {
        p.textSize = size
        val out = mutableListOf<String>()
        var line = ""
        for (w in s.split(" ")) {
            val cand = if (line.isEmpty()) w else "$line $w"
            if (p.measureText(cand) > maxW && line.isNotEmpty()) { out += line; line = w } else line = cand
        }
        if (line.isNotEmpty()) out += line
        return out
    }
}
