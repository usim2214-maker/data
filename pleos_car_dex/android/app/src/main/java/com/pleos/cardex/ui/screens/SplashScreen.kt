package com.pleos.cardex.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 앱이 켜지는 동안 보이는 한 장의 그림: 밤의 쇼룸 — 스포트라이트, 빛나는 턴테이블, 도시 실루엣, 로고.
 * 글이나 버튼 없이 깔끔하게. progress 0..1 은 하단 얇은 바.
 */
@Composable
fun SplashScreen(progress: Float = 0f, animate: Boolean = true) {
    val glow = remember { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) { if (animate) glow.animateTo(1f, tween(900)) }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) { drawNightShowroom(glow.value, progress) }
        Column(
            Modifier.align(Alignment.Center).padding(bottom = 40.dp).graphicsLayer { alpha = glow.value },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("CarDex", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("도로 위 모든 차가 나의 도감", color = Color.White.copy(alpha = 0.6f), fontSize = 20.sp)
        }
    }
}

private fun DrawScope.drawNightShowroom(glow: Float, progress: Float) {
    val w = size.width; val h = size.height
    val horizon = h * 0.62f
    // 하늘
    drawRect(Brush.verticalGradient(listOf(Color(0xFF070B18), Color(0xFF141B3A), Color(0xFF2A1F4E)), endY = horizon))
    // 도시 실루엣
    val skyline = Path().apply {
        moveTo(0f, horizon)
        var x = 0f; var i = 0
        val heights = floatArrayOf(0.10f, 0.18f, 0.08f, 0.24f, 0.14f, 0.3f, 0.12f, 0.2f, 0.09f, 0.26f, 0.15f, 0.11f, 0.22f, 0.13f)
        while (x < w) {
            val bw = w * (0.05f + (i % 3) * 0.015f)
            val bh = h * heights[i % heights.size] * 0.9f
            lineTo(x, horizon - bh); lineTo(x + bw, horizon - bh); x += bw; i++
        }
        lineTo(w, horizon); close()
    }
    drawPath(skyline, Color(0xFF0D1226))
    // 바닥 (광택)
    drawRect(Brush.verticalGradient(listOf(Color(0xFF151A2E), Color(0xFF06080F)), startY = horizon, endY = h), Offset(0f, horizon), Size(w, h - horizon))
    // 스포트라이트 두 줄기
    val cx = w / 2
    listOf(-1f, 1f).forEach { s ->
        val beam = Path().apply {
            moveTo(cx + s * w * 0.32f, 0f); lineTo(cx + s * w * 0.36f, 0f)
            lineTo(cx + s * w * 0.03f, horizon + h * 0.16f); lineTo(cx - s * w * 0.16f, horizon + h * 0.16f); close()
        }
        drawPath(beam, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f * glow), Color.White.copy(alpha = 0.02f * glow)), endY = horizon + h * 0.16f))
    }
    // 턴테이블 빛
    val ty = horizon + h * 0.16f
    drawOval(Brush.radialGradient(listOf(Color(0xFF8AB4F8).copy(alpha = 0.55f * glow), Color.Transparent), center = Offset(cx, ty), radius = w * 0.28f),
        Offset(cx - w * 0.28f, ty - h * 0.07f), Size(w * 0.56f, h * 0.14f))
    drawOval(Color(0xFF8AB4F8).copy(alpha = 0.7f * glow), Offset(cx - w * 0.2f, ty - h * 0.045f), Size(w * 0.4f, h * 0.09f), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
    // 로딩 바
    if (progress > 0f) {
        drawRoundRect(Color.White.copy(alpha = 0.12f), Offset(cx - w * 0.12f, h * 0.9f), Size(w * 0.24f, 4f), androidx.compose.ui.geometry.CornerRadius(2f))
        drawRoundRect(Color(0xFF8AB4F8), Offset(cx - w * 0.12f, h * 0.9f), Size(w * 0.24f * progress.coerceIn(0f, 1f), 4f), androidx.compose.ui.geometry.CornerRadius(2f))
    }
}
