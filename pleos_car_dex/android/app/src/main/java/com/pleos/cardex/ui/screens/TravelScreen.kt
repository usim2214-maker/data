package com.pleos.cardex.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.cardex.data.Challenge
import com.pleos.cardex.data.CourseStop
import com.pleos.cardex.data.DriveCourse
import com.pleos.cardex.data.HomeSummary
import com.pleos.cardex.data.LandmarkNearby
import com.pleos.cardex.ui.components.HomeSummaryCard

private val HOUR_OPTIONS = listOf(2.0, 4.0, 6.0, 8.0)

/**
 * 여행 탭.
 * 좌측: AI 드라이브 코스 추천 (미획득 지역 스탬프 + 축제 + 명소) -> Pleos 내비 길안내
 * 우측: 주변 히든 스탬프 (TourAPI 명소, Pleos 근접 알림으로 자동 획득)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TravelScreen(
    course: DriveCourse?,
    loading: Boolean,
    landmarks: List<LandmarkNearby>,
    challenge: Challenge?,
    onRecommend: (Double, String) -> Unit,
    onStartChallenge: () -> Unit,
    onResumeNavigation: () -> Unit,
    onAbandon: () -> Unit,
    home: HomeSummary? = null,
) {
    var hours by remember { mutableDoubleStateOf(4.0) }
    var note by remember { mutableStateOf("") }

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(
            Modifier.weight(1.3f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HomeSummaryCard(home)
            challenge?.let { ChallengeCard(it, onResumeNavigation, onAbandon) }
            Text("AI 드라이브 코스", style = MaterialTheme.typography.displaySmall)
            Text("아직 없는 지역 스탬프와 지금 열리는 축제를 엮어 드려요 · 왕복 운전 시간", color = Color.Gray)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HOUR_OPTIONS.forEach { h ->
                    FilterChip(
                        selected = hours == h, onClick = { hours = h },
                        label = { Text("${h.toInt()}시간", fontSize = 20.sp, modifier = Modifier.padding(vertical = 10.dp)) },
                    )
                }
            }
            OutlinedTextField(
                value = note, onValueChange = { note = it.take(100) },
                label = { Text("취향 (예: 바다, 아이와 함께)") }, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onRecommend(hours, note) }, enabled = !loading, modifier = Modifier.height(76.dp)) {
                    Text("코스 추천받기", style = MaterialTheme.typography.labelLarge)
                }
                if (loading) CircularProgressIndicator()
            }
            course?.let { CourseCard(it, onStartChallenge) }
        }

        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("주변 히든 스탬프", style = MaterialTheme.typography.headlineMedium)
            Text("명소 500m 안에 들어가면 자동으로 획득돼요", color = Color.Gray)
            if (landmarks.isEmpty()) Text("📍 위치를 받으면 주변 20km 안의 명소·축제가 여기에 나타나요", color = Color.Gray)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(landmarks, key = { it.attraction.contentId }) { LandmarkRow(it) }
            }
        }
    }
}

@Composable
private fun CourseCard(course: DriveCourse, onStartChallenge: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(course.title, style = MaterialTheme.typography.headlineMedium)
            Text(course.summary, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("운전 약 ${course.estDriveHours}시간") })
                AssistChip(onClick = {}, label = { Text("스탬프 +${course.expectedPoints}") })
                AssistChip(onClick = {}, label = { Text("🏁 완주 +${course.completionBonus}") })
                if (course.candidatesSource == "mock") AssistChip(onClick = {}, label = { Text("시연 데이터") })
            }
            course.limited?.let { lim ->
                Text(
                    "🎖️ 축제 한정: '${lim.title}' 기간(~${lim.eventEnd.drop(4).chunked(2).joinToString("/")}) 안에 완주하면 한정 배지 +50",
                    color = Color(0xFFFF7043), style = MaterialTheme.typography.bodyLarge,
                )
            }
            course.stops.forEachIndexed { i, s -> StopRow(i + 1, s) }
            if (course.stops.size > 3) Text("※ 내비에는 앞의 3곳까지 경로로 보냅니다", color = Color.Gray)
            FilledTonalButton(onClick = onStartChallenge, enabled = course.stops.isNotEmpty(), modifier = Modifier.height(76.dp)) {
                Text("🏁 챌린지 시작 + Pleos 길안내", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun StopRow(index: Int, stop: CourseStop) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$index. ${stop.name}", style = MaterialTheme.typography.titleLarge)
            if (stop.kind == "festival") Badge("축제", Color(0xFFFF7043))
            if (stop.newRegionStamp) Badge("새 지역", Color(0xFF4DA3FF))
            if (stop.newHiddenStamp) Badge("히든", Color(0xFFFFC94D))
        }
        Text(stop.reason, color = Color.Gray)
    }
}

@Composable
private fun Badge(text: String, color: Color) =
    Text(text, color = color, style = MaterialTheme.typography.labelLarge)

@Composable
private fun LandmarkRow(item: LandmarkNearby) {
    val a = item.attraction
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (item.collected) "✅" else if (a.kind == "festival") "🎉" else "📍", style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(a.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (item.distanceM >= 1000) "%.1fkm".format(item.distanceM / 1000.0) else "${item.distanceM}m",
                color = Color.Gray,
            )
        }
        Text(if (item.collected) "획득" else "+${item.points}", color = MaterialTheme.colorScheme.secondary)
    }
}

/** 진행 중/완료된 코스 챌린지 */
@Composable
private fun ChallengeCard(ch: Challenge, onResume: () -> Unit, onAbandon: () -> Unit) {
    val total = ch.course.stops.size
    val done = ch.visited.size
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val head = when (ch.status) {
                "completed" -> "🏁 완주! ${ch.course.title}"
                "expired" -> "⌛ 시간 초과 — ${ch.course.title}"
                else -> "진행 중 챌린지 · ${ch.course.title}"
            }
            Text(head, style = MaterialTheme.typography.headlineMedium)
            androidx.compose.material3.LinearProgressIndicator(
                progress = { if (total == 0) 0f else done / total.toFloat() },
                modifier = Modifier.fillMaxWidth().height(10.dp),
            )
            Text("$done / $total 곳 방문 · 완주 보너스 +${ch.course.completionBonus}", style = MaterialTheme.typography.bodyLarge)
            ch.course.stops.forEach { s ->
                Text((if (s.contentId in ch.visited) "✅ " else "⬜ ") + s.name, style = MaterialTheme.typography.bodyLarge)
            }
            if (ch.status == "active") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onResume, modifier = Modifier.height(76.dp)) { Text("🧭 남은 곳 길안내", fontSize = 22.sp) }
                    androidx.compose.material3.TextButton(onClick = onAbandon, modifier = Modifier.height(76.dp)) { Text("포기", fontSize = 20.sp) }
                }
            }
        }
    }
}
