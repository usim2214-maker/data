package com.pleos.cardex.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pleos.cardex.data.CarDexApi
import com.pleos.cardex.data.VehicleCard
import com.pleos.cardex.ui.theme.rarityColor

/**
 * 탭하면 Y축으로 뒤집히는 대형 카드.
 * 앞면: 일러스트(MVP 는 크롭 이미지) + 이름 / 뒷면: 유저 키워드 요약
 */
@Composable
fun FlipCard(card: VehicleCard, modifier: Modifier = Modifier, startFlipped: Boolean = false) {
    var flipped by remember(card.id) { mutableStateOf(startFlipped) }
    val rotation by animateFloatAsState(if (flipped) 180f else 0f, tween(500), label = "flip")
    val accent = rarityColor(card.rarity)

    Card(
        modifier = modifier
            .graphicsLayer { rotationY = rotation; cameraDistance = 16 * density }
            .clickable { flipped = !flipped },
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(3.dp, accent),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        if (rotation <= 90f) {
            CardFront(card, accent)
        } else {
            // 뒷면은 글자가 거꾸로 보이지 않도록 한 번 더 뒤집는다
            Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { CardBack(card, accent) }
        }
    }
}

@Composable
private fun CardFront(card: VehicleCard, accent: Color) {
    Column(Modifier.fillMaxSize()) {
        if (card.imageUrl != null) {
            AsyncImage(
                model = CarDexApi.absolute(card.imageUrl),
                contentDescription = card.model,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Black),
            )
        } else { // 사진이 없으면(시연 데이터 등) 카툰 일러스트
            CarIllustration(card.id, card.bodyType, card.rarity, Modifier.fillMaxWidth().weight(1f).background(Color(0xFF232A36)))
        }
        Column(Modifier.padding(20.dp)) {
            Text(card.rarity.uppercase(), color = accent, style = MaterialTheme.typography.labelLarge)
            Text("${card.brand} ${card.model}", style = MaterialTheme.typography.headlineMedium)
            Text(card.oneLiner, style = MaterialTheme.typography.bodyLarge, color = Color.LightGray)
        }
    }
}

@Composable
private fun CardBack(card: VehicleCard, accent: Color) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${card.brand} ${card.model}", style = MaterialTheme.typography.titleLarge, color = accent)
        HorizontalDivider(color = accent.copy(alpha = 0.4f))
        card.facts.forEach { fact ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(fact.keyword, Modifier.weight(0.35f), style = MaterialTheme.typography.labelLarge, color = Color.Gray)
                Text(fact.value, Modifier.weight(0.65f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
