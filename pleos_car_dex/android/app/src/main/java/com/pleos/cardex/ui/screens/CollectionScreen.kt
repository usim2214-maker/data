package com.pleos.cardex.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pleos.cardex.data.CollectionEntry
import com.pleos.cardex.data.SynergyScore
import com.pleos.cardex.ui.components.FlipCard

/** 도감: 시너지 점수 + 수집 카드 그리드 (탭=뒤집기, 쇼룸 대표 차량 지정) */
@Composable
fun CollectionScreen(
    entries: List<CollectionEntry>,
    synergy: SynergyScore?,
    featuredId: String?,
    onRefresh: () -> Unit,
    onFeature: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("차량 도감 · ${entries.size}종", style = MaterialTheme.typography.displaySmall)
            synergy?.let {
                Text("시너지 ${it.total}점", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.secondary)
            }
        }
        synergy?.let { s ->
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("차량 ${s.base}") })
                AssistChip(onClick = {}, label = { Text("여행 ${s.travel}") })
                s.bonuses.forEach { b -> AssistChip(onClick = {}, label = { Text("${b.name} +${b.points}") }) }
            }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "아직 수집한 차량이 없어요\n주행 중 마주친 차량이 여기에 카드로 모여요 (차량 인식은 베타)",
                    style = MaterialTheme.typography.titleLarge, color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 230.dp), // 1280px 차량 화면에서 한 줄 4장, 카드 전체가 보이게
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(top = 24.dp),
        ) {
            items(entries, key = { it.card.id }) { entry ->
                Column {
                    FlipCard(entry.card, Modifier.fillMaxWidth().aspectRatio(0.72f))
                    Text("발견 ${entry.seenCount}회", color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onFeature(entry.card.id) }, enabled = featuredId != entry.card.id) {
                            Text(if (featuredId == entry.card.id) "전시 중" else "쇼룸에 전시")
                        }
                        TextButton(onClick = { onDelete(entry.card.id) }) { Text("삭제") }
                    }
                }
            }
        }
    }
}
