package com.pleos.cardex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.cardex.data.TripReport

/** 주차 후 '오늘의 드라이브' 리포트: 거리·시간(Pleos Odometer) + 이번에 모은 카드·스탬프·점수 */
@Composable
fun TripReportCard(r: TripReport, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 760.dp).clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF0F172A), Color(0xFF1E3A8A))))
                .clickable(enabled = false) {}.padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("🅿️ 오늘의 드라이브", color = Color(0xFF93C5FD), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(r.headline, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Big("${r.distanceKm}km", "주행 거리")
                Big("${r.driveMinutes}분", "주행 시간")
                Big("${r.newCards.size}장", "새 카드")
                Big("${r.stamps.size}개", "스탬프")
                Big("+${r.points}", "점수")
            }
            if (r.newCards.isNotEmpty()) Line("🃏", r.newCards.joinToString(" · ") + if (r.newLegendary > 0) "  🌟 전설 ${r.newLegendary}" else "")
            if (r.stamps.isNotEmpty()) Line("📍", r.stamps.joinToString(" · "))
            Line("🔥", "${r.streak}일 연속 (차 쉬는 날 포함)" + if (r.dailyFound) " · 이번 주의 차 발견 ✅" else "")
            if (r.greenNew > 0) Line("🌿", "그동안 차가 ${r.greenNew}일 쉬어서 잎 ${r.greenNew}장 · 누적 ${r.greenDays}일")
            if (r.ecoTip.isNotBlank()) Line("💡", r.ecoTip)
            if (r.nextGoal.isNotBlank()) Line("🎯", r.nextGoal)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("함께 달린 거리 ${r.totalKm}km · ${r.trips}번째 드라이브", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp, modifier = Modifier.weight(1f))
                Button(onClick = onDismiss) { Text("확인", fontSize = 18.sp) }
            }
        }
    }
}

@Composable
private fun Big(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
    }
}

@Composable
private fun Line(icon: String, text: String) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$icon ", fontSize = 20.sp)
        Text(text, color = Color.White, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
