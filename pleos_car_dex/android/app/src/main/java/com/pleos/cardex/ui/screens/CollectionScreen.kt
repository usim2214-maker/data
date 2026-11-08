package com.pleos.cardex.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.pleos.cardex.ui.components.FlipCard

/** 도감: 수집한 차량 카드 그리드 (탭하면 뒤집혀 키워드 요약 확인) */
@Composable
fun CollectionScreen(entries: List<CollectionEntry>, onRefresh: () -> Unit, onDelete: (String) -> Unit) {
    LaunchedEffect(Unit) { onRefresh() }
    Column(Modifier.fillMaxSize().padding(32.dp)) {
        Text("차량 도감 · ${entries.size}종", style = MaterialTheme.typography.displaySmall)
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("아직 수집한 차량이 없어요", style = MaterialTheme.typography.titleLarge, color = Color.Gray)
            }
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 300.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(top = 24.dp),
        ) {
            items(entries, key = { it.card.id }) { entry ->
                Column {
                    FlipCard(entry.card, Modifier.fillMaxWidth().aspectRatio(0.72f))
                    Text("발견 ${entry.seenCount}회", color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
                    TextButton(onClick = { onDelete(entry.card.id) }) { Text("삭제") }
                }
            }
        }
    }
}
