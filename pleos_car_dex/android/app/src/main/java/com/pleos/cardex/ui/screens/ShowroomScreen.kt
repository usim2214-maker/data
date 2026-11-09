package com.pleos.cardex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.pleos.cardex.data.CarDexApi
import com.pleos.cardex.data.CollectionEntry
import com.pleos.cardex.data.DecorCatalog
import com.pleos.cardex.data.DecorItem
import com.pleos.cardex.data.MAX_PROPS
import com.pleos.cardex.data.Showroom
import com.pleos.cardex.data.StampBook
import com.pleos.cardex.ui.components.CarIllustration
import com.pleos.cardex.ui.components.ShowroomStage
import com.pleos.cardex.ui.components.hexColor
import com.pleos.cardex.ui.theme.rarityColor

private enum class DecorTab(val label: String) {
    Car("차량"), Background("배경"), Floor("바닥"), Light("조명"), Pedestal("전시대"), Sign("간판"), Prop("소품"), Trophy("트로피")
}

/**
 * 쇼룸(차고): 왼쪽 무대 + 오른쪽 꾸미기 패널.
 * 아이템은 여행(스탬프·히든·완주·마스터·축제 배지)으로 해금된다.
 */
@Composable
fun ShowroomScreen(
    showroom: Showroom,
    collection: List<CollectionEntry>,
    stampBook: StampBook?,
    decor: DecorCatalog?,
    onRefresh: () -> Unit,
    onFeature: (String) -> Unit,
    onBackground: (String?) -> Unit,
    onEquip: (String, String) -> Unit,
    onAddProp: (String) -> Unit,
    onMoveProp: (Int, Float, Float, Boolean) -> Unit,
    onResizeProp: (Int) -> Unit,
    onRemoveProp: (Int) -> Unit,
    animate: Boolean = true,
    initialTab: Int = 0,
) {
    LaunchedEffect(Unit) { onRefresh() }
    var tab by remember { mutableIntStateOf(initialTab) }
    var editMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }

    Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.weight(1.55f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ShowroomStage(
                showroom = showroom,
                modifier = Modifier.fillMaxWidth().weight(1f),
                editMode = editMode,
                selectedProp = selected,
                onSelectProp = { selected = it },
                onMoveProp = onMoveProp,
                animate = animate,
            )
            // 편집 바
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(
                    selected = editMode, onClick = { editMode = !editMode; selected = null },
                    label = { Text(if (editMode) "✋ 소품 배치 중 (드래그)" else "✋ 소품 배치", fontSize = 18.sp) },
                )
                Text("소품 ${showroom.props.size}/$MAX_PROPS", color = Color.Gray)
                val sel = selected
                if (editMode && sel != null && sel in showroom.props.indices) {
                    TextButton(onClick = { onResizeProp(sel) }) { Text("크기 ×${showroom.props[sel].scale}") }
                    TextButton(onClick = { onRemoveProp(sel); selected = null }) { Text("삭제", color = Color(0xFFFF6B6B)) }
                }
            }
        }

        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("내 쇼룸", style = MaterialTheme.typography.headlineMedium)
                decor?.let { Text("아이템 ${it.unlocked}/${it.total} 해금", color = MaterialTheme.colorScheme.secondary) }
            }
            if (showroom.titles.isNotEmpty()) {
                Text("👑 ${showroom.titles.joinToString(" · ") { "$it 마스터" }}", color = Color(0xFFFFD54F))
            }
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                DecorTab.entries.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t.label, fontSize = 18.sp) }) }
            }
            val items = decor?.items.orEmpty()
            when (DecorTab.entries[tab]) {
                DecorTab.Car -> CarPicker(collection, showroom.car?.id, onFeature)
                DecorTab.Background -> BackgroundPicker(stampBook, showroom.background?.id, onBackground)
                DecorTab.Floor -> ItemGrid(items.filter { it.slot == "floor" }, showroom.floor?.id) { onEquip("floor", it.id) }
                DecorTab.Light -> ItemGrid(items.filter { it.slot == "light" }, showroom.light?.id) { onEquip("light", it.id) }
                DecorTab.Pedestal -> ItemGrid(items.filter { it.slot == "pedestal" }, showroom.pedestal?.id) { onEquip("pedestal", it.id) }
                DecorTab.Sign -> ItemGrid(items.filter { it.slot == "sign" }, showroom.sign?.id) { onEquip("sign", it.id) }
                DecorTab.Prop -> ItemGrid(items.filter { it.slot == "prop" }, null, columns = 4) { onAddProp(it.id); editMode = true }
                DecorTab.Trophy -> TrophyShelf(showroom, stampBook)
            }
        }
    }
}

@Composable
private fun CarPicker(collection: List<CollectionEntry>, selectedId: String?, onFeature: (String) -> Unit) {
    if (collection.isEmpty()) {
        Text("도감에서 차량을 먼저 획득하세요", color = Color.Gray); return
    }
    LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(collection, key = { it.card.id }) { e ->
            val sel = e.card.id == selectedId
            Column(
                Modifier.clip(RoundedCornerShape(14.dp)).border(if (sel) 3.dp else 1.dp, rarityColor(e.card.rarity), RoundedCornerShape(14.dp))
                    .clickable { onFeature(e.card.id) }.padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CarIllustration(
                    e.card.id, e.card.bodyType, e.card.rarity,
                    Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF232A36)),
                )
                Text(e.card.model, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun BackgroundPicker(stampBook: StampBook?, selectedId: String?, onBackground: (String?) -> Unit) {
    val stamps = stampBook?.stamps.orEmpty()
    LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Tile(selected = selectedId == null, locked = false, onClick = { onBackground(null) }, brush = Brush.verticalGradient(listOf(Color(0xFF1B2440), Color(0xFF2B1F3A)))) {
                Text("🏠", fontSize = 26.sp); Text("기본 차고", fontSize = 14.sp, color = Color.White)
            }
        }
        items(stamps, key = { it.region.id }) { s ->
            val r = s.region
            Tile(
                selected = selectedId == r.id, locked = !s.acquired, onClick = { if (s.acquired) onBackground(r.id) },
                brush = Brush.verticalGradient(r.colors.map(::hexColor)),
            ) {
                Text(if (s.acquired) r.emoji else "🔒", fontSize = 26.sp)
                Text(r.name, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                if (!s.acquired) Text("${r.name} 방문", fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
            }
        }
    }
}

@Composable
private fun ItemGrid(items: List<DecorItem>, selectedId: String?, columns: Int = 3, onPick: (DecorItem) -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(columns), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(items, key = { it.id }) { it ->
            Tile(selected = it.id == selectedId, locked = !it.unlocked, onClick = { if (it.unlocked) onPick(it) }, brush = previewBrush(it)) {
                when (it.slot) {
                    "prop" -> Text(it.key, fontSize = 30.sp)
                    "light" -> Box(Modifier.size(28.dp).clip(CircleShape).background(hexColor(it.key)))
                    "sign" -> Text(it.key.replace("{title}", "OO"), fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, maxLines = 2)
                    "pedestal" -> Text(when (it.key) { "gold" -> "🥇"; "neon" -> "💠"; else -> "⚙️" }, fontSize = 26.sp)
                    else -> {}
                }
                Text(it.name, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                if (!it.unlocked) Text("🔒 ${it.unlockHint}", fontSize = 11.sp, color = Color(0xFFFFC94D), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun previewBrush(item: DecorItem): Brush = when (item.slot) {
    "floor" -> Brush.verticalGradient(
        when (item.key) {
            "asphalt" -> listOf(Color(0xFF2B2E33), Color(0xFF45484E))
            "checker" -> listOf(Color(0xFF111111), Color(0xFF777777))
            "wood" -> listOf(Color(0xFF6D4C33), Color(0xFFA1826A))
            "neon" -> listOf(Color(0xFF0B0F1E), Color(0xFF6A1B9A))
            "marble" -> listOf(Color(0xFFC9A227), Color(0xFFEDE7DC))
            else -> listOf(Color(0xFF4A515C), Color(0xFF7A828F))
        },
    )
    "light" -> Brush.radialGradient(listOf(hexColor(item.key).copy(alpha = 0.45f), Color(0xFF161B22)))
    else -> Brush.verticalGradient(listOf(Color(0xFF232A36), Color(0xFF161B22)))
}

@Composable
private fun Tile(selected: Boolean, locked: Boolean, onClick: () -> Unit, brush: Brush, content: @Composable () -> Unit) {
    Column(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(brush)
            .border(if (selected) 3.dp else 1.dp, if (selected) Color(0xFFFFC94D) else Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .then(if (locked) Modifier.background(Color.Black.copy(alpha = 0.55f)) else Modifier) // 잠김: 어둡게 + 글자는 또렷하게
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun TrophyShelf(showroom: Showroom, stampBook: StampBook?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🎖️ 축제 한정 배지", style = MaterialTheme.typography.titleLarge)
        if (showroom.badges.isEmpty()) Text("축제 코스를 기간 안에 완주하면 받을 수 있어요", color = Color.Gray)
        showroom.badges.forEach { Text("🎖️ ${it.title}", fontSize = 18.sp) }
        Text("✨ 히든 스탬프 ${stampBook?.hidden?.size ?: 0}", style = MaterialTheme.typography.titleLarge)
        stampBook?.hidden.orEmpty().take(6).forEach { Text("✨ ${it.attraction.title}  +${it.points}", fontSize = 16.sp) }
        Text("🗺️ 지역 스탬프 ${stampBook?.acquired ?: 0}/${stampBook?.total ?: 17}", style = MaterialTheme.typography.titleLarge)
    }
}
