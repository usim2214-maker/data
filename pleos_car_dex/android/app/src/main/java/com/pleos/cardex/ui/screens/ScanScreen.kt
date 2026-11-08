package com.pleos.cardex.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pleos.cardex.ui.UiState
import com.pleos.cardex.ui.components.FlipCard

/** 메인 대시보드: 좌측 상태/조작, 우측 대형 플립 카드 */
@Composable
fun ScanScreen(state: UiState, onScanOnce: () -> Unit, onToggleAuto: () -> Unit) {
    Row(Modifier.fillMaxSize().padding(32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.width(360.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("차량 스캔", style = MaterialTheme.typography.displaySmall)
            state.driving?.let {
                Text("%.1f km/h".format(it.speedKmh), style = MaterialTheme.typography.titleLarge)
                it.location?.let { loc -> Text("%.4f, %.4f".format(loc.lat, loc.lon), color = Color.Gray) }
            }
            Button(onClick = onScanOnce, enabled = !state.busy, modifier = Modifier.height(72.dp)) {
                Text("지금 인식", style = MaterialTheme.typography.labelLarge)
            }
            FilledTonalButton(onClick = onToggleAuto, modifier = Modifier.height(72.dp)) {
                Text(if (state.scanning) "자동 스캔 중지" else "자동 스캔 시작", style = MaterialTheme.typography.labelLarge)
            }
            if (state.busy) CircularProgressIndicator()
            state.lastResult?.message?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary)
            }
            state.error?.let { Text(it, color = Color(0xFFFF6B6B)) }
            Text("수집: ${state.collection.size}대", style = MaterialTheme.typography.bodyLarge)
        }

        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val card = state.lastResult?.card
            if (card != null) {
                FlipCard(card, Modifier.fillMaxHeight().aspectRatio(0.72f))
            } else {
                Text("주변 차량을 인식하면 카드가 나타나요", style = MaterialTheme.typography.titleLarge, color = Color.Gray)
            }
        }
    }
}
