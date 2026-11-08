package com.pleos.cardex.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.cardex.data.GeoPoint
import com.pleos.cardex.ui.UiState

/**
 * 주행 모드: 주행 중에는 조작이 필요한 화면을 모두 잠그고, 한눈에 읽을 정보와 큰 버튼 하나만 보여준다.
 * 알림은 음성(Pleos TTS)으로 안내. 정차(P단)하면 전체 기능이 다시 열린다.
 */
@Composable
fun DrivingScreen(
    state: UiState,
    onResumeNavigation: () -> Unit,
    onToggleVoice: () -> Unit,
    onExitSimulation: (() -> Unit)?,
) {
    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("🚗 주행 모드", fontSize = 44.sp, style = MaterialTheme.typography.displaySmall)
            Row {
                TextButton(onClick = onToggleVoice) { Text(if (state.voiceOn) "🔊 음성 안내 켜짐" else "🔇 음성 안내 꺼짐", fontSize = 22.sp) }
                onExitSimulation?.let { TextButton(onClick = it) { Text("시연 종료", fontSize = 22.sp) } }
            }
        }
        Text("정차하면 모든 기능을 이용할 수 있어요 · ${state.drivingSource}", color = Color.Gray, fontSize = 22.sp)

        val ch = state.challenge?.takeIf { it.status == "active" }
        if (ch != null) {
            val here = state.driving?.location
            val next = ch.course.stops.firstOrNull { it.contentId !in ch.visited }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("🏁 ${ch.course.title}", fontSize = 34.sp)
                    LinearProgressIndicator(
                        progress = { ch.visited.size / ch.course.stops.size.coerceAtLeast(1).toFloat() },
                        modifier = Modifier.fillMaxWidth().height(14.dp),
                    )
                    Text("${ch.visited.size} / ${ch.course.stops.size} 곳 · 완주 +${ch.course.completionBonus}", fontSize = 28.sp)
                    next?.let { n ->
                        val dist = here?.let { distanceKm(it, GeoPoint(n.lat, n.lon)) }
                        Text("다음: ${n.name}" + (dist?.let { "  (%.1fkm)".format(it) } ?: ""), fontSize = 32.sp,
                            color = MaterialTheme.colorScheme.secondary)
                    }
                    Button(onClick = onResumeNavigation, modifier = Modifier.height(80.dp)) {
                        Text("🧭 남은 곳 길안내", fontSize = 28.sp)
                    }
                }
            }
        } else {
            val near = state.landmarks.filterNot { it.collected }.minByOrNull { it.distanceM }
            Text(
                near?.let { "가까운 히든 스탬프: ${it.attraction.title} (%.1fkm)".format(it.distanceM / 1000.0) }
                    ?: "주변 히든 스탬프를 찾는 중…",
                fontSize = 32.sp,
            )
        }

        state.weekly?.me?.let { me ->
            Text("이번 주 ${me.rank}위 · ${me.points}점" + (state.weekly.gapToNext?.let { " — 한 계단 위까지 ${it}점" } ?: ""), fontSize = 28.sp)
        }
        state.notice?.let { Text(it, fontSize = 30.sp, color = MaterialTheme.colorScheme.secondary) }
    }
}

private fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
    val out = FloatArray(1)
    android.location.Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, out)
    return out[0] / 1000.0
}
