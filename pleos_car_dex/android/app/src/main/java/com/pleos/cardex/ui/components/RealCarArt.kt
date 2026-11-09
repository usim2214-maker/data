package com.pleos.cardex.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import kotlin.math.cos
import kotlin.math.sin

/**
 * 실차 비율의 입체 일러스트 (측면, 앞=왼쪽). 외곽선 없이 빛과 반사로 입체감을 낸다.
 * - 차체: 메탈릭 도장 반사 (위는 하늘빛, 숄더 하이라이트, 아래는 지면 반사로 어둡게) + 수평 반사선
 * - 유리: 짙은 틴팅 + 사선 하늘 반사, B필러, 크롬/블랙 몰딩
 * - 휠: 타이어 측면 음영, 브레이크 디스크·캘리퍼가 비치는 멀티 스포크 알로이
 * 로고·엠블럼·번호판은 그리지 않는다 (상표·개인정보). 차종 특징은 [CarStyle] 의 램프·지붕·비율로 표현.
 */
internal data class P(val x: Float, val y: Float) // x: 길이 비율(앞=0), y: 전고 비율(지면=0)

internal class Profile(val body: List<P>, val glass: List<P>, val height: Float, val axles: Pair<Float, Float>)

internal fun profileOf(s: CarStyle): Profile {
    val mpv = s.roof == Roof.SUV && s.cabinH >= 0.26f
    val small = s.length < 0.86f
    var body: List<P>
    var glass: List<P>
    var h: Float
    var axles = 0.19f to 0.79f
    when {
        mpv -> {
            h = 0.43f
            body = listOf(P(0.005f, 0.2f), P(0f, 0.46f), P(0.015f, 0.6f), P(0.08f, 0.615f), P(0.2f, 0.625f), P(0.31f, 0.985f), P(0.93f, 0.98f), P(0.99f, 0.86f), P(1f, 0.5f), P(0.995f, 0.2f))
            glass = listOf(P(0.215f, 0.645f), P(0.315f, 0.945f), P(0.915f, 0.945f), P(0.965f, 0.655f))
            axles = 0.17f to 0.81f
        }
        s.roof == Roof.SUV -> {
            h = 0.40f
            body = listOf(P(0.005f, 0.22f), P(0f, 0.48f), P(0.015f, 0.62f), P(0.08f, 0.625f), P(0.25f, 0.635f), P(0.39f, 0.985f), P(0.82f, 0.975f), P(0.96f, 0.88f), P(0.99f, 0.72f), P(1f, 0.52f), P(0.995f, 0.22f))
            glass = listOf(P(0.27f, 0.650f), P(0.395f, 0.945f), P(0.81f, 0.935f), P(0.935f, 0.665f))
        }
        s.roof == Roof.HATCH -> {
            h = 0.355f
            body = listOf(P(0.005f, 0.18f), P(0f, 0.43f), P(0.02f, 0.56f), P(0.09f, 0.585f), P(0.25f, 0.605f), P(0.42f, 0.98f), P(0.84f, 0.97f), P(0.97f, 0.66f), P(1f, 0.5f), P(0.995f, 0.18f))
            glass = listOf(P(0.27f, 0.625f), P(0.425f, 0.94f), P(0.83f, 0.935f), P(0.94f, 0.620f))
        }
        else -> { // 세단
            h = 0.31f
            body = listOf(P(0.005f, 0.18f), P(0f, 0.43f), P(0.025f, 0.57f), P(0.1f, 0.595f), P(0.28f, 0.605f), P(0.46f, 0.98f), P(0.66f, 0.99f), P(0.85f, 0.67f), P(0.965f, 0.66f), P(1f, 0.55f), P(0.995f, 0.18f))
            glass = listOf(P(0.305f, 0.625f), P(0.465f, 0.94f), P(0.655f, 0.95f), P(0.82f, 0.640f))
        }
    }
    // 뒷유리 기울기: 기본보다 크면 지붕 끝을 앞으로 당겨 패스트백
    val defaultRear = when (s.roof) { Roof.SEDAN -> 0.17f; Roof.HATCH -> 0.05f; Roof.SUV -> 0.03f }
    val shift = ((s.rearSlope ?: defaultRear) - defaultRear) * 0.9f
    if (shift != 0f) {
        val roofBackBody = body.indices.filter { body[it].y > 0.9f }.maxOf { it }
        body = body.mapIndexed { i, p -> if (i == roofBackBody) p.copy(x = p.x - shift) else p }
        glass = glass.mapIndexed { i, p -> if (i == 2) p.copy(x = p.x - shift) else p }
    }
    if (small) h += 0.06f // 경차는 짧고 높다
    if (s.spoiler || s.clearance < 0.04f) h -= 0.015f // 고성능: 낮게
    return Profile(body, glass, h, axles)
}

/** 바퀴~지붕 높이 / 폭 (상자에 맞출 때) */
fun CarStyle.realHeightRatio(): Float = length * (profileOf(this).height + (if (rails) 0.02f else 0f))

/** 꺾인 점들을 모서리만 둥글린 매끈한 닫힌 경로로 */
private fun smoothClosed(pts: List<Offset>, round: Float = 0.35f): Path = Path().apply {
    val n = pts.size
    fun lerp(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    val start = lerp(pts[0], pts[1], round)
    moveTo(start.x, start.y)
    for (i in 1..n) {
        val prev = pts[(i - 1) % n]; val cur = pts[i % n]; val next = pts[(i + 1) % n]
        val a = lerp(cur, prev, round); val b = lerp(cur, next, round)
        lineTo(a.x, a.y)
        quadraticTo(cur.x, cur.y, b.x, b.y)
    }
    close()
}

fun DrawScope.drawRealCar(style: CarStyle, centerX: Float, groundY: Float, width: Float, light: Color = Color.White) {
    val L = width * style.length
    val x0 = centerX - L / 2
    val prof = profileOf(style)
    val H = L * prof.height
    fun at(p: P) = Offset(x0 + p.x * L, groundY - p.y * H)
    val bodyPts = prof.body.map(::at)
    val body = smoothClosed(bodyPts, 0.32f)
    val wheelR = L * (if (style.spoiler || style.caliper != null) 0.09f else 0.083f)
    val axleY = groundY - wheelR
    val axles = listOf(x0 + prof.axles.first * L, x0 + prof.axles.second * L)
    val paint = style.paint
    val roofY = groundY - H
    val beltY = groundY - H * prof.glass.first().y

    // ---- 그림자 (부드러운 접지 그림자) ----
    drawOval(
        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), center = Offset(centerX, groundY), radius = L * 0.58f),
        Offset(x0 - L * 0.06f, groundY - wheelR * 0.35f), Size(L * 1.12f, wheelR * 0.7f),
    )

    // ---- 차체 도장: 위=하늘 반사, 숄더 하이라이트, 아래=지면 반사 ----
    val sky = Color(0xFFDCE8F5)
    drawPath(
        body,
        Brush.verticalGradient(
            0f to paint.lighten(0.18f).mix(sky, 0.12f),
            0.42f to paint.lighten(0.32f).mix(light, 0.08f),
            0.47f to paint.lighten(0.05f),
            0.62f to paint,
            0.8f to paint.darken(0.35f),
            1f to paint.darken(0.62f),
            startY = roofY, endY = groundY,
        ),
    )
    clipPath(body) {
        // 수평 반사선 (지평선이 비친 듯한 선)
        val horizonY = beltY + (groundY - beltY) * 0.33f
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0f)), startY = horizonY - H * 0.04f, endY = horizonY + H * 0.03f), Offset(x0, horizonY - H * 0.04f), Size(L, H * 0.07f))
        // 앞쪽(빛 방향)은 밝게, 뒤쪽은 살짝 어둡게 → 입체감
        drawRect(Brush.horizontalGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Transparent, Color.Black.copy(alpha = 0.18f)), startX = x0, endX = x0 + L), Offset(x0, roofY), Size(L, H))
        // 숄더 캐릭터 라인
        drawLine(Color.White.copy(alpha = 0.45f), Offset(x0 + L * 0.06f, beltY + H * 0.05f), Offset(x0 + L * 0.95f, beltY + H * 0.03f), L * 0.004f, StrokeCap.Round)
        drawLine(Color.Black.copy(alpha = 0.18f), Offset(x0 + L * 0.06f, beltY + H * 0.075f), Offset(x0 + L * 0.95f, beltY + H * 0.055f), L * 0.004f, StrokeCap.Round)
        if (style.pixelLine) drawLine(Color.Black.copy(alpha = 0.25f), Offset(x0 + L * 0.3f, groundY - H * 0.25f), Offset(x0 + L * 0.66f, beltY + H * 0.12f), L * 0.005f, StrokeCap.Round)
        // 하단 블랙 클래딩(SUV)·사이드 스커트
        val skirtTop = groundY - H * (if (style.roof == Roof.SUV) 0.3f else 0.24f)
        drawRect(Color(0xFF15181C).copy(alpha = if (style.roof == Roof.SUV) 0.75f else 0.45f), Offset(x0, skirtTop), Size(L, groundY - skirtTop))
        style.stripe?.let { drawRect(it, Offset(x0 + L * 0.05f, skirtTop - L * 0.006f), Size(L * 0.9f, L * 0.007f)) }
        // 휠 아치 (안쪽 그늘)
        axles.forEach { ax ->
            drawCircle(Color(0xFF0B0C0E), wheelR * 1.2f, Offset(ax, axleY))
            drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.12f)), center = Offset(ax, axleY), radius = wheelR * 1.28f), wheelR * 1.28f, Offset(ax, axleY), style = Stroke(wheelR * 0.08f))
        }
    }

    // ---- 유리 ----
    val glassPts = prof.glass.map(::at)
    val glass = smoothClosed(glassPts, 0.18f)
    drawPath(glass, Brush.verticalGradient(listOf(Color(0xFF3B4B5E), Color(0xFF141B24)), startY = roofY, endY = beltY))
    clipPath(glass) {
        // 사선 하늘 반사
        val g0 = glassPts[1]
        val refl = Path().apply {
            moveTo(g0.x + L * 0.02f, g0.y); lineTo(g0.x + L * 0.17f, g0.y); lineTo(g0.x + L * 0.07f, beltY); lineTo(g0.x - L * 0.08f, beltY); close()
        }
        drawPath(refl, Color.White.copy(alpha = 0.16f))
        val refl2 = Path().apply {
            moveTo(g0.x + L * 0.22f, g0.y); lineTo(g0.x + L * 0.26f, g0.y); lineTo(g0.x + L * 0.16f, beltY); lineTo(g0.x + L * 0.12f, beltY); close()
        }
        drawPath(refl2, Color.White.copy(alpha = 0.1f))
        // B필러 (블랙)
        val pillarX = glassPts[1].x + (glassPts[2].x - glassPts[1].x) * (if (style.roof == Roof.SEDAN) 0.52f else 0.42f)
        drawRect(Color(0xFF0A0C0F), Offset(pillarX - L * 0.012f, roofY), Size(L * 0.024f, beltY - roofY))
        if (style.roof == Roof.SUV) { // C필러 쿼터 글라스 구분
            val cX = glassPts[1].x + (glassPts[2].x - glassPts[1].x) * 0.8f
            drawRect(Color(0xFF0A0C0F), Offset(cX - L * 0.01f, roofY), Size(L * 0.02f, beltY - roofY))
        }
    }
    drawPath(glass, if (style.chromeLine) Color(0xFFE8ECEF) else Color(0xFF0E1013), style = Stroke(L * (if (style.chromeLine) 0.005f else 0.006f)))
    if (style.twoToneRoof) {
        drawRect(Color(0xFF17191D), Offset(glassPts[1].x, roofY - L * 0.002f), Size(glassPts[2].x - glassPts[1].x, glassPts[1].y - roofY + L * 0.004f))
    }
    if (style.rails) {
        drawRoundRect(Color(0xFF22252A), Offset(glassPts[1].x + L * 0.04f, roofY - L * 0.012f), Size(glassPts[2].x - glassPts[1].x - L * 0.06f, L * 0.013f), CornerRadius(L * 0.006f))
        drawLine(Color.White.copy(alpha = 0.35f), Offset(glassPts[1].x + L * 0.05f, roofY - L * 0.009f), Offset(glassPts[2].x - L * 0.03f, roofY - L * 0.009f), L * 0.002f)
    }
    if (style.spoiler) {
        val tip = bodyPts[prof.body.indices.filter { prof.body[it].y > 0.9f }.maxOf { it }]
        val wing = Path().apply {
            moveTo(tip.x - L * 0.08f, tip.y + L * 0.004f); lineTo(tip.x + L * 0.012f, tip.y - L * 0.005f)
            lineTo(tip.x + L * 0.018f, tip.y + L * 0.002f); lineTo(tip.x - L * 0.06f, tip.y + L * 0.012f); close()
        }
        drawPath(wing, Color(0xFF15171A))
    }

    // ---- 사이드미러 · 도어 라인 · 손잡이 ----
    val a = glassPts[0]
    val mirror = Path().apply {
        moveTo(a.x + L * 0.005f, a.y + L * 0.004f); lineTo(a.x - L * 0.045f, a.y - L * 0.006f)
        quadraticTo(a.x - L * 0.06f, a.y + L * 0.02f, a.x - L * 0.035f, a.y + L * 0.03f); lineTo(a.x + L * 0.01f, a.y + L * 0.025f); close()
    }
    drawPath(mirror, Brush.verticalGradient(listOf(paint.lighten(0.25f), paint.darken(0.3f)), startY = a.y - L * 0.01f, endY = a.y + L * 0.03f))
    val doorLine = Color.Black.copy(alpha = 0.35f)
    val doorBottom = groundY - H * 0.24f
    val frontDoorX = a.x + L * 0.01f
    val pillar = glassPts[1].x + (glassPts[2].x - glassPts[1].x) * (if (style.roof == Roof.SEDAN) 0.52f else 0.42f)
    val rearDoorX = minOf(axles[1] - wheelR * 1.25f, glassPts[3].x - L * 0.03f)
    listOf(frontDoorX, pillar, rearDoorX).forEach { x -> drawLine(doorLine, Offset(x, beltY + L * 0.004f), Offset(x - L * 0.006f, doorBottom), L * 0.0028f) }
    listOf(pillar - L * 0.07f, rearDoorX - L * 0.07f).forEach { hx ->
        drawRoundRect(Color.Black.copy(alpha = 0.35f), Offset(hx, beltY + H * 0.1f), Size(L * 0.045f, L * 0.011f), CornerRadius(L * 0.006f))
        drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(hx + L * 0.004f, beltY + H * 0.1f), Size(L * 0.037f, L * 0.004f), CornerRadius(L * 0.002f))
    }

    drawRealLamps(style, x0, L, groundY, H, light)

    // ---- 휠 ----
    axles.forEach { ax -> drawRealWheel(Offset(ax, axleY), wheelR, style.caliper, style.spoiler || style.caliper != null) }
}

private fun DrawScope.drawRealLamps(style: CarStyle, x0: Float, L: Float, groundY: Float, H: Float, light: Color) {
    val warm = Color(0xFFFFF7D6)
    val red = Color(0xFFE5262E)
    val headY = groundY - H * 0.5f
    val tailY = groundY - H * 0.56f
    val front = x0 + L * 0.004f
    val back = x0 + L * 0.996f
    fun glow(c: Offset, r: Float, col: Color) = drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.55f), Color.Transparent), center = c, radius = r), r, c)
    when (style.lamp) {
        LampStyle.PIXEL -> for (r in 0 until 2) for (c in 0 until 4) {
            drawRect(warm, Offset(front + L * 0.006f + c * L * 0.011f, headY - L * 0.01f + r * L * 0.011f), Size(L * 0.008f, L * 0.008f))
            drawRect(red, Offset(back - L * 0.05f + c * L * 0.011f, tailY - L * 0.01f + r * L * 0.011f), Size(L * 0.008f, L * 0.008f))
        }
        LampStyle.VERTICAL -> {
            drawRoundRect(warm, Offset(front + L * 0.004f, headY - L * 0.035f), Size(L * 0.01f, L * 0.06f), CornerRadius(L * 0.005f))
            drawRoundRect(red, Offset(back - L * 0.014f, tailY - L * 0.035f), Size(L * 0.01f, L * 0.065f), CornerRadius(L * 0.005f))
        }
        LampStyle.TWO_LINE -> listOf(0f, L * 0.016f).forEach { dy ->
            drawLine(warm, Offset(front + L * 0.004f, headY - L * 0.008f + dy), Offset(front + L * 0.07f, headY - L * 0.014f + dy), L * 0.0055f, StrokeCap.Round)
            drawLine(red, Offset(back - L * 0.075f, tailY - L * 0.006f + dy), Offset(back - L * 0.004f, tailY - L * 0.006f + dy), L * 0.0055f, StrokeCap.Round)
        }
        LampStyle.HORIZON -> {
            drawLine(warm, Offset(front + L * 0.002f, headY - L * 0.012f), Offset(front + L * 0.12f, headY - L * 0.024f), L * 0.005f, StrokeCap.Round)
            drawRoundRect(Color(0xFF1B1E22), Offset(front + L * 0.006f, headY - L * 0.004f), Size(L * 0.05f, L * 0.018f), CornerRadius(L * 0.006f))
            drawLine(red, Offset(back - L * 0.12f, tailY - L * 0.02f), Offset(back - L * 0.002f, tailY - L * 0.014f), L * 0.006f, StrokeCap.Round)
        }
        LampStyle.ROUND -> {
            drawCircle(Color(0xFF1B1E22), L * 0.022f, Offset(front + L * 0.025f, headY))
            drawCircle(warm, L * 0.016f, Offset(front + L * 0.025f, headY))
            drawCircle(Color.White, L * 0.006f, Offset(front + L * 0.021f, headY - L * 0.005f))
            drawRoundRect(red, Offset(back - L * 0.016f, tailY - L * 0.02f), Size(L * 0.012f, L * 0.04f), CornerRadius(L * 0.006f))
        }
    }
    glow(Offset(front + L * 0.02f, headY), L * 0.035f, warm.mix(light, 0.3f))
    glow(Offset(back - L * 0.015f, tailY), L * 0.025f, red)
}

private fun DrawScope.drawRealWheel(c: Offset, r: Float, caliper: Color?, sporty: Boolean) {
    // 타이어 (측면 음영)
    drawCircle(Brush.radialGradient(0.62f to Color(0xFF2A2C30), 0.85f to Color(0xFF161719), 1f to Color(0xFF0A0A0B), center = c, radius = r), r, c)
    drawCircle(Color.White.copy(alpha = 0.06f), r * 0.93f, c, style = Stroke(r * 0.03f))
    val rim = r * 0.7f
    // 림 안쪽(배럴) 어둡게 + 브레이크 디스크
    drawCircle(Color(0xFF1A1B1E), rim, c)
    drawCircle(Brush.radialGradient(listOf(Color(0xFF8A8F96), Color(0xFF55595F)), center = c, radius = rim * 0.78f), rim * 0.78f, c)
    caliper?.let { drawArc(it, 195f, 60f, false, Offset(c.x - rim * 0.62f, c.y - rim * 0.62f), Size(rim * 1.24f, rim * 1.24f), style = Stroke(rim * 0.2f, cap = StrokeCap.Round)) }
    // 스포크 (알로이)
    val spokes = if (sporty) 10 else 5
    val silver = Color(0xFFD9DDE2)
    for (k in 0 until spokes) {
        val ang = Math.toRadians(k * 360.0 / spokes - 90)
        val dx = cos(ang).toFloat(); val dy = sin(ang).toFloat()
        val w = if (sporty) rim * 0.11f else rim * 0.2f
        drawLine(Color(0xFF6E737A), Offset(c.x + dx * rim * 0.2f + dy * w * 0.25f, c.y + dy * rim * 0.2f - dx * w * 0.25f), Offset(c.x + dx * rim * 0.95f + dy * w * 0.25f, c.y + dy * rim * 0.95f - dx * w * 0.25f), w, StrokeCap.Round)
        drawLine(silver, Offset(c.x + dx * rim * 0.2f, c.y + dy * rim * 0.2f), Offset(c.x + dx * rim * 0.95f, c.y + dy * rim * 0.95f), w * 0.8f, StrokeCap.Round)
    }
    // 림 테두리 + 허브
    drawCircle(Brush.sweepGradient(listOf(Color(0xFFF2F4F6), Color(0xFF8E949B), Color(0xFFF2F4F6), Color(0xFF8E949B), Color(0xFFF2F4F6)), center = c), rim, c, style = Stroke(rim * 0.09f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFF5F6F7), Color(0xFF7D838A)), center = Offset(c.x - rim * 0.05f, c.y - rim * 0.05f), radius = rim * 0.2f), rim * 0.18f, c)
    // 위쪽 하이라이트 (빛 방향)
    drawArc(Color.White.copy(alpha = 0.18f), 200f, 100f, false, Offset(c.x - r * 0.9f, c.y - r * 0.9f), Size(r * 1.8f, r * 1.8f), style = Stroke(r * 0.05f, cap = StrokeCap.Round))
}

private fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)
private fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)
private fun Color.mix(o: Color, t: Float) = Color(red + (o.red - red) * t, green + (o.green - green) * t, blue + (o.blue - blue) * t, alpha)
