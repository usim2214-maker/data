package com.pleos.cardex.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pleos.cardex.data.CarDexApi
import com.pleos.cardex.data.Showroom
import com.pleos.cardex.ui.theme.rarityColor
import kotlin.math.roundToInt

/**
 * 쇼룸 무대 (자체 디자인).
 * 레이어: 하늘(지역 색) → 지역 풍경 실루엣 → 차고 바닥(원근) → 스포트라이트 → 전시대(회전) → 차량 → 네온 간판 → 소품
 * editMode 이면 소품을 드래그로 옮기고, 탭하면 선택(크기/삭제).
 */
@Composable
fun ShowroomStage(
    showroom: Showroom,
    modifier: Modifier = Modifier,
    editMode: Boolean = false,
    selectedProp: Int? = null,
    onSelectProp: (Int?) -> Unit = {},
    onMoveProp: (Int, Float, Float, Boolean) -> Unit = { _, _, _, _ -> },
    animate: Boolean = true,
) {
    val spin = if (animate) {
        val t = rememberInfiniteTransition(label = "stage")
        val v by t.animateFloat(0f, 360f, infiniteRepeatable(tween(12_000, easing = LinearEasing)), label = "spin")
        v
    } else 30f
    val glow = if (animate) {
        val t = rememberInfiniteTransition(label = "glow")
        val v by t.animateFloat(0.75f, 1f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "glow")
        v
    } else 1f

    val bg = showroom.background
    val sky = bg?.colors?.map(::hexColor) ?: listOf(Color(0xFF1B2440), Color(0xFF2B1F3A))
    val light = showroom.light?.key?.let(::hexColor) ?: Color.White
    val floorKey = showroom.floor?.key ?: "concrete"
    val pedestalKey = showroom.pedestal?.key ?: "basic"
    val accent = showroom.car?.let { rarityColor(it.rarity) } ?: Color(0xFF4DA3FF)

    val ctx = androidx.compose.ui.platform.LocalContext.current
    val floorTex = androidx.compose.runtime.remember(floorKey) { ShowroomAssets.floorTexture(ctx, floorKey) }

    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .pointerInputIf(editMode) { detectTapGestures { onSelectProp(null) } },
    ) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val density = LocalDensity.current

        Canvas(Modifier.fillMaxSize()) {
            val horizon = size.height * 0.56f
            drawRect(Brush.verticalGradient(sky + listOf(sky.last().darken(0.5f)), endY = horizon))
            drawScene(bg?.scene ?: "garage", horizon, sky.first())
            drawBackWall(horizon)
            drawFloor(floorKey, horizon, glow, floorTex)
            drawSpotlights(light, horizon, glow)
            drawPedestal(pedestalKey, horizon, spin, accent, glow)
        }

        // 대표 차량: 제조사 공식 이미지 (턴테이블과 함께 회전)
        showroom.car?.let { car ->
            val horizonPx = hPx * 0.56f
            val groundPx = horizonPx + (hPx - horizonPx) * 0.48f
            val boxW = wPx * 0.66f; val boxH = boxW * 0.52f
            Box(
                Modifier.offset { IntOffset(((wPx - boxW) / 2).roundToInt(), (groundPx - boxH * 0.86f).roundToInt()) }
                    .size(with(density) { boxW.toDp() }, with(density) { boxH.toDp() }),
            ) {
                OfficialCarView(
                    car.id, carStyleFor(car.id, car.bodyType, car.rarity), Modifier.fillMaxSize(),
                    turn = if (animate) spin / 360f else null, showHint = false,
                )
            }
        }

        // 네온 간판
        NeonSign(showroom.signText, light, glow, Modifier.align(Alignment.TopCenter).padding(top = 18.dp))

        // 대표 차량: 전시대 위에는 일러스트(캔버스), 실제 도감 사진은 벽 액자, 이름은 번호판
        val car = showroom.car
        if (car != null) {
            Box(Modifier.align(Alignment.BottomCenter).offset(y = with(density) { (-hPx * 0.06f).toDp() })) {
                PlateText("${car.brand} ${car.model}", accent)
            }
            if (car.imageUrl != null) {
                Box(
                    Modifier.align(Alignment.CenterStart).padding(start = 22.dp).offset(y = with(density) { (-hPx * 0.12f).toDp() })
                        .graphicsLayer { rotationZ = -4f }
                        .background(Color(0xFFF5F0E6), RoundedCornerShape(4.dp)).padding(6.dp, 6.dp, 6.dp, 18.dp),
                ) {
                    AsyncImage(
                        model = CarDexApi.absolute(car.imageUrl), contentDescription = car.model, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(with(density) { (wPx * 0.13f).toDp() }, with(density) { (wPx * 0.09f).toDp() }).background(Color.DarkGray),
                    )
                }
            }
        } else {
            Text(
                "도감에서 대표 차량을 골라 전시하세요", color = Color.White.copy(alpha = 0.9f), fontSize = 20.sp,
                modifier = Modifier.align(Alignment.Center).offset(y = with(density) { (hPx * 0.08f).toDp() }),
            )
        }

        if (showroom.backgroundMastered && bg != null) {
            Text(
                "👑 ${bg.name} MASTER", color = Color(0xFFFFD54F), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(18.dp),
            )
        }
        bg?.let {
            Text(
                "${it.name} · ${it.landmark}", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(18.dp),
            )
        }

        // 소품 (편집 모드에서 드래그)
        showroom.props.forEachIndexed { i, p ->
            val sizePx = with(density) { (54.dp * p.scale).toPx() }
            val selected = editMode && selectedProp == i
            val latest by rememberUpdatedState(p)
            Box(
                Modifier
                    .offset { IntOffset((latest.x * wPx - sizePx / 2).roundToInt(), (latest.y * hPx - sizePx / 2).roundToInt()) }
                    .size(with(density) { sizePx.toDp() })
                    .then(if (selected) Modifier.border(2.dp, Color(0xFFFFC94D), RoundedCornerShape(12.dp)) else Modifier)
                    .pointerInputIf(editMode, i) {
                        detectDragGestures(
                            onDragStart = { onSelectProp(i) },
                            onDragEnd = { onMoveProp(i, latest.x, latest.y, true) },
                        ) { change, drag ->
                            change.consume()
                            onMoveProp(i, latest.x + drag.x / wPx, latest.y + drag.y / hPx, false)
                        }
                    }
                    .pointerInputIf(editMode, "tap$i") { detectTapGestures { onSelectProp(i) } },
                contentAlignment = Alignment.Center,
            ) {
                EmojiIcon(p.item.key, (44 * p.scale).dp)
            }
        }
    }
}

// ---------------- 텍스트 요소 ----------------
@Composable
private fun NeonSign(text: String, color: Color, glow: Float, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.45f))
            .border(2.dp, color.copy(alpha = 0.9f * glow), RoundedCornerShape(14.dp))
            .padding(horizontal = 22.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = TextStyle(
                color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp,
                shadow = Shadow(color = color.copy(alpha = glow), blurRadius = 24f),
            ),
        )
    }
}

@Composable
private fun PlateText(text: String, accent: Color) {
    Box(
        Modifier.padding(top = 10.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.6f))
            .border(1.dp, accent, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------- 그리기 ----------------
private fun DrawScope.drawScene(scene: String, horizon: Float, sky: Color) {
    val far = sky.darken(0.45f)
    val near = sky.darken(0.7f)
    val w = size.width
    when (scene) {
        "city" -> {
            val heights = listOf(0.30f, 0.45f, 0.25f, 0.55f, 0.35f, 0.62f, 0.28f, 0.48f, 0.38f, 0.52f, 0.3f, 0.42f)
            val bw = w / heights.size
            heights.forEachIndexed { i, h ->
                val top = horizon - horizon * h * 0.7f
                drawRect(if (i % 2 == 0) far else near, Offset(i * bw, top), Size(bw * 0.86f, horizon - top))
                for (row in 0 until 6) for (col in 0 until 2) {
                    if ((i + row + col) % 3 == 0) drawRect(
                        Color(0xFFFFE082).copy(alpha = 0.55f),
                        Offset(i * bw + bw * (0.18f + col * 0.38f), top + 14f + row * 22f), Size(bw * 0.16f, 8f),
                    )
                }
            }
        }
        "mountain", "fortress" -> {
            ridge(horizon, listOf(0f to 0.55f, 0.18f to 0.2f, 0.35f to 0.5f, 0.55f to 0.12f, 0.75f to 0.45f, 0.9f to 0.25f, 1f to 0.5f), far)
            ridge(horizon, listOf(0f to 0.75f, 0.25f to 0.45f, 0.5f to 0.7f, 0.7f to 0.4f, 1f to 0.7f), near)
            if (scene == "fortress") {
                val wallTop = horizon - horizon * 0.14f
                drawRect(near.darken(0.3f), Offset(0f, wallTop), Size(w, horizon - wallTop))
                var x = 0f
                while (x < w) { drawRect(near.darken(0.3f), Offset(x, wallTop - 14f), Size(22f, 14f)); x += 44f }
            }
        }
        "sea", "island", "bridge", "lake" -> {
            if (scene == "island") ridge(horizon * 0.86f, listOf(0.25f to 1f, 0.5f to 0.35f, 0.75f to 1f), far)
            if (scene == "lake") ridge(horizon * 0.82f, listOf(0f to 0.6f, 0.3f to 0.3f, 0.6f to 0.55f, 1f to 0.35f), far)
            val seaTop = horizon * 0.82f
            drawRect(Brush.verticalGradient(listOf(far, near), startY = seaTop, endY = horizon), Offset(0f, seaTop), Size(w, horizon - seaTop))
            for (k in 0 until 5) {
                val y = seaTop + (horizon - seaTop) * (k + 1) / 6f
                drawLine(Color.White.copy(alpha = 0.15f), Offset(w * 0.1f * k, y), Offset(w * (0.3f + 0.1f * k), y), 2f)
            }
            if (scene == "bridge") {
                val deck = seaTop - 10f
                drawLine(near, Offset(0f, deck), Offset(w, deck), 8f)
                listOf(0.3f, 0.7f).forEach { px ->
                    drawLine(near, Offset(w * px, deck), Offset(w * px, deck - horizon * 0.35f), 10f)
                }
                val cable = Path().apply {
                    moveTo(0f, deck - horizon * 0.1f)
                    quadraticTo(w * 0.15f, deck - horizon * 0.05f, w * 0.3f, deck - horizon * 0.35f)
                    quadraticTo(w * 0.5f, deck - 6f, w * 0.7f, deck - horizon * 0.35f)
                    quadraticTo(w * 0.85f, deck - horizon * 0.05f, w, deck - horizon * 0.1f)
                }
                drawPath(cable, Color(0xFFFF80AB).copy(alpha = 0.8f), style = Stroke(4f))
            }
        }
        "hanok" -> {
            ridge(horizon, listOf(0f to 0.6f, 0.4f to 0.35f, 1f to 0.55f), far)
            listOf(0.2f, 0.5f, 0.8f).forEach { cx ->
                val roofY = horizon - horizon * 0.28f
                val half = w * 0.13f
                val roof = Path().apply {
                    moveTo(w * cx - half, roofY + 18f)
                    quadraticTo(w * cx, roofY - 26f, w * cx + half, roofY + 18f)
                    lineTo(w * cx + half * 0.8f, roofY + 30f); lineTo(w * cx - half * 0.8f, roofY + 30f); close()
                }
                drawPath(roof, near)
                drawRect(near.darken(0.2f), Offset(w * cx - half * 0.7f, roofY + 30f), Size(half * 1.4f, horizon - roofY - 30f))
            }
        }
        "field" -> {
            ridge(horizon, listOf(0f to 0.4f, 0.5f to 0.25f, 1f to 0.45f), far)
            for (k in 0 until 6) {
                val y = horizon - horizon * 0.22f + k * horizon * 0.035f
                drawLine(Color(0xFF2E7D32).copy(alpha = 0.7f), Offset(0f, y), Offset(w, y + 12f), 10f, StrokeCap.Round)
            }
        }
        else -> { // 기본 차고: 셔터 문
            for (k in 0 until 10) {
                val y = horizon * 0.25f + k * horizon * 0.07f
                drawLine(Color.White.copy(alpha = 0.06f), Offset(w * 0.15f, y), Offset(w * 0.85f, y), 6f)
            }
        }
    }
}

private fun DrawScope.ridge(base: Float, pts: List<Pair<Float, Float>>, color: Color) {
    val path = Path().apply {
        moveTo(0f, base)
        pts.forEach { (x, h) -> lineTo(size.width * x, base - base * (1f - h) * 0.55f) }
        lineTo(size.width, base); close()
    }
    drawPath(path, color)
}

/** 차고 뒷벽 하단 띠 (풍경과 바닥 경계) */
private fun DrawScope.drawBackWall(horizon: Float) {
    drawRect(Color.Black.copy(alpha = 0.35f), Offset(0f, horizon - 6f), Size(size.width, 10f))
}

private fun DrawScope.drawFloor(key: String, horizon: Float, glow: Float, texture: androidx.compose.ui.graphics.ImageBitmap? = null) {
    val w = size.width; val h = size.height
    val floor = Path().apply { moveTo(w * 0.18f, horizon); lineTo(w * 0.82f, horizon); lineTo(w * 1.2f, h); lineTo(-w * 0.2f, h); close() }
    val base = when (key) {
        "asphalt" -> Color(0xFF2B2E33)
        "checker" -> Color(0xFF1E1E1E)
        "wood" -> Color(0xFF8D6E4F)
        "neon" -> Color(0xFF0B0F1E)
        "marble" -> Color(0xFFEDE7DC)
        else -> Color(0xFF5C6370)
    }
    drawRect(base.darken(0.25f), Offset(0f, horizon), Size(w, h - horizon)) // 바닥 바깥
    drawPath(floor, Brush.verticalGradient(listOf(base.darken(0.35f), base), startY = horizon, endY = h))
    if (texture != null) {
        // 실사 질감(Poly Haven CC0)을 원근 변환으로 바닥 사다리꼴에 타일링
        val tw = texture.width.toFloat(); val th = texture.height.toFloat()
        val cols = 4f; val rows = 3f
        val m = android.graphics.Matrix().apply {
            setPolyToPoly(
                floatArrayOf(0f, 0f, tw * cols, 0f, tw * cols, th * rows, 0f, th * rows), 0,
                floatArrayOf(w * 0.18f, horizon, w * 0.82f, horizon, w * 1.2f, h, -w * 0.2f, h), 0, 4,
            )
        }
        val shader = android.graphics.BitmapShader(
            texture.asAndroidBitmap(), android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT,
        ).apply { setLocalMatrix(m) }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        // 같은 원근 평면을 바닥 전체 폭으로 (양옆 빈 삼각형 없이)
        drawContext.canvas.nativeCanvas.drawRect(0f, horizon, w, h, paint)
        val all = Offset(0f, horizon); val allSize = Size(w, h - horizon)
        if (key == "marble") drawRect(Color(0xFFFFD54F).copy(alpha = 0.12f), all, allSize) // 골드 마블 톤
        // 원근 깊이감: 안쪽(지평선)일수록 어둡게 + 앞쪽 하이라이트(광택)
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent), startY = horizon, endY = horizon + (h - horizon) * 0.6f), all, allSize)
        drawPath(floor, Brush.radialGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent), center = Offset(w / 2, horizon + (h - horizon) * 0.55f), radius = w * 0.45f))
    }
    clipPath(floor) {
        fun lerpX(t: Float, side: Float) = w * (0.5f + (side - 0.5f) * (0.64f + 0.76f * t)) // 원근 x
        fun yAt(t: Float) = horizon + (h - horizon) * t
        when (key) {
            "checker" -> {
                val rows = 12; val cols = 14
                for (r in 0 until rows) for (c in 0 until cols) if ((r + c) % 2 == 0) {
                    val t0 = (r / rows.toFloat()).let { it * it }; val t1 = ((r + 1) / rows.toFloat()).let { it * it }
                    val p = Path().apply {
                        moveTo(lerpX(t0, c / cols.toFloat()), yAt(t0)); lineTo(lerpX(t0, (c + 1) / cols.toFloat()), yAt(t0))
                        lineTo(lerpX(t1, (c + 1) / cols.toFloat()), yAt(t1)); lineTo(lerpX(t1, c / cols.toFloat()), yAt(t1)); close()
                    }
                    drawPath(p, Color(0xFFD9D9D9).copy(alpha = 0.85f))
                }
            }
            "asphalt" -> {
                for (k in 0 until 8) {
                    val t0 = (k / 8f).let { it * it }; val t1 = ((k + 0.5f) / 8f).let { it * it }
                    drawLine(Color(0xFFFFD54F), Offset(lerpX(t0, 0.5f), yAt(t0)), Offset(lerpX(t1, 0.5f), yAt(t1)), 3f + 10f * t1)
                }
                listOf(0.08f, 0.92f).forEach { s -> drawLine(Color.White.copy(alpha = 0.8f), Offset(lerpX(0f, s), yAt(0f)), Offset(lerpX(1f, s), yAt(1f)), 6f) }
            }
            "wood" -> if (texture == null) for (k in 1 until 14) {
                val s = k / 14f
                drawLine(Color.Black.copy(alpha = 0.18f), Offset(lerpX(0f, s), yAt(0f)), Offset(lerpX(1f, s), yAt(1f)), 3f)
            }
            "neon" -> {
                val c1 = Color(0xFF00E5FF).copy(alpha = 0.7f * glow); val c2 = Color(0xFFFF4081).copy(alpha = 0.6f * glow)
                for (k in 0..12) { val s = k / 12f; drawLine(c1, Offset(lerpX(0f, s), yAt(0f)), Offset(lerpX(1f, s), yAt(1f)), 2.5f) }
                for (k in 1..8) { val t = (k / 8f).let { it * it }; drawLine(c2, Offset(0f, yAt(t)), Offset(w, yAt(t)), 2.5f) }
            }
            "marble" -> for (k in 0 until 7) {
                val s = k / 7f
                drawLine(Color(0xFFC9A227).copy(alpha = 0.55f), Offset(lerpX(0f, s), yAt(0f)), Offset(lerpX(1f, s + 0.12f), yAt(1f)), 2f)
            }
            else -> if (texture == null) for (k in 1 until 6) {
                val t = (k / 6f).let { it * it }
                drawLine(Color.Black.copy(alpha = 0.12f), Offset(0f, yAt(t)), Offset(w, yAt(t)), 2f)
            }
        }
    }
}

private fun DrawScope.drawSpotlights(color: Color, horizon: Float, glow: Float) {
    val w = size.width; val h = size.height
    val target = Offset(w / 2, horizon + (h - horizon) * 0.45f)
    listOf(Offset(w * 0.12f, 0f), Offset(w * 0.88f, 0f)).forEach { src ->
        val cone = Path().apply {
            moveTo(src.x - 10f, src.y); lineTo(src.x + 10f, src.y)
            lineTo(target.x + w * 0.2f, target.y); lineTo(target.x - w * 0.2f, target.y); close()
        }
        drawPath(cone, Brush.verticalGradient(listOf(color.copy(alpha = 0.32f * glow), Color.Transparent), endY = target.y))
    }
    drawOval(
        Brush.radialGradient(listOf(color.copy(alpha = 0.35f * glow), Color.Transparent), center = target, radius = w * 0.3f),
        topLeft = Offset(target.x - w * 0.3f, target.y - h * 0.09f), size = Size(w * 0.6f, h * 0.18f),
    )
}

private fun DrawScope.drawPedestal(key: String, horizon: Float, spin: Float, accent: Color, glow: Float) {
    val w = size.width; val h = size.height
    val c = Offset(w / 2, horizon + (h - horizon) * 0.5f)
    val rx = w * 0.27f; val ry = (h - horizon) * 0.2f
    val oval = Rect(c.x - rx, c.y - ry, c.x + rx, c.y + ry)
    when (key) {
        "gold" -> {
            // 아래층(넓음) -> 위층(좁음) 순서로 쌓는다. 맨 위 판이 차량이 놓이는 높이(oval)
            for (k in 2 downTo 0) {
                val inset = (2 - k) * 0f + k * -22f // k=2 가장 넓음
                val dy = k * 20f
                val o = Rect(oval.left + inset, oval.top + dy, oval.right - inset, oval.bottom + dy)
                drawOval(Color(0xFF7A5C00), o.topLeft + Offset(0f, 16f), o.size)
                drawOval(Brush.linearGradient(listOf(Color(0xFFFFE9A8), Color(0xFFC9A227)), start = o.topLeft, end = o.bottomRight), o.topLeft, o.size)
                drawOval(Color.White.copy(alpha = 0.35f), o.topLeft, o.size, style = Stroke(2f))
            }
        }
        "neon" -> {
            drawOval(Color(0xFF101626), oval.topLeft + Offset(0f, 12f), oval.size)
            drawOval(Color(0xFF1B2236), oval.topLeft, oval.size)
            drawOval(accent.copy(alpha = glow), oval.topLeft, oval.size, style = Stroke(6f))
            // 회전하는 점선 링 (원근 타원 위)
            for (k in 0 until 24) {
                val a = Math.toRadians((spin + k * 15.0))
                val p = Offset(c.x + (rx * 0.85f) * kotlin.math.cos(a).toFloat(), c.y + (ry * 0.85f) * kotlin.math.sin(a).toFloat())
                drawCircle(Color(0xFF00E5FF).copy(alpha = if (k % 2 == 0) glow else 0.3f), 5f, p)
            }
        }
        else -> {
            drawOval(Color(0xFF2A2F38), oval.topLeft + Offset(0f, 12f), oval.size)
            drawOval(Brush.radialGradient(listOf(Color(0xFF7A828F), Color(0xFF4A515C)), center = c, radius = rx), oval.topLeft, oval.size)
            drawOval(Color.White.copy(alpha = 0.25f), oval.topLeft, oval.size, style = Stroke(3f))
            for (k in 0 until 12) {
                val a = Math.toRadians((spin + k * 30.0))
                val p1 = Offset(c.x + rx * 0.7f * kotlin.math.cos(a).toFloat(), c.y + ry * 0.7f * kotlin.math.sin(a).toFloat())
                val p2 = Offset(c.x + rx * 0.92f * kotlin.math.cos(a).toFloat(), c.y + ry * 0.92f * kotlin.math.sin(a).toFloat())
                drawLine(Color.White.copy(alpha = 0.18f), p1, p2, 3f)
            }
        }
    }
}

// ---------------- 유틸 ----------------
fun hexColor(value: String): Color = runCatching { Color(android.graphics.Color.parseColor(value)) }.getOrDefault(Color.White)

private fun Color.darken(f: Float) = Color(red * (1 - f), green * (1 - f), blue * (1 - f), alpha)
private fun Color.lighten(f: Float) = Color(red + (1 - red) * f, green + (1 - green) * f, blue + (1 - blue) * f, alpha)

private fun Modifier.pointerInputIf(enabled: Boolean, key: Any = Unit, block: suspend androidx.compose.ui.input.pointer.PointerInputScope.() -> Unit) =
    if (enabled) this.then(Modifier.pointerInput(key, block)) else this
