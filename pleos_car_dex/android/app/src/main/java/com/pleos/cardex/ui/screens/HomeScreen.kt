package com.pleos.cardex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.cardex.pleos.NaviPlace
import com.pleos.cardex.ui.UiState
import com.pleos.cardex.ui.components.ShowroomStage

/** 메인 화면 추천 한 칸 */
data class HomePick(val icon: String, val title: String, val sub: String = "", val onClick: () -> Unit)

/**
 * 메인 화면: 위에는 내비 목적지 검색 + 추천, 가운데는 내 대표 차량이 있는 쇼룸.
 * 도감·수집·퀴즈·여행·랭킹·설정은 ☰ 메뉴로 (화면을 깔끔하게).
 */
@Composable
fun HomeScreen(
    state: UiState,
    onMenu: () -> Unit,
    onSearch: (String) -> Unit,
    onPlace: (NaviPlace) -> Unit,
    onVoice: () -> Unit,
    onOpen: (String) -> Unit, // 메뉴 경로 (collection, showroom, travel, scan …)
    animate: Boolean = true,
) {
    var query by remember { mutableStateOf("") }
    val picks = buildList {
        state.naviPlaces.forEach { p ->
            add(HomePick(when (p.kind) { "home" -> "🏠"; "work" -> "🏢"; "bookmark" -> "⭐"; else -> "🕘" }, p.name, p.address.take(14)) { onPlace(p) })
        }
        state.challenge?.takeIf { it.status == "active" }?.let { ch ->
            add(HomePick("🏁", "코스 이어가기", "${ch.visited.size}/${ch.course.stops.size}곳") { onOpen("travel") })
        }
        state.home?.daily?.takeIf { !it.found }?.let { d -> add(HomePick("📅", "이번 주의 차", "${d.target.model} · +${d.bonus}") { onOpen("collection") }) }
        add(HomePick("🗺️", "AI 추천 드라이브", "새 지역 스탬프") { onOpen("travel") })
        state.landmarks.firstOrNull { !it.collected }?.let { l ->
            add(HomePick(if (l.attraction.kind == "brand") "🏢" else "📍", l.attraction.title, "${"%.1f".format(l.distanceM / 1000f)}km · +${l.points}") { onOpen("travel") })
        }
    }

    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1020), Color(0xFF111827)))).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 상단: 메뉴 + 목적지 검색 ──
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = onMenu, modifier = Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF1C2433))) {
                Icon(Icons.Filled.Menu, "메뉴", tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Row(
                Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(30.dp)).background(Color(0xFF1C2433))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(30.dp)).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, null, tint = Color(0xFF8AB4F8), modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("어디로 갈까요?  주소·장소 검색", color = Color.White.copy(alpha = 0.45f), fontSize = 22.sp)
                    BasicTextField(
                        value = query, onValueChange = { query = it.take(60) }, singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 22.sp), cursorBrush = SolidColor(Color(0xFF8AB4F8)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconButton(onClick = onVoice) { Icon(Icons.Filled.Mic, "음성", tint = if (state.listening) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.8f)) }
            }
        }
        // ── 추천 (최근 목적지·즐겨찾기 + 앱 추천) ──
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            picks.forEach { p ->
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF161D2B)).clickable(onClick = p.onClick).padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(p.icon, fontSize = 22.sp)
                    Column {
                        Text(p.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (p.sub.isNotBlank()) Text(p.sub, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
        // ── 가운데: 내 쇼룸 (대표 차량) ──
        Box(Modifier.fillMaxWidth().weight(1f)) {
            ShowroomStage(state.showroom, Modifier.fillMaxSize(), animate = animate)
            Row(
                Modifier.align(Alignment.BottomEnd).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                state.home?.let { h ->
                    GlassChip("🃏 도감 ${h.dexOwned}/${h.dexTotal}") { onOpen("collection") }
                    h.daily?.let { d -> if (d.streak > 0) GlassChip("🔥 ${d.streak}일" + if (d.greenDays > 0) " · 🌿${d.greenDays}" else "") { onOpen("collection") } }
                }
                GlassChip(if (state.showroom.car == null) "🚗 대표 차량 고르기" else "✨ 쇼룸 꾸미기", strong = true) {
                    onOpen(if (state.showroom.car == null) "collection" else "showroom")
                }
            }
        }
    }
}

@Composable
private fun GlassChip(text: String, strong: Boolean = false, onClick: () -> Unit) {
    Text(
        text, color = if (strong) Color(0xFF0B1020) else Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).background(if (strong) Color(0xFF8AB4F8) else Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 10.dp),
    )
}
