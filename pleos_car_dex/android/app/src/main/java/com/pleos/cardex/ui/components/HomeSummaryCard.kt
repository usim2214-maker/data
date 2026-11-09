package com.pleos.cardex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.cardex.data.DailyMission
import com.pleos.cardex.data.HomeSummary

/** 첫 화면 '한눈에 보기': 점수·순위·수집 현황 + 다음 목표 한 줄 */
@Composable
fun HomeSummaryCard(home: HomeSummary?, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF1E3A8A), Color(0xFF6D28D9))))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (home == null) {
            Text("내 여행 현황을 불러오는 중…", color = Color.White, fontSize = 20.sp); return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("나의 차량 도감", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            home.titles.takeIf { it.isNotEmpty() }?.let { Text("👑 ${it.joinToString(" · ") { t -> "$t 마스터" }}", color = Color(0xFFFFD54F), fontSize = 18.sp) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("도감", "${home.dexOwned}/${home.dexTotal}")
            Stat("전설", "${home.legendaryOwned}/${home.legendaryTotal}")
            Stat("시너지", "${home.synergyTotal}")
            Stat("이번 주", "${home.weeklyRank}위")
            Stat("지역 스탬프", "${home.regionStamps}/${home.regionTotal}")
        }
        home.daily?.let { DailyRow(it) }
        // 오늘의 차가 목표일 땐 위 줄과 중복이므로 생략
        if (home.daily == null || !home.nextGoal.startsWith("오늘의 차")) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.25f)).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("🎯 ", fontSize = 22.sp)
            Text(home.nextGoal, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** 오늘의 차: 찾기 전엔 실루엣, 찾으면 일러스트 + 연속 출석 */
@Composable
private fun DailyRow(d: DailyMission) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.12f)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CarIllustration(
            d.target.id, d.target.bodyType, d.target.rarity,
            Modifier.size(width = 120.dp, height = 72.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFFE2E8F0)),
            silhouette = !d.found,
        )
        Column(Modifier.weight(1f)) {
            Text("📅 오늘의 차", color = Color(0xFFFFD54F), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(
                if (d.found) "${d.target.brand} ${d.target.model} 발견 완료 ✅" else "${d.target.brand} ${d.target.model} 를 찾아보세요 (+${d.bonus})",
                color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔥 ${d.streak}일", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
            Text("연속 · 최고 ${d.bestStreak}", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp)
    }
}
