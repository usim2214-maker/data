package com.signalassist.app.alert

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.signalassist.core.Alert
import com.signalassist.core.AlertCategory
import com.signalassist.core.AlertLevel
import com.signalassist.core.AlertType

/**
 * '내비와 함께 쓰기': 내비 화면 위에 잠깐 뜨는 헤드업 카드의 내용.
 * 운전 중 한눈(1초 안)에 읽히도록 ① 모양 아이콘 ② 두세 단어 제목 ③ 한 문장 설명만 둔다.
 * 색은 보조일 뿐이고 의미는 모양과 글자로 전달한다 (색각이상 운전자 기준).
 */
data class GlanceCard(
    val title: String,
    val text: String,
    val shape: Shape,
    /** Okabe-Ito 계열 (색각이상에도 구분되는 색) */
    val color: Int,
    val timeoutMs: Long,
    /** 카드 버튼 '지금 신호' 를 붙일지 (신호 관련 알림만) */
    val offerSignalQuery: Boolean,
) {
    enum class Shape { OCTAGON_STOP, TRIANGLE_CAUTION, CIRCLE_GO, ARROW_LEFT, ARROW_TURN, QUESTION, WARNING, SIREN }

    companion object {
        private const val VERMILION = 0xFFD55E00.toInt()
        private const val YELLOW = 0xFFF0E442.toInt()
        private const val BLUISH_GREEN = 0xFF009E73.toInt()
        private const val BLUE = 0xFF0072B2.toInt()
        private const val ORANGE = 0xFFE69F00.toInt()
        private const val GREY = 0xFFB0B0B0.toInt()

        fun of(alert: Alert): GlanceCard {
            val t = alert.type
            val shape = when (t) {
                AlertType.RED_STOP, AlertType.RED_URGENT -> Shape.OCTAGON_STOP
                AlertType.YELLOW_CAUTION, AlertType.YELLOW_DILEMMA -> Shape.TRIANGLE_CAUTION
                AlertType.GREEN_GO, AlertType.GREEN_REMINDER -> Shape.CIRCLE_GO
                AlertType.LEFT_ARROW_ON, AlertType.LEFT_ARROW_FOR_ROUTE, AlertType.LEFT_ARROW_NOT_FOR_ROUTE -> Shape.ARROW_LEFT
                // '지금 신호' 답: 답한 신호의 모양 (확실하지 않으면 '?')
                AlertType.SIGNAL_STATUS -> when (alert.symbol) {
                    "■" -> Shape.OCTAGON_STOP; "▲" -> Shape.TRIANGLE_CAUTION; "●" -> Shape.CIRCLE_GO; "←" -> Shape.ARROW_LEFT
                    else -> Shape.QUESTION
                }
                AlertType.SIGNAL_LOST -> Shape.QUESTION
                AlertType.SIREN_NEARBY -> Shape.SIREN
                AlertType.NAV_MANEUVER_SOON -> Shape.ARROW_TURN
                else -> Shape.WARNING
            }
            val color = when (shape) {
                Shape.OCTAGON_STOP -> VERMILION
                Shape.TRIANGLE_CAUTION -> YELLOW
                Shape.CIRCLE_GO, Shape.ARROW_LEFT -> BLUISH_GREEN
                Shape.ARROW_TURN -> BLUE
                Shape.QUESTION -> GREY
                Shape.WARNING, Shape.SIREN -> if (alert.level >= AlertLevel.WARNING) VERMILION else ORANGE
            }
            val title = "${alert.symbol} ${alert.headline}" + (colorWord(t)?.let { " · $it" } ?: "")
            val timeout = when (alert.level) {
                AlertLevel.URGENT -> 8_000L
                AlertLevel.WARNING -> 6_000L
                AlertLevel.CAUTION -> 5_000L
                AlertLevel.INFO -> 4_000L
            }
            return GlanceCard(title, alert.message, shape, color, timeout, offerSignalQuery = alert.category == AlertCategory.SIGNAL)
        }

        /** 제목 뒤에 붙이는 일상어 색 이름 (헤드라인이 '정지'처럼 동작만 말할 때 신호 이름을 함께) */
        private fun colorWord(t: AlertType): String? = when (t) {
            AlertType.RED_STOP, AlertType.RED_URGENT -> "빨간불"
            AlertType.YELLOW_CAUTION, AlertType.YELLOW_DILEMMA -> "노란불"
            AlertType.GREEN_GO, AlertType.GREEN_REMINDER -> "초록불"
            else -> null
        }
    }

    /** 카드 왼쪽 큰 아이콘: 모양 + 굵은 흰 테두리 (밝은 화면·어두운 화면 모두에서 보이게) */
    fun icon(sizePx: Int = 192): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = sizePx.toFloat()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@GlanceCard.color; style = Paint.Style.FILL }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = s * 0.06f; strokeJoin = Paint.Join.ROUND }
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (shape == Shape.TRIANGLE_CAUTION) Color.BLACK else Color.WHITE
            textAlign = Paint.Align.CENTER; isFakeBoldText = true
        }
        fun both(p: Path) { c.drawPath(p, fill); c.drawPath(p, edge) }
        val m = s * 0.08f
        when (shape) {
            Shape.OCTAGON_STOP -> {
                val p = Path(); val r = (s - 2 * m) / 2; val cx = s / 2
                for (i in 0 until 8) {
                    val a = Math.toRadians(22.5 + 45.0 * i)
                    val x = cx + r * Math.cos(a).toFloat(); val y = cx + r * Math.sin(a).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close(); both(p)
                ink.textSize = s * 0.26f; c.drawText("정지", s / 2, s / 2 + s * 0.09f, ink)
            }
            Shape.TRIANGLE_CAUTION, Shape.WARNING -> {
                val p = Path().apply { moveTo(s / 2, m); lineTo(s - m, s - m * 1.3f); lineTo(m, s - m * 1.3f); close() }
                both(p)
                ink.color = if (shape == Shape.TRIANGLE_CAUTION || this.color == ORANGE) Color.BLACK else Color.WHITE
                ink.textSize = s * 0.42f; c.drawText("!", s / 2, s * 0.78f, ink)
            }
            Shape.CIRCLE_GO -> {
                c.drawCircle(s / 2, s / 2, s / 2 - m, fill); c.drawCircle(s / 2, s / 2, s / 2 - m, edge)
                ink.textSize = s * 0.26f; c.drawText("진행", s / 2, s / 2 + s * 0.09f, ink)
            }
            Shape.ARROW_LEFT, Shape.ARROW_TURN -> {
                val r = s * 0.12f
                c.drawRoundRect(m, m, s - m, s - m, r, r, fill); c.drawRoundRect(m, m, s - m, s - m, r, r, edge)
                val a = Path().apply {
                    if (shape == Shape.ARROW_LEFT) {
                        moveTo(s * 0.22f, s / 2); lineTo(s * 0.48f, s * 0.26f); lineTo(s * 0.48f, s * 0.40f)
                        lineTo(s * 0.78f, s * 0.40f); lineTo(s * 0.78f, s * 0.60f); lineTo(s * 0.48f, s * 0.60f)
                        lineTo(s * 0.48f, s * 0.74f); close()
                    } else {
                        moveTo(s / 2, s * 0.20f); lineTo(s * 0.74f, s * 0.46f); lineTo(s * 0.60f, s * 0.46f)
                        lineTo(s * 0.60f, s * 0.80f); lineTo(s * 0.40f, s * 0.80f); lineTo(s * 0.40f, s * 0.46f)
                        lineTo(s * 0.26f, s * 0.46f); close()
                    }
                }
                c.drawPath(a, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
            }
            Shape.QUESTION -> {
                val r = s * 0.12f
                c.drawRoundRect(m, m, s - m, s - m, r, r, fill); c.drawRoundRect(m, m, s - m, s - m, r, r, edge)
                ink.color = Color.BLACK; ink.textSize = s * 0.5f; c.drawText("?", s / 2, s * 0.68f, ink)
            }
            Shape.SIREN -> {
                c.drawCircle(s / 2, s / 2, s / 2 - m, fill); c.drawCircle(s / 2, s / 2, s / 2 - m, edge)
                ink.textSize = s * 0.18f; c.drawText("사이렌", s / 2, s / 2 + s * 0.06f, ink)
            }
        }
        return bmp
    }
}
