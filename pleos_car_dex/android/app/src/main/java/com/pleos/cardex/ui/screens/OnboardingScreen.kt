package com.pleos.cardex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.cardex.ui.components.CarIllustration

private data class Page(val emoji: String, val title: String, val lines: List<String>)

private val PAGES = listOf(
    Page("🚗", "도로 위 차량을 카드로 모아요", listOf(
        "주행 중 마주친 차량을 YOLOv8 이 인식하면 나만의 차량 카드가 생겨요",
        "앞면은 차량 일러스트, 뒷면은 내가 고른 정보(가격·마력 등)를 AI 가 요약",
        "28종 도감을 채우고 전설 차량을 찾아보세요",
    )),
    Page("🏆", "쇼룸에 자랑하고, 새 지역으로", listOf(
        "모은 차량을 쇼룸에 전시하고 아이템으로 차고를 꾸며요",
        "희귀 차량은 새 지역에서 — AI 가 아직 안 가 본 지역·축제로 코스를 추천해요",
        "지역 스탬프와 '지역 마스터' 칭호로 쇼룸 아이템을 열어요",
    )),
    Page("🛡️", "운전이 먼저예요", listOf(
        "주행 중에는 화면이 '주행 모드'로 잠기고 알림은 음성으로 들려드려요",
        "위치는 스탬프 판정과 명소 반경 확인에만 쓰고, 이동 경로는 저장하지 않아요",
        "다음 화면에서 위치 · 주행 상태 · 내비 · 음성 권한을 요청할게요",
    )),
)

/** 첫 실행 안내 3장. 마지막에 '시작하기' -> Pleos 권한 요청 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val p = PAGES[page]
    Row(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0D1117), Color(0xFF1E2A4A)))).padding(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(48.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (page == 0) {
                CarIllustration("hyundai-ioniq-5-n", "CUV", "legendary", Modifier.size(420.dp, 260.dp))
            } else {
                Text(p.emoji, fontSize = 160.sp)
            }
        }
        Column(Modifier.weight(1.3f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("CarDex", color = Color(0xFF4DA3FF), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(p.title, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black)
            p.lines.forEach { Text("• $it", color = Color.White.copy(alpha = 0.88f), fontSize = 22.sp) }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PAGES.indices.forEach { i ->
                    Box(Modifier.size(if (i == page) 28.dp else 12.dp, 12.dp).clip(CircleShape)
                        .background(if (i == page) Color(0xFF4DA3FF) else Color.White.copy(alpha = 0.3f)))
                }
                Box(Modifier.width(24.dp))
                if (page < PAGES.lastIndex) {
                    TextButton(onClick = onFinish) { Text("건너뛰기", fontSize = 20.sp, color = Color.Gray) }
                    Button(onClick = { page++ }, modifier = Modifier.height(72.dp)) { Text("다음", fontSize = 24.sp) }
                } else {
                    Button(onClick = onFinish, modifier = Modifier.height(72.dp)) { Text("시작하기", fontSize = 24.sp) }
                }
            }
        }
    }
}
