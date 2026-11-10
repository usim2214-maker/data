package com.signalassist.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.signalassist.core.AlertLevel
import com.signalassist.core.SignalColor
import com.signalassist.core.SignalState
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

object SignalSymbols {
    const val STOP = "■"
    const val CAUTION = "▲"
    const val GO = "●"
    const val LEFT_ARROW = "←"
}

/**
 * 적록색맹 운전자를 위한 신호 표시.
 * - 위치: 실제 국내 4구 신호등과 같은 순서(적·황·좌회전·녹)로 켜진 칸을 강조
 * - 모양: 정지=팔각형(■), 주의=삼각형(▲), 진행=원(●), 좌회전=화살표
 * - 문구: "정지 / 주의 / 진행" 을 크게
 * - 색: Okabe-Ito 색각이상 친화 팔레트 (빨강 대신 주홍, 초록 대신 청록)
 */
class SignalPanelView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var state: SignalState = SignalState.UNKNOWN
        set(value) { field = value; invalidate() }

    /** 긴급 경고 시 테두리 점멸 */
    var flashLevel: AlertLevel? = null
        set(value) { field = value; invalidate() }

    private val vermilion = Color.rgb(213, 94, 0)
    private val amber = Color.rgb(240, 228, 66)
    private val bluishGreen = Color.rgb(0, 158, 115)
    private val off = Color.rgb(45, 45, 48)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Color.BLACK)

        // 4구 신호등
        val housingH = h * 0.42f
        val cell = min(w * 0.9f / 4f, housingH)
        val left = (w - cell * 4) / 2
        val top = h * 0.06f
        fill.color = Color.rgb(18, 18, 20)
        canvas.drawRoundRect(RectF(left, top, left + cell * 4, top + cell), 24f, 24f, fill)

        val r = cell * 0.36f
        val cy = top + cell / 2
        val lit = listOf(
            state.color == SignalColor.RED,
            state.color == SignalColor.YELLOW,
            state.leftTurnArrow,
            state.color == SignalColor.GREEN,
        )
        for (i in 0 until 4) {
            val cx = left + cell * (i + 0.5f)
            fill.color = if (lit[i]) listOf(vermilion, amber, bluishGreen, bluishGreen)[i] else off
            when (i) {
                0 -> drawOctagon(canvas, cx, cy, r, fill)
                1 -> drawTriangle(canvas, cx, cy, r, fill)
                2 -> drawArrow(canvas, cx, cy, r, fill)
                3 -> canvas.drawCircle(cx, cy, r, fill)
            }
            if (lit[i]) { // 켜진 칸은 흰 테두리로 한 번 더 강조 (명도 대비)
                stroke.color = Color.WHITE
                stroke.strokeWidth = cell * 0.04f
                canvas.drawRoundRect(RectF(cx - cell / 2 + 6, top + 6, cx + cell / 2 - 6, top + cell - 6), 18f, 18f, stroke)
            }
        }

        // 큰 문구
        val (symbol, label) = when (state.color) {
            SignalColor.RED -> SignalSymbols.STOP to "정지"
            SignalColor.YELLOW -> SignalSymbols.CAUTION to "주의"
            SignalColor.GREEN -> SignalSymbols.GO to "진행"
            SignalColor.UNKNOWN -> "?" to "신호 인식 중"
        }
        val arrow = if (state.leftTurnArrow) "  ${SignalSymbols.LEFT_ARROW} 좌회전" else ""
        text.color = Color.WHITE
        text.textSize = h * 0.2f
        canvas.drawText("$symbol $label$arrow", w / 2, top + cell + h * 0.3f, text)

        flashLevel?.let {
            stroke.color = if (it == AlertLevel.URGENT) vermilion else amber
            stroke.strokeWidth = h * 0.03f
            canvas.drawRect(0f, 0f, w, h, stroke)
        }
    }

    private fun drawOctagon(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        val path = Path()
        for (k in 0 until 8) {
            val a = Math.toRadians(22.5 + 45.0 * k)
            val x = cx + r * cos(a).toFloat(); val y = cy + r * sin(a).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close(); c.drawPath(path, p)
    }

    private fun drawTriangle(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        val path = Path().apply {
            moveTo(cx, cy - r); lineTo(cx + r * 0.95f, cy + r * 0.75f); lineTo(cx - r * 0.95f, cy + r * 0.75f); close()
        }
        c.drawPath(path, p)
    }

    private fun drawArrow(c: Canvas, cx: Float, cy: Float, r: Float, p: Paint) {
        val path = Path().apply {
            moveTo(cx - r, cy); lineTo(cx - r * 0.1f, cy - r * 0.7f); lineTo(cx - r * 0.1f, cy - r * 0.28f)
            lineTo(cx + r, cy - r * 0.28f); lineTo(cx + r, cy + r * 0.28f); lineTo(cx - r * 0.1f, cy + r * 0.28f)
            lineTo(cx - r * 0.1f, cy + r * 0.7f); close()
        }
        c.drawPath(path, p)
    }
}
