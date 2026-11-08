package com.pleos.cardex.ui.screens

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.pleos.cardex.ui.UiState
import com.pleos.cardex.ui.components.FlipCard
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/** 메인 대시보드: 좌측 주행 영상(직접 입력) + 조작, 우측 대형 플립 카드 */
@Composable
fun ScanScreen(
    state: UiState,
    videoPositionMs: AtomicLong,
    onScanOnce: () -> Unit,
    onToggleAuto: () -> Unit,
) {
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {

            state.demoVideo?.let { DriveVideo(it, videoPositionMs, Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onScanOnce, enabled = !state.busy, modifier = Modifier.height(64.dp)) {
                    Text("지금 인식", style = MaterialTheme.typography.labelLarge)
                }
                FilledTonalButton(onClick = onToggleAuto, modifier = Modifier.height(64.dp)) {
                    Text(if (state.scanning) "자동 스캔 중지" else "자동 스캔", style = MaterialTheme.typography.labelLarge)
                }
                if (state.busy) CircularProgressIndicator()
            }
            state.lastResult?.message?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary)
            }
            state.error?.let { Text(it, color = Color(0xFFFF6B6B)) }
            Text(
                "수집 ${state.collection.size}대 · 시너지 ${state.synergy?.total ?: 0}점",
                style = MaterialTheme.typography.bodyLarge,
            )
            state.driving?.let { d ->
                d.location?.let { Text("%.4f, %.4f (%s)".format(it.lat, it.lon, d.source), color = Color.Gray) }
            }
        }

        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            val card = state.lastResult?.card
            if (card != null) {
                FlipCard(card, Modifier.fillMaxHeight().aspectRatio(0.72f))
            } else {
                Text("주변 차량을 인식하면 카드가 나타나요", style = MaterialTheme.typography.titleLarge, color = Color.Gray)
            }
        }
    }
}

/** 시연용 주행 영상 재생. 재생 위치를 videoPositionMs 로 공유해 해당 프레임을 인식에 사용 */
@Composable
private fun DriveVideo(uri: Uri, positionMs: AtomicLong, modifier: Modifier) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(uri)
                setOnPreparedListener { it.isLooping = true; start() }
                view = this
            }
        },
    )
    LaunchedEffect(view) {
        while (true) {
            view?.let { positionMs.set(it.currentPosition.toLong()) }
            delay(200)
        }
    }
}
