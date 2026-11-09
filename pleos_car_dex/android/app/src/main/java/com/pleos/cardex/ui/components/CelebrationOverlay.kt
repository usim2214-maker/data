package com.pleos.cardex.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.cardex.ui.Celebration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val CONFETTI = listOf("🎉", "✨", "🎊", "⭐", "🎈", "💫")

/** 큰 성취 축하: 가운데 큰 이모지 + 사방으로 퍼지는 꽃가루. 탭하거나 3.5초 뒤 닫힘 */
@Composable
fun CelebrationOverlay(c: Celebration, onDismiss: () -> Unit, animate: Boolean = true) {
    val pop = remember(c) { Animatable(if (animate) 0f else 1f) }
    val burst = remember(c) { Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(c) {
        if (!animate) return@LaunchedEffect
        launch { pop.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) }
        launch { burst.animateTo(1f, tween(1_200, easing = FastOutSlowInEasing)) }
        delay(3_500); onDismiss()
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(onClick = onDismiss)) {
        val radius = minOf(constraints.maxWidth, constraints.maxHeight) * 0.42f
        repeat(18) { i ->
            val a = Math.toRadians(i * 20.0 + 10)
            val r = radius * burst.value * (0.7f + (i % 3) * 0.15f)
            Text(
                CONFETTI[i % CONFETTI.size], fontSize = 34.sp,
                modifier = Modifier.align(Alignment.Center)
                    .offset { IntOffset((r * cos(a)).roundToInt(), (r * sin(a)).roundToInt()) }
                    .graphicsLayer { alpha = 1f - burst.value * 0.3f; rotationZ = burst.value * 180f * (if (i % 2 == 0) 1 else -1) },
            )
        }
        Column(
            Modifier.align(Alignment.Center).graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                .clip(RoundedCornerShape(28.dp)).background(Color(0xFF161B22)).padding(horizontal = 48.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(c.emoji, fontSize = 96.sp)
            Text(c.title, color = Color(0xFFFFC94D), fontSize = 40.sp, fontWeight = FontWeight.Black)
            Text(c.subtitle, color = Color.White, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
