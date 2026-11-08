package com.pleos.cardex.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pleos.cardex.data.CarDexApi
import com.pleos.cardex.data.VehicleCard
import com.pleos.cardex.ui.theme.rarityColor

/** 카트라이더식 '소환된 내 차량' 배지. 소환 시 옆에서 튀어나오는 애니메이션. */
@Composable
fun SummonedCar(card: VehicleCard?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = card != null,
        enter = slideInHorizontally(spring(Spring.DampingRatioMediumBouncy)) { -it } +
            scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        card ?: return@AnimatedVisibility
        val accent = rarityColor(card.rarity)
        Surface(shape = RoundedCornerShape(20.dp), border = BorderStroke(2.dp, accent), tonalElevation = 4.dp) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AsyncImage(
                    model = CarDexApi.absolute(card.imageUrl),
                    contentDescription = card.model,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(CircleShape),
                )
                Column {
                    Text("소환 중", color = accent, style = MaterialTheme.typography.labelLarge)
                    Text("${card.brand} ${card.model}", style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = onDismiss) { Text("해제", color = Color.Gray) }
            }
        }
    }
}
