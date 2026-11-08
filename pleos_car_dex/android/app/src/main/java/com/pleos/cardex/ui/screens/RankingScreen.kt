package com.pleos.cardex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pleos.cardex.data.Leaderboard
import com.pleos.cardex.data.MastersResponse
import com.pleos.cardex.data.RankRow
import com.pleos.cardex.data.Region
import com.pleos.cardex.data.RegionMaster

/**
 * 경쟁 탭: 이번 주 여행 점수 랭킹(전국/현재 지역) + 이번 달 지역 마스터.
 * 속도·주행시간이 아니라 방문·수집 점수로만 경쟁한다.
 */
@Composable
fun RankingScreen(
    weekly: Leaderboard?,
    masters: MastersResponse?,
    currentRegion: Region?,
    onLoad: (String?) -> Unit,
) {
    LaunchedEffect(Unit) { onLoad(weekly?.regionId) }
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("이번 주 랭킹", style = MaterialTheme.typography.displaySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = weekly?.regionId == null, onClick = { onLoad(null) }, label = { Text("전국") })
                currentRegion?.let { r ->
                    FilterChip(selected = weekly?.regionId == r.id, onClick = { onLoad(r.id) }, label = { Text("${r.emoji} ${r.name}") })
                }
            }
            weekly?.let { lb ->
                lb.me?.let { me ->
                    val gap = lb.gapToNext
                    Text(
                        if (gap == null) "🥇 내가 1위! (${me.points}점)" else "내 순위 ${me.rank}위 · ${me.points}점 — 한 계단 위까지 ${gap}점",
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary,
                    )
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(lb.rows, key = { it.userId }) { RankLine(it) }
                }
            }
        }

        Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("지역 마스터 · ${masters?.period ?: ""}", style = MaterialTheme.typography.displaySmall)
            masters?.myTitles?.takeIf { it.isNotEmpty() }?.let {
                Text("👑 내 칭호: ${it.joinToString(", ") { n -> "$n 마스터" }}", style = MaterialTheme.typography.titleLarge)
            }
            Text("그 지역 이번 달 여행 점수 1위가 마스터 — 넘어서면 칭호를 빼앗아요", color = Color.Gray)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 220.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(masters?.masters.orEmpty(), key = { it.region.id }) { MasterCard(it) }
            }
        }
    }
}

@Composable
private fun RankLine(row: RankRow) {
    val medal = when (row.rank) { 1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; else -> "${row.rank}" }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (row.isMe) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(medal, Modifier.width(48.dp), style = MaterialTheme.typography.titleLarge)
        Text(row.nickname, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = if (row.isMe) FontWeight.Bold else null)
        Text("${row.points}점", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun MasterCard(m: RegionMaster) {
    Card(colors = CardDefaults.cardColors(containerColor = if (m.isMe) Color(0xFF3B2F00) else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${m.region.emoji} ${m.region.name}" + if (m.isMe) "  👑" else "", style = MaterialTheme.typography.titleLarge)
            Text(m.master?.let { "마스터: ${it.nickname} (${it.points})" } ?: "마스터 없음 — 첫 방문자가 차지!", color = Color.Gray)
            Text(
                if (m.isMe) "내가 마스터!" else "나 ${m.myPoints}점 · ${m.gap}점 더 모으면 마스터",
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}
