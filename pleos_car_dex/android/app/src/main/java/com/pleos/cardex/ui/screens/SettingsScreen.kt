package com.pleos.cardex.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.cardex.data.CarDexApi

/** 설정: 카드 키워드 + 음성 안내 + (디버그) 시연 도구 */
@Composable
fun SettingsScreen(
    keywords: List<String>,
    voiceOn: Boolean,
    showDemoTools: Boolean,
    onSaveKeywords: (List<String>) -> Unit,
    onToggleVoice: () -> Unit,
    onSeedDemo: () -> Unit,
    onResetDemo: () -> Unit,
    onSimulateDriving: () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1.4f).fillMaxHeight()) { KeywordSettingsScreen(keywords, onSaveKeywords) }
        Column(Modifier.weight(1f).fillMaxHeight().padding(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("음성 안내", style = MaterialTheme.typography.titleLarge)
                        Text("스탬프·코스 알림을 소리로 들려줘요", color = Color.Gray)
                    }
                    Switch(checked = voiceOn, onCheckedChange = { onToggleVoice() })
                }
            }
            if (showDemoTools) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("🎬 시연 도구", style = MaterialTheme.typography.titleLarge)
                        Text("서버 DEMO_MODE=true 필요 · 서버 ${CarDexApi.baseUrl}", color = Color.Gray, fontSize = 14.sp)
                        FilledTonalButton(onClick = onSeedDemo, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("시연 데이터 채우기", fontSize = 20.sp) }
                        FilledTonalButton(onClick = onSimulateDriving, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("🚗 주행 모드 시연", fontSize = 20.sp) }
                        OutlinedButton(onClick = onResetDemo, modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("전체 초기화", fontSize = 20.sp, color = Color(0xFFFF6B6B)) }
                    }
                }
            }
        }
    }
}
