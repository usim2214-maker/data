package com.pleos.cardex.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.cardex.data.CatalogVehicle
import com.pleos.cardex.data.CollectionEntry
import com.pleos.cardex.data.HomeSummary
import com.pleos.cardex.data.SynergyScore
import com.pleos.cardex.ui.components.CarIllustration
import com.pleos.cardex.ui.components.FlipCard
import com.pleos.cardex.ui.components.HomeSummaryCard
import com.pleos.cardex.ui.theme.rarityColor

private enum class DexFilter(val label: String) { All("전체"), Owned("수집"), Missing("미발견"), Legendary("전설") }

/**
 * 차량 도감 (앱의 첫 화면).
 * 모은 차량은 카드(앞: 일러스트 / 뒤: 차량 정보 목록에서 내가 고른 항목), 아직 못 만난 차량은 '???' 실루엣 칸.
 */
@Composable
fun CollectionScreen(
    entries: List<CollectionEntry>,
    synergy: SynergyScore?,
    featuredId: String?,
    onRefresh: () -> Unit,
    onFeature: (String) -> Unit,
    onDelete: (String) -> Unit,
    catalog: List<CatalogVehicle> = emptyList(),
    home: HomeSummary? = null,
) {
    LaunchedEffect(Unit) { onRefresh() }
    var filter by remember { mutableStateOf(DexFilter.All) }
    val ownedIds = entries.map { it.card.id }.toSet()
    val missing = catalog.filterNot { it.id in ownedIds }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 230.dp), // 1280px 차량 화면에서 한 줄 4장
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.padding(top = 24.dp)) { HomeSummaryCard(home) } }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DexFilter.entries.forEach { f ->
                        val count = when (f) {
                            DexFilter.All -> catalog.size.takeIf { it > 0 } ?: entries.size
                            DexFilter.Owned -> entries.size
                            DexFilter.Missing -> missing.size
                            DexFilter.Legendary -> catalog.count { it.rarity == "legendary" }
                        }
                        FilterChip(
                            selected = filter == f, onClick = { filter = f },
                            label = { Text("${f.label} $count", fontSize = 18.sp, modifier = Modifier.padding(vertical = 8.dp)) },
                        )
                    }
                }
                synergy?.let { s ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = {}, label = { Text("시너지 ${s.total}") })
                        s.bonuses.take(4).forEach { b -> AssistChip(onClick = {}, label = { Text("${b.name} +${b.points}") }) }
                    }
                }
            }
        }
        val showOwned = filter == DexFilter.All || filter == DexFilter.Owned || filter == DexFilter.Legendary
        val showMissing = filter == DexFilter.All || filter == DexFilter.Missing || filter == DexFilter.Legendary
        val ownedList = entries.filter { filter != DexFilter.Legendary || it.card.rarity == "legendary" }
        val missingList = missing.filter { filter != DexFilter.Legendary || it.rarity == "legendary" }
        if (showOwned) items(ownedList, key = { it.card.id }) { entry ->
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
        if (showMissing) items(missingList, key = { "missing-${it.id}" }) { v -> MissingSlot(v) }
        if (entries.isEmpty() && catalog.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("주행 중 마주친 차량이 여기에 카드로 모여요", style = MaterialTheme.typography.titleLarge, color = Color.Gray)
        }
    }
}

/** 아직 못 만난 차량: 실루엣 + ??? (브랜드만 힌트) */
@Composable
private fun MissingSlot(v: CatalogVehicle) {
    val accent = rarityColor(v.rarity)
    Card(
        Modifier.fillMaxWidth().aspectRatio(0.72f),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(2.dp, accent.copy(alpha = 0.35f)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF12161D)),
    ) {
        Column(Modifier.fillMaxSize()) {
            CarIllustration(v.id, v.bodyType, v.rarity, Modifier.fillMaxWidth().weight(1f).background(Color(0xFF161B22)), silhouette = true)
            Column(Modifier.padding(20.dp)) {
                Text(v.rarity.uppercase(), color = accent.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
                Text("???", color = Color.White.copy(alpha = 0.6f), fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text("${v.brand} · 미발견", color = Color.Gray, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
