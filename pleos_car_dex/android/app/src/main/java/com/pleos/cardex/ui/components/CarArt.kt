package com.pleos.cardex.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/**
 * 차량 일러스트 (측면, 앞=왼쪽). 기본은 실차 비율 입체 렌더링(RealCarArt.kt), 카툰 버전도 보관.
 * 실제 차의 '알아볼 수 있는 특징'(비율·램프 모양·루프랙·윙 등)만 살리고 로고/엠블럼은 그리지 않는다.
 */
enum class LampStyle { PIXEL, VERTICAL, TWO_LINE, HORIZON, ROUND }
enum class Roof { HATCH, SEDAN, SUV }

data class CarStyle(
    val paint: Color,
    val roof: Roof,
    val lamp: LampStyle,
    val length: Float = 1f, // 상대 길이 (짧을수록 귀여움)
    val bodyH: Float = 0.30f, // 차체 높이 / 길이
    val cabinH: Float = 0.24f, // 캐빈 높이 / 길이
    val wheel: Float = 0.13f, // 바퀴 반지름 / 길이
    val clearance: Float = 0.05f, // 지상고 / 길이
    val rails: Boolean = false,
    val spoiler: Boolean = false,
    val stripe: Color? = null,
    val caliper: Color? = null,
    val pixelLine: Boolean = false, // 아이오닉 특유의 사선 캐릭터 라인
    val chromeLine: Boolean = false, // 세단 창문 크롬 라인
    val rearSlope: Float? = null, // 뒷유리 기울기 (null = 지붕 형태 기본값)
    val twoToneRoof: Boolean = false,
) {
    /** 전체 높이 / 폭 (바퀴~지붕) — 상자 안에 맞출 때 사용 */
    val heightRatio: Float get() = length * (clearance + bodyH + cabinH + wheel * 0.35f + (if (rails) 0.04f else 0f) + (if (spoiler) 0.03f else 0f))
}

/** 카탈로그 차종별 스타일. 모르는 차는 차체 유형 + 희귀도 색 기본형 */
fun carStyleFor(id: String, bodyType: String, rarity: String): CarStyle = when (id) {
    "hyundai-ioniq-5" -> CarStyle(Color(0xFF5FC9B8), Roof.HATCH, LampStyle.PIXEL, length = 0.92f, bodyH = 0.31f, cabinH = 0.25f, pixelLine = true)
    "hyundai-ioniq-5-n" -> CarStyle(
        Color(0xFF5AA9E6), Roof.HATCH, LampStyle.PIXEL, length = 0.95f, bodyH = 0.29f, cabinH = 0.22f, wheel = 0.145f,
        clearance = 0.03f, spoiler = true, stripe = Color(0xFFE53935), caliper = Color(0xFFE53935), pixelLine = true,
    )
    "kia-ev9" -> CarStyle(Color(0xFF3F6FB5), Roof.SUV, LampStyle.VERTICAL, length = 1.06f, bodyH = 0.3f, cabinH = 0.22f, wheel = 0.135f, clearance = 0.06f, rails = true)
    "genesis-g80" -> CarStyle(Color(0xFF2E5E4E), Roof.SEDAN, LampStyle.TWO_LINE, length = 1.08f, bodyH = 0.26f, cabinH = 0.21f, wheel = 0.125f, chromeLine = true)
    "hyundai-grandeur" -> CarStyle(Color(0xFF263859), Roof.SEDAN, LampStyle.HORIZON, length = 1.08f, bodyH = 0.26f, cabinH = 0.21f, wheel = 0.12f, chromeLine = true)
    "kia-sorento" -> CarStyle(
        Color(0xFFB0B8C1), Roof.SUV, LampStyle.VERTICAL, length = 1.0f, bodyH = 0.29f, cabinH = 0.21f, wheel = 0.13f, clearance = 0.06f,
        rails = true, rearSlope = 0.1f, twoToneRoof = true,
    )
    // ---- 현대 ----
    "hyundai-avante" -> CarStyle(Color(0xFFECEFF1), Roof.SEDAN, LampStyle.HORIZON, length = 1.04f, bodyH = 0.26f, cabinH = 0.2f, wheel = 0.12f)
    "hyundai-avante-n" -> CarStyle(
        Color(0xFF2F5FA8), Roof.SEDAN, LampStyle.HORIZON, length = 1.04f, bodyH = 0.25f, cabinH = 0.19f, wheel = 0.135f,
        clearance = 0.03f, spoiler = true, stripe = Color(0xFFE53935), caliper = Color(0xFFE53935),
    )
    "hyundai-sonata" -> CarStyle(Color(0xFF8FA8C8), Roof.SEDAN, LampStyle.HORIZON, length = 1.08f, bodyH = 0.25f, cabinH = 0.2f, wheel = 0.12f, chromeLine = true)
    "hyundai-tucson" -> CarStyle(Color(0xFF5E7D5A), Roof.SUV, LampStyle.PIXEL, length = 1.0f, bodyH = 0.29f, cabinH = 0.21f, clearance = 0.06f, rails = true, rearSlope = 0.08f)
    "hyundai-palisade" -> CarStyle(Color(0xFF2B2F36), Roof.SUV, LampStyle.VERTICAL, length = 1.1f, bodyH = 0.31f, cabinH = 0.23f, wheel = 0.135f, clearance = 0.06f, rails = true)
    "hyundai-casper" -> CarStyle(Color(0xFFF2C94C), Roof.SUV, LampStyle.ROUND, length = 0.8f, bodyH = 0.32f, cabinH = 0.24f, wheel = 0.14f, clearance = 0.05f)
    "hyundai-ioniq-6" -> CarStyle(Color(0xFFE76F51), Roof.SEDAN, LampStyle.PIXEL, length = 1.06f, bodyH = 0.25f, cabinH = 0.2f, wheel = 0.125f, rearSlope = 0.3f, pixelLine = true)
    "hyundai-staria" -> CarStyle(Color(0xFF9AA5B1), Roof.SUV, LampStyle.HORIZON, length = 1.12f, bodyH = 0.3f, cabinH = 0.3f, wheel = 0.12f, clearance = 0.05f)
    "hyundai-kona" -> CarStyle(Color(0xFF7FB069), Roof.SUV, LampStyle.HORIZON, length = 0.95f, bodyH = 0.29f, cabinH = 0.21f, clearance = 0.06f, rearSlope = 0.07f)
    // ---- 기아 ----
    "kia-morning" -> CarStyle(Color(0xFFF4A6C0), Roof.HATCH, LampStyle.ROUND, length = 0.82f, bodyH = 0.3f, cabinH = 0.24f, wheel = 0.13f)
    "kia-ray" -> CarStyle(Color(0xFF9ED9C8), Roof.SUV, LampStyle.ROUND, length = 0.84f, bodyH = 0.31f, cabinH = 0.3f, wheel = 0.125f)
    "kia-k5" -> CarStyle(Color(0xFFC0392B), Roof.SEDAN, LampStyle.TWO_LINE, length = 1.06f, bodyH = 0.25f, cabinH = 0.19f, wheel = 0.125f, rearSlope = 0.24f)
    "kia-k8" -> CarStyle(Color(0xFF1F2A37), Roof.SEDAN, LampStyle.HORIZON, length = 1.1f, bodyH = 0.25f, cabinH = 0.2f, wheel = 0.125f, chromeLine = true, rearSlope = 0.22f)
    "kia-sportage" -> CarStyle(Color(0xFFE59A3C), Roof.SUV, LampStyle.VERTICAL, length = 1.0f, bodyH = 0.29f, cabinH = 0.21f, clearance = 0.06f, rails = true, rearSlope = 0.09f, twoToneRoof = true)
    "kia-seltos" -> CarStyle(Color(0xFF3D7DCA), Roof.SUV, LampStyle.HORIZON, length = 0.95f, bodyH = 0.29f, cabinH = 0.21f, clearance = 0.06f, rails = true)
    "kia-carnival" -> CarStyle(Color(0xFFF5F5F5), Roof.SUV, LampStyle.HORIZON, length = 1.12f, bodyH = 0.3f, cabinH = 0.26f, wheel = 0.12f, clearance = 0.05f)
    "kia-ev3" -> CarStyle(Color(0xFF7FC8A9), Roof.SUV, LampStyle.VERTICAL, length = 0.93f, bodyH = 0.29f, cabinH = 0.21f, clearance = 0.05f, twoToneRoof = true)
    "kia-ev6" -> CarStyle(Color(0xFF7D8590), Roof.HATCH, LampStyle.HORIZON, length = 1.04f, bodyH = 0.27f, cabinH = 0.19f, wheel = 0.13f, clearance = 0.04f, rearSlope = 0.12f)
    // ---- 제네시스 ----
    "genesis-g90" -> CarStyle(Color(0xFF1B1B1F), Roof.SEDAN, LampStyle.TWO_LINE, length = 1.14f, bodyH = 0.26f, cabinH = 0.21f, wheel = 0.125f, chromeLine = true)
    "genesis-gv70" -> CarStyle(Color(0xFF8E2B2B), Roof.SUV, LampStyle.TWO_LINE, length = 1.0f, bodyH = 0.29f, cabinH = 0.21f, clearance = 0.06f, rearSlope = 0.1f)
    "genesis-gv80" -> CarStyle(Color(0xFF243B55), Roof.SUV, LampStyle.TWO_LINE, length = 1.08f, bodyH = 0.3f, cabinH = 0.22f, wheel = 0.135f, clearance = 0.06f, chromeLine = true)
    "genesis-gv60" -> CarStyle(Color(0xFF3E6B5A), Roof.HATCH, LampStyle.TWO_LINE, length = 0.98f, bodyH = 0.28f, cabinH = 0.2f, wheel = 0.13f, clearance = 0.04f)
    else -> {
        val paint = when (rarity) { "legendary" -> Color(0xFFF2B33D); "rare" -> Color(0xFF8E7CF0); else -> Color(0xFF4F8EF7) }
        when {
            bodyType.contains("SUV") || bodyType.contains("트럭") -> CarStyle(paint, Roof.SUV, LampStyle.ROUND, length = 1.02f, bodyH = 0.3f, cabinH = 0.22f, rails = true, clearance = 0.06f)
            bodyType.contains("CUV") || bodyType.contains("해치") -> CarStyle(paint, Roof.HATCH, LampStyle.ROUND, length = 0.92f)
            bodyType.contains("쿠페") -> CarStyle(paint, Roof.SEDAN, LampStyle.ROUND, bodyH = 0.25f, cabinH = 0.18f, clearance = 0.035f)
            else -> CarStyle(paint, Roof.SEDAN, LampStyle.ROUND, length = 1.05f, bodyH = 0.27f, cabinH = 0.21f)
        }
    }
}

/** 단독 일러스트 (목록 썸네일 등). silhouette=true 면 아직 못 모은 차량용 그림자 실루엣 */
@Composable
fun CarIllustration(id: String, bodyType: String, rarity: String, modifier: Modifier = Modifier, silhouette: Boolean = false) {
    val style = carStyleFor(id, bodyType, rarity)
    Canvas(modifier) {
        // 키 큰 SUV 도 잘리지 않게 높이에 맞춰 축소
        val w = minOf(size.width * 0.88f / style.length, size.height * 0.8f / style.realHeightRatio())
        if (!silhouette) {
            drawCartoonCar(style, centerX = size.width / 2, groundY = size.height * 0.9f, width = w)
        } else {
            // 별도 레이어에 차를 그린 뒤, 그려진 픽셀만 단색으로 덮어 실루엣을 만든다 (배경은 그대로)
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(Offset.Zero, size), Paint())
                drawCartoonCar(style, centerX = size.width / 2, groundY = size.height * 0.9f, width = w)
                drawRect(Color(0xFF2E3644), blendMode = BlendMode.SrcIn)
                canvas.restore()
            }
        }
    }
}

/** 차량 그리기 진입점: 실차 비율 입체 일러스트 ([drawRealCar]). 예전 카툰 버전은 [drawToyCar] */
fun DrawScope.drawCartoonCar(style: CarStyle, centerX: Float, groundY: Float, width: Float, light: Color = Color.White) =
    drawRealCar(style, centerX, groundY, width, light)

/** (이전) 둥글둥글 카툰 버전 — 필요 시 전환용으로 보관 */
fun DrawScope.drawToyCar(style: CarStyle, centerX: Float, groundY: Float, width: Float, light: Color = Color.White) {
    val L = width * style.length
    val x0 = centerX - L / 2
    val wheelR = L * style.wheel
    val outline = style.paint.darken(0.62f)
    val stroke = L * 0.022f
    val bodyBottom = groundY - L * style.clearance - wheelR * 0.35f
    val bodyTop = bodyBottom - L * style.bodyH
    val cabinTop = bodyTop - L * style.cabinH
    val bodyRadius = L * style.bodyH * 0.48f

    // 캐빈 영역 (앞 유리 시작~뒷 유리 끝)
    val (cs, ce, frontSlope, defaultRear) = when (style.roof) {
        Roof.HATCH -> listOf(0.24f, 0.93f, 0.16f, 0.05f)
        Roof.SEDAN -> listOf(0.26f, 0.84f, 0.17f, 0.17f)
        Roof.SUV -> listOf(0.22f, 0.95f, 0.12f, 0.03f)
    }
    val rearSlope = style.rearSlope ?: defaultRear
    val cabin = Path().apply {
        val left = x0 + L * cs; val right = x0 + L * ce
        val tl = left + L * frontSlope; val tr = right - L * rearSlope
        val r = L * 0.07f
        moveTo(left, bodyTop + 2f)
        lineTo(tl - r * 0.4f, cabinTop + r * 0.6f)
        quadraticTo(tl, cabinTop, tl + r, cabinTop)
        lineTo(tr - r, cabinTop)
        quadraticTo(tr, cabinTop, tr + r * 0.3f, cabinTop + r * 0.7f)
        lineTo(right, bodyTop + 2f)
        close()
    }
    val bodyRect = RoundRect(Rect(x0, bodyTop, x0 + L, bodyBottom), CornerRadius(bodyRadius, bodyRadius))
    val body = Path().apply { addRoundRect(bodyRect) }

    // 그림자
    drawOval(Color.Black.copy(alpha = 0.35f), Offset(x0 - L * 0.02f, groundY - wheelR * 0.28f), Size(L * 1.04f, wheelR * 0.62f))

    // 1) 외곽선을 먼저 굵게 → 2) 채우기 : 차체+캐빈이 한 덩어리 실루엣이 됨
    val outlineStyle = Stroke(stroke * 2, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(cabin, outline, style = outlineStyle)
    drawPath(body, outline, style = outlineStyle)
    if (style.rails) drawLine(outline, Offset(x0 + L * 0.34f, cabinTop - stroke * 1.6f), Offset(x0 + L * 0.9f, cabinTop - stroke * 1.6f), stroke * 2.2f, StrokeCap.Round)
    if (style.spoiler) drawSpoiler(x0, L, cabinTop, bodyTop, outline, style.paint, stroke)

    val shade = Brush.verticalGradient(listOf(style.paint.lighten(0.22f), style.paint, style.paint.darken(0.18f)), startY = cabinTop, endY = bodyBottom)
    drawPath(cabin, shade)
    drawPath(body, shade)
    if (style.twoToneRoof) { // 투톤: 지붕만 검정
        drawRect(Color(0xFF2A2F36), Offset(x0 + L * (cs + frontSlope), cabinTop - stroke), Size(L * (ce - cs - frontSlope - rearSlope), L * 0.03f + stroke))
    }

    // 하단 스커트 (더 어둡게) - 차체 아래쪽 띠
    drawRoundRect(
        style.paint.darken(0.3f), Offset(x0 + L * 0.06f, bodyBottom - L * 0.06f), Size(L * 0.88f, L * 0.05f),
        CornerRadius(L * 0.025f, L * 0.025f),
    )

    // 창문 (큰 둥근 유리 + 반짝이)
    val glassInset = L * 0.03f
    val window = Path().apply {
        val left = x0 + L * cs + glassInset * 1.4f; val right = x0 + L * ce - glassInset * 1.4f
        val top = cabinTop + glassInset; val bottom = bodyTop - glassInset * 0.2f
        val tl = left + L * frontSlope * 0.92f; val tr = right - L * rearSlope * 0.9f
        val r = L * 0.05f
        moveTo(left, bottom)
        lineTo(tl - r * 0.3f, top + r * 0.5f)
        quadraticTo(tl, top, tl + r, top)
        lineTo(tr - r, top)
        quadraticTo(tr, top, tr + r * 0.2f, top + r * 0.6f)
        lineTo(right, bottom)
        close()
    }
    drawPath(window, Brush.verticalGradient(listOf(Color(0xFFCDEBFF), Color(0xFF6FA8DC)), startY = cabinTop, endY = bodyTop))
    drawPath(window, outline, style = Stroke(stroke * 0.8f, join = StrokeJoin.Round))
    val pillar = x0 + L * (cs + (ce - cs) * (if (style.roof == Roof.SEDAN) 0.55f else 0.52f))
    drawLine(outline, Offset(pillar, cabinTop + glassInset), Offset(pillar, bodyTop), stroke * 0.9f)
    if (style.chromeLine) drawPath(window, Color(0xFFE6E6E6), style = Stroke(stroke * 0.35f))
    // 유리 반짝이
    drawLine(Color.White.copy(alpha = 0.85f), Offset(pillar - L * 0.14f, bodyTop - L * 0.035f), Offset(pillar - L * 0.08f, cabinTop + glassInset * 2.2f), stroke * 0.9f, StrokeCap.Round)
    drawLine(Color.White.copy(alpha = 0.6f), Offset(pillar - L * 0.1f, bodyTop - L * 0.03f), Offset(pillar - L * 0.065f, cabinTop + glassInset * 3f), stroke * 0.5f, StrokeCap.Round)

    // 차체 광택 (위쪽 길쭉한 하이라이트)
    drawRoundRect(
        Color.White.copy(alpha = 0.35f), Offset(x0 + L * 0.12f, bodyTop + L * 0.03f), Size(L * 0.52f, L * 0.028f),
        CornerRadius(L * 0.014f, L * 0.014f),
    )
    // 문 라인
    drawLine(outline.copy(alpha = 0.55f), Offset(pillar, bodyTop + L * 0.02f), Offset(pillar - L * 0.01f, bodyBottom - L * 0.07f), stroke * 0.5f)
    drawRoundRect(outline.copy(alpha = 0.7f), Offset(pillar - L * 0.09f, bodyTop + L * 0.08f), Size(L * 0.05f, L * 0.016f), CornerRadius(L * 0.01f))

    // 차종 특징
    if (style.pixelLine) { // 사선 캐릭터 라인
        drawLine(outline.copy(alpha = 0.5f), Offset(x0 + L * 0.3f, bodyBottom - L * 0.08f), Offset(x0 + L * 0.62f, bodyTop + L * 0.12f), stroke * 0.55f, StrokeCap.Round)
    }
    style.stripe?.let { // N 포인트 라인
        drawRoundRect(it, Offset(x0 + L * 0.05f, bodyBottom - L * 0.075f), Size(L * 0.9f, L * 0.018f), CornerRadius(L * 0.01f))
    }
    drawLamps(style, x0, L, bodyTop, bodyBottom, outline, stroke, light)

    // 바퀴 (크고 동글게)
    listOf(0.22f, 0.79f).forEach { fx ->
        val c = Offset(x0 + L * fx, groundY - wheelR)
        drawCircle(outline, wheelR + stroke, c)
        drawCircle(Color(0xFF2B2B2B), wheelR, c)
        drawCircle(Brush.radialGradient(listOf(Color(0xFFF2F2F2), Color(0xFF9E9E9E)), center = c, radius = wheelR * 0.6f), wheelR * 0.6f, c)
        style.caliper?.let { drawArc(it, 200f, 70f, false, Offset(c.x - wheelR * 0.5f, c.y - wheelR * 0.5f), Size(wheelR, wheelR), style = Stroke(wheelR * 0.16f, cap = StrokeCap.Round)) }
        for (k in 0 until 5) {
            val a = Math.toRadians(k * 72.0 - 90)
            drawCircle(Color(0xFF6E6E6E), wheelR * 0.09f, Offset(c.x + wheelR * 0.36f * cos(a).toFloat(), c.y + wheelR * 0.36f * sin(a).toFloat()))
        }
        drawCircle(Color.White.copy(alpha = 0.7f), wheelR * 0.12f, Offset(c.x - wheelR * 0.18f, c.y - wheelR * 0.2f))
    }
}

private fun DrawScope.drawSpoiler(x0: Float, L: Float, cabinTop: Float, bodyTop: Float, outline: Color, paint: Color, stroke: Float) {
    val wingY = cabinTop - L * 0.02f
    val wing = Path().apply {
        moveTo(x0 + L * 0.82f, wingY); lineTo(x0 + L * 1.02f, wingY - L * 0.025f)
        lineTo(x0 + L * 1.02f, wingY + L * 0.02f); lineTo(x0 + L * 0.84f, wingY + L * 0.03f); close()
    }
    drawPath(wing, outline, style = Stroke(stroke * 2, join = StrokeJoin.Round))
    drawPath(wing, paint.darken(0.35f))
    drawLine(outline, Offset(x0 + L * 0.95f, wingY + L * 0.01f), Offset(x0 + L * 0.93f, bodyTop + L * 0.02f), stroke * 1.4f, StrokeCap.Round)
}

private fun DrawScope.drawLamps(style: CarStyle, x0: Float, L: Float, bodyTop: Float, bodyBottom: Float, outline: Color, stroke: Float, light: Color) {
    val headX = x0 + L * 0.035f
    val lampY = bodyTop + L * 0.07f
    val warm = Color(0xFFFFF4B0)
    val red = Color(0xFFFF4D4D)
    when (style.lamp) {
        LampStyle.PIXEL -> { // 작은 사각 픽셀 격자
            for (r in 0 until 2) for (c in 0 until 3) {
                drawRect(warm, Offset(headX + c * L * 0.022f, lampY + r * L * 0.022f), Size(L * 0.016f, L * 0.016f))
                drawRect(red, Offset(x0 + L * 0.9f + c * L * 0.022f, lampY + r * L * 0.022f), Size(L * 0.016f, L * 0.016f))
            }
        }
        LampStyle.VERTICAL -> { // 세로로 긴 램프
            drawRoundRect(warm, Offset(headX, lampY - L * 0.02f), Size(L * 0.022f, L * 0.11f), CornerRadius(L * 0.011f))
            drawRoundRect(warm.copy(alpha = 0.7f), Offset(headX + L * 0.032f, lampY), Size(L * 0.014f, L * 0.08f), CornerRadius(L * 0.007f))
            drawRoundRect(red, Offset(x0 + L * 0.95f, lampY - L * 0.02f), Size(L * 0.022f, L * 0.11f), CornerRadius(L * 0.011f))
        }
        LampStyle.TWO_LINE -> { // 가늘게 두 줄
            listOf(0f, L * 0.035f).forEach { dy ->
                drawLine(warm, Offset(headX, lampY + dy), Offset(headX + L * 0.11f, lampY + dy - L * 0.006f), L * 0.012f, StrokeCap.Round)
                drawLine(red, Offset(x0 + L * 0.86f, lampY + dy), Offset(x0 + L * 0.975f, lampY + dy), L * 0.012f, StrokeCap.Round)
            }
        }
        LampStyle.HORIZON -> { // 앞을 가로지르는 일자 LED + 일자 테일램프
            drawLine(warm, Offset(headX - L * 0.005f, lampY - L * 0.01f), Offset(headX + L * 0.2f, lampY - L * 0.022f), L * 0.012f, StrokeCap.Round)
            drawLine(red, Offset(x0 + L * 0.8f, lampY - L * 0.018f), Offset(x0 + L * 0.985f, lampY - L * 0.01f), L * 0.014f, StrokeCap.Round)
        }
        LampStyle.ROUND -> { // 동그란 '눈'
            drawCircle(outline, L * 0.04f, Offset(headX + L * 0.03f, lampY + L * 0.015f))
            drawCircle(warm, L * 0.03f, Offset(headX + L * 0.03f, lampY + L * 0.015f))
            drawCircle(Color.White, L * 0.01f, Offset(headX + L * 0.022f, lampY + L * 0.006f))
            drawRoundRect(red, Offset(x0 + L * 0.95f, lampY), Size(L * 0.025f, L * 0.05f), CornerRadius(L * 0.012f))
        }
    }
    // 헤드라이트 빛
    drawOval(
        Brush.radialGradient(listOf(warm.copy(alpha = 0.45f), Color.Transparent), center = Offset(x0, lampY + L * 0.02f), radius = L * 0.14f),
        Offset(x0 - L * 0.14f, lampY - L * 0.05f), Size(L * 0.28f, L * 0.14f),
    )
}

private fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)
private fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)
