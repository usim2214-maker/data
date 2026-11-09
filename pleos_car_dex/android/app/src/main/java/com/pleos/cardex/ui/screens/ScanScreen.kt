package com.pleos.cardex.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pleos.cardex.data.Detection
import com.pleos.cardex.ui.UiState
import com.pleos.cardex.ui.components.FlipCard
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

/**
 * 수집 탭: YOLOv8 차량 인식 -> 내가 고른 정보(키워드)로 AI 요약 -> 카드 (앞: 일러스트 / 뒤: 요약) -> 도감
 * 입력 경로(차량 영상 API 등)는 미정이라, 지금은 시연 영상/이미지 프레임을 보낸다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanScreen(
    state: UiState,
    videoPositionMs: AtomicLong,
    onScanOnce: () -> Unit,
    onToggleAuto: () -> Unit,
) {
    val result = state.lastResult
    Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1.35f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("차량 수집", style = MaterialTheme.typography.displaySmall)
                Text("YOLOv8 인식 → 내가 고른 정보로 AI 카드", color = Color.Gray, fontSize = 18.sp)
            }
            FramePreview(state.lastFrameJpeg, result?.detections.orEmpty(), result?.detection, state.demoVideo, videoPositionMs, state.busy)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onScanOnce, enabled = !state.busy, modifier = Modifier.height(76.dp)) {
                    Text("🔎 지금 인식", style = MaterialTheme.typography.labelLarge)
                }
                FilledTonalButton(onClick = onToggleAuto, modifier = Modifier.height(76.dp)) {
                    Text(if (state.scanning) "⏸ 자동 수집 중지" else "▶ 자동 수집", style = MaterialTheme.typography.labelLarge)
                }
                if (state.busy) CircularProgressIndicator()
            }
            // 내가 고른 정보 = AI 프롬프트에 들어가는 키워드
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("카드에 담을 정보", color = Color.Gray, fontSize = 16.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.keywords.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                }
            }
            Text("설정 탭에서 바꿀 수 있어요 · 사진은 인식에만 쓰고 저장하지 않아요", color = Color.Gray, fontSize = 14.sp)
        }

        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            val card = result?.card
            if (card != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (result.isNewEntry) "🃏 새 카드!" else "이미 모은 카드 · 재회",
                        color = if (result.isNewEntry) Color(0xFFFFC94D) else Color.Gray, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    )
                    FlipCard(card, Modifier.fillMaxHeight(0.86f).aspectRatio(0.72f))
                    Text("카드를 탭하면 뒷면(AI 요약)", color = Color.Gray, fontSize = 14.sp)
                }
            } else {
                Text(
                    result?.message?.takeIf { it.isNotBlank() } ?: "차량을 인식하면 카드가 나타나요",
                    style = MaterialTheme.typography.titleLarge, color = Color.Gray,
                )
            }
        }
    }
}

/** 마지막 분석 프레임 + 감지 박스 (카드로 만든 차는 노란색). 오른쪽 위에 입력 영상 미리보기 */
@Composable
private fun FramePreview(jpeg: ByteArray?, dets: List<Detection>, primary: Detection?, video: Uri?, positionMs: AtomicLong, busy: Boolean) {
    val bmp = remember(jpeg) { jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(Color(0xFF0B0F16))) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // ContentScale.Fit 기준 실제 그려진 영역 계산
                val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
                val scale = minOf(boxW / bmp.width, boxH / bmp.height)
                val ox = (boxW - bmp.width * scale) / 2; val oy = (boxH - bmp.height * scale) / 2
                Canvas(Modifier.fillMaxSize()) {
                    dets.forEach { d ->
                        val main = primary != null && d.bbox == primary.bbox
                        drawRect(
                            if (main) Color(0xFFFFC94D) else Color(0xFF4DA3FF),
                            Offset(ox + d.bbox.x1 * scale, oy + d.bbox.y1 * scale),
                            Size((d.bbox.x2 - d.bbox.x1) * scale, (d.bbox.y2 - d.bbox.y1) * scale),
                            style = Stroke(if (main) 6f else 4f),
                        )
                    }
                }
                val density = LocalDensity.current
                dets.forEach { d ->
                    Text(
                        "${d.label} ${"%.2f".format(d.confidence)}", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .offset(with(density) { (ox + d.bbox.x1 * scale).toDp() }, with(density) { (oy + d.bbox.y1 * scale).toDp() })
                            .background(if (primary != null && d.bbox == primary.bbox) Color(0xFFFFC94D) else Color(0xFF4DA3FF))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                if (busy) "분석 중…" else "YOLOv8 감지 ${dets.size}대",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        } else {
            Text("인식할 화면이 여기에 나타나요", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
        }
        // 입력 영상(시연용) 작은 미리보기 — 재생 위치의 프레임을 인식에 사용
        video?.let { DriveVideo(it, positionMs, Modifier.align(Alignment.TopEnd).padding(10.dp).size(220.dp, 124.dp).clip(RoundedCornerShape(10.dp))) }
    }
}

@Composable
private fun DriveVideo(uri: Uri, positionMs: AtomicLong, modifier: Modifier) {
    var view by remember { mutableStateOf<VideoView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoURI(uri)
                setOnPreparedListener { it.isLooping = true; it.setVolume(0f, 0f); start() }
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
