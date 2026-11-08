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
import com.pleos.cardex.data.CourseStop
import com.pleos.cardex.data.DriveCourse
import com.pleos.cardex.data.LandmarkNearby

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
    onRecommend: (Double, String) -> Unit,
    onNavigate: () -> Unit,
) {
    var hours by remember { mutableDoubleStateOf(4.0) }
    var note by remember { mutableStateOf("") }

    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(
            Modifier.weight(1.3f).fillMaxHeight().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("AI 드라이브 코스", style = MaterialTheme.typography.displaySmall)
            Text("아직 없는 지역 스탬프와 지금 열리는 축제를 엮어 드려요", color = Color.Gray)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HOUR_OPTIONS.forEach { h ->
                    FilterChip(selected = hours == h, onClick = { hours = h }, label = { Text("왕복 ${h.toInt()}시간") })
                }
            }
            OutlinedTextField(
                value = note, onValueChange = { note = it.take(100) },
                label = { Text("취향 (예: 바다, 아이와 함께)") }, modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onRecommend(hours, note) }, enabled = !loading, modifier = Modifier.height(64.dp)) {
                    Text("코스 추천받기", style = MaterialTheme.typography.labelLarge)
                }
                if (loading) CircularProgressIndicator()
            }
            course?.let { CourseCard(it, onNavigate) }
        }

        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("주변 히든 스탬프", style = MaterialTheme.typography.headlineMedium)
            Text("명소 500m 안에 들어가면 자동으로 획득돼요", color = Color.Gray)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(landmarks, key = { it.attraction.contentId }) { LandmarkRow(it) }
            }
        }
    }
}

@Composable
private fun CourseCard(course: DriveCourse, onNavigate: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(course.title, style = MaterialTheme.typography.headlineMedium)
            Text(course.summary, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text("운전 약 ${course.estDriveHours}시간") })
                AssistChip(onClick = {}, label = { Text("예상 +${course.expectedPoints}점") })
                if (course.candidatesSource == "mock") AssistChip(onClick = {}, label = { Text("시연 데이터") })
            }
            course.stops.forEachIndexed { i, s -> StopRow(i + 1, s) }
            if (course.stops.size > 3) Text("※ 내비에는 앞의 3곳까지 경로로 보냅니다", color = Color.Gray)
            FilledTonalButton(onClick = onNavigate, enabled = course.stops.isNotEmpty(), modifier = Modifier.height(64.dp)) {
                Text("🧭 Pleos 내비로 길안내", style = MaterialTheme.typography.labelLarge)
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
