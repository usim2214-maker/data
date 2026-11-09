package com.pleos.cardex.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.cardex.data.VehicleCard
import com.pleos.cardex.ui.theme.rarityColor

/**
 * 탭하면 Y축으로 뒤집히는 대형 카드.
 * 앞면: 카툰 일러스트 + 이름 / 뒷면: 차량 정보 목록에서 사용자가 고른 항목
 * flipped 를 넘기면 외부에서 앞/뒤를 제어 (시연 등), null 이면 탭으로 뒤집기
 */
@Composable
fun FlipCard(
    card: VehicleCard, modifier: Modifier = Modifier, startFlipped: Boolean = false, flipped: Boolean? = null,
    onColor: ((String?) -> Unit)? = null,
) {
    var tapped by remember(card.id) { mutableStateOf(startFlipped) }
    val showBack = flipped ?: tapped
    val rotation by animateFloatAsState(if (showBack) 180f else 0f, tween(600), label = "flip")
    val accent = rarityColor(card.rarity)

    Card(
        modifier = modifier
            .graphicsLayer { rotationY = rotation; cameraDistance = 16 * density }
            .clickable { tapped = !tapped },
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(3.dp, accent),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        if (rotation <= 90f) {
            CardFront(card, accent, onColor)
        } else {
            // 뒷면은 글자가 거꾸로 보이지 않도록 한 번 더 뒤집는다
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { CardBack(card, accent) }
        }
    }
}

@Composable
private fun CardFront(card: VehicleCard, accent: Color, onColor: ((String?) -> Unit)? = null) {
    Column(Modifier.fillMaxSize()) {
        // 앞면: 실제 차 (제조사 공식 이미지, 드래그로 360° 회전)
        CarIllustration(
            card.id, card.bodyType, card.rarity, interactive = true, colorCode = card.colorCode, seenColor = card.seenColor, onColor = onColor,
            modifier = Modifier.fillMaxWidth().weight(1f)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), Color(0xFF161B22)))),
        )
        Column(Modifier.padding(20.dp)) {
            Text(
                card.rarity.uppercase() + (if (card.isMyCar) "  ·  🚗 MY CAR" else "") +
                    when (card.eco) { "ev" -> "  ·  ⚡ EV"; "eco_option" -> "  ·  🌿 친환경 트림"; else -> "" },
                color = accent, style = MaterialTheme.typography.labelLarge,
            )
            Text("${card.brand} ${card.model}", style = MaterialTheme.typography.headlineMedium)
            Text(card.oneLiner, style = MaterialTheme.typography.bodyLarge, color = Color.LightGray)
        }
    }
}

@Composable
private fun CardBack(card: VehicleCard, accent: Color) {
    Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${card.brand} ${card.model}", style = MaterialTheme.typography.titleLarge, color = accent)
        Text("📋 내가 고른 정보", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
        HorizontalDivider(color = accent.copy(alpha = 0.4f))
        card.facts.forEach { fact ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(fact.keyword, Modifier.weight(0.35f), style = MaterialTheme.typography.labelLarge, color = Color.Gray)
                Text(fact.value, Modifier.weight(0.65f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
        }
        if (card.isMyCar) Text("🚗 내 차와 같은 모델이에요", color = Color(0xFF7CE38B), style = MaterialTheme.typography.labelLarge)
        if (card.vsMyCar.isNotEmpty()) {
            HorizontalDivider(color = accent.copy(alpha = 0.25f))
            Text("🚗 내 차와 비교", style = MaterialTheme.typography.labelLarge, color = Color.Gray)
            card.vsMyCar.forEach { Text(it, fontSize = 17.sp, color = Color(0xFFB3E5FC), fontWeight = FontWeight.SemiBold) }
        }
    }
}
