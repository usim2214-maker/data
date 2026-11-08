package com.pleos.cardex.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pleos.cardex.data.CarDexApi
import com.pleos.cardex.data.CollectionEntry
import com.pleos.cardex.data.MAX_STICKERS
import com.pleos.cardex.data.Region
import com.pleos.cardex.data.Showroom
import com.pleos.cardex.data.StampBook
import com.pleos.cardex.ui.theme.rarityColor

/**
 * 카트라이더식 쇼룸.
 * 좌측: 대표 차량 전시 무대 (배경 = 획득한 지역 스탬프 테마, 스티커 = 스탬프 최대 3개)
 * 우측: 대표 차량 선택 / 배경 선택 / 스티커 선택 / 스탬프북
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShowroomScreen(
    showroom: Showroom,
    collection: List<CollectionEntry>,
    stampBook: StampBook?,
    onRefresh: () -> Unit,
    onFeature: (String) -> Unit,
    onBackground: (String?) -> Unit,
    onToggleSticker: (String) -> Unit,
) {
    LaunchedEffect(Unit) { onRefresh() }
    val acquired = stampBook?.stamps?.filter { it.acquired }?.map { it.region }.orEmpty()

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        ShowroomStage(showroom, Modifier.weight(1.4f).fillMaxHeight())

        Column(
            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("대표 차량")
            if (collection.isEmpty()) Text("도감에서 차량을 먼저 획득하세요", color = Color.Gray)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(collection, key = { it.card.id }) { e ->
                    val selected = showroom.car?.id == e.card.id
                    AsyncImage(
                        model = CarDexApi.absolute(e.card.imageUrl),
                        contentDescription = e.card.model,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(16.dp))
                            .border(if (selected) 4.dp else 1.dp, rarityColor(e.card.rarity), RoundedCornerShape(16.dp))
                            .clickable { onFeature(e.card.id) },
                    )
                }
            }

            Section("배경 (획득한 지역)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = showroom.background == null, onClick = { onBackground(null) }, label = { Text("기본") })
                acquired.forEach { r ->
                    FilterChip(
                        selected = showroom.background?.id == r.id,
                        onClick = { onBackground(r.id) },
                        label = { Text("${r.emoji} ${r.name}") },
                    )
                }
            }

            Section("스티커 (최대 $MAX_STICKERS)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                acquired.forEach { r ->
                    FilterChip(
                        selected = showroom.stickers.any { it.id == r.id },
                        onClick = { onToggleSticker(r.id) },
                        label = { Text(r.emoji, fontSize = 22.sp) },
                    )
                }
            }

            Section("스탬프북 ${stampBook?.acquired ?: 0}/${stampBook?.total ?: 0}")
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(320.dp),
            ) {
                items(stampBook?.stamps.orEmpty(), key = { it.region.id }) { s ->
                    StampBadge(s.region, s.acquired)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleLarge)

/** 전시 무대: 지역 테마 그라데이션 + 랜드마크 + 스티커 + 대표 차량 */
@Composable
private fun ShowroomStage(showroom: Showroom, modifier: Modifier) {
    val bg = showroom.background
    val brush = Brush.verticalGradient(bg?.colors?.map(::hex) ?: listOf(Color(0xFF1F2937), Color(0xFF0D1117)))

    Box(modifier.clip(RoundedCornerShape(28.dp)).background(brush)) {
        // 배경 랜드마크
        bg?.let {
            Text(it.emoji, fontSize = 160.sp, modifier = Modifier.align(Alignment.TopEnd).padding(24.dp).alpha(0.35f))
            Text(
                "${it.name} · ${it.landmark}",
                color = Color.White, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.align(Alignment.TopStart).padding(24.dp),
            )
        }
        // 스티커: 무대 하단 좌/중/우 고정 위치
        val slots = listOf(Alignment.BottomStart, Alignment.CenterStart, Alignment.BottomEnd)
        showroom.stickers.zip(slots).forEach { (r, slot) ->
            Text(r.emoji, fontSize = 56.sp, modifier = Modifier.align(slot).padding(28.dp))
        }

        val car = showroom.car
        if (car == null) {
            Text("도감에서 대표 차량을 골라 전시하세요", color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                AsyncImage(
                    model = CarDexApi.absolute(car.imageUrl),
                    contentDescription = car.model,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(420.dp).height(260.dp).clip(RoundedCornerShape(24.dp))
                        .border(BorderStroke(4.dp, rarityColor(car.rarity)), RoundedCornerShape(24.dp)),
                )
                // 전시대(턴테이블) 느낌의 받침
                Box(
                    Modifier.offset(y = (-12).dp).width(460.dp).height(28.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                )
                Text("${car.brand} ${car.model}", color = Color.White, style = MaterialTheme.typography.displaySmall)
                Text(car.oneLiner, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun StampBadge(region: Region, acquired: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (acquired) hex(region.colors.first()) else Color(0xFF30363D),
        modifier = Modifier.size(72.dp).alpha(if (acquired) 1f else 0.6f),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(if (acquired) region.emoji else "🔒", fontSize = 22.sp)
            Text(region.name, fontSize = 12.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
            // 미획득 지역은 획득 시 점수를 보여줘 방문 동기 부여
            if (!acquired) Text("+${region.travelPoints}", fontSize = 11.sp, color = Color(0xFFFFC94D))
        }
    }
}

private fun hex(value: String): Color = Color(android.graphics.Color.parseColor(value))
