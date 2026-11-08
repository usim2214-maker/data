package com.pleos.cardex.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val PRESETS = listOf("가격", "브랜드", "마력", "연비", "출시연도", "구동방식", "제로백", "차체크기", "안전등급")
private const val MAX_KEYWORDS = 8

/** 유저 맞춤 키워드 설정 -> 백엔드 LLM 프롬프트에 동적 주입 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeywordSettingsScreen(current: List<String>, onSave: (List<String>) -> Unit) {
    val selected = remember(current) { mutableStateListOf(*current.toTypedArray()) }
    var custom by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("카드에 보여줄 정보", style = MaterialTheme.typography.displaySmall)
        Text("선택한 순서대로 카드 뒷면에 표시됩니다 (최대 $MAX_KEYWORDS개)", color = Color.Gray)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (PRESETS + selected.filterNot { it in PRESETS }).forEach { kw ->
                val on = kw in selected
                FilterChip(
                    selected = on,
                    onClick = { if (on) selected.remove(kw) else if (selected.size < MAX_KEYWORDS) selected.add(kw) },
                    label = { Text(kw, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(8.dp)) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = custom, onValueChange = { custom = it }, label = { Text("직접 추가") }, modifier = Modifier.width(320.dp))
            Button(onClick = {
                val kw = custom.trim()
                if (kw.isNotEmpty() && kw !in selected && selected.size < MAX_KEYWORDS) selected.add(kw)
                custom = ""
            }) { Text("추가") }
        }

        Text("현재 순서: ${selected.joinToString(" · ")}", style = MaterialTheme.typography.bodyLarge)
        Button(onClick = { onSave(selected.toList()) }, enabled = selected.isNotEmpty()) {
            Text("저장", style = MaterialTheme.typography.labelLarge)
        }
    }
}
