package com.pleos.cardex.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 차량 정보 목록에 있는 항목 (서버 /preferences/fields 를 못 받았을 때의 기본값) */
val DEFAULT_FIELDS = listOf("가격", "브랜드", "차급", "마력", "연비", "연료", "출시연도", "구동방식", "승차인원")
private const val MAX_KEYWORDS = 8

/** 카드 뒷면에 보여줄 정보 고르기 — 차량 정보 목록에 있는 항목 중에서만 (AI 생성 아님) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeywordSettingsScreen(current: List<String>, onSave: (List<String>) -> Unit, fields: List<String> = DEFAULT_FIELDS) {
    val selected = remember(current) { mutableStateListOf(*current.filter { it in fields }.toTypedArray()) }

    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Text("카드에 보여줄 정보", style = MaterialTheme.typography.displaySmall)
        Text("고른 순서대로 카드 뒷면에 표시돼요 (최대 ${MAX_KEYWORDS}개) · 정보는 도감의 차량 정보 목록에서 가져와요", color = Color.Gray)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            fields.forEach { kw ->
                val on = kw in selected
                FilterChip(
                    selected = on,
                    onClick = { if (on) selected.remove(kw) else if (selected.size < MAX_KEYWORDS) selected.add(kw) },
                    label = { Text(kw, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(8.dp)) },
                )
            }
        }

        Text("현재 순서: ${selected.joinToString(" · ")}", style = MaterialTheme.typography.bodyLarge)
        Button(onClick = { onSave(selected.toList()) }, enabled = selected.isNotEmpty()) {
            Text("저장", style = MaterialTheme.typography.labelLarge)
        }
    }
}
