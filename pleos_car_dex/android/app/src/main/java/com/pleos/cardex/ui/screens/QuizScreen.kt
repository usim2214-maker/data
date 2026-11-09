package com.pleos.cardex.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.cardex.data.QuizQuestion
import com.pleos.cardex.ui.UiState
import com.pleos.cardex.ui.components.hexColor

/**
 * 아이용 차 맞히기 퀴즈: 방금 지나간 차의 종류·색깔 맞히기 (YOLO 결과로 출제).
 * 정차 중엔 큰 버튼으로, 주행 중엔 말로("빨간색!", "버스!", "1번!") 대답 — 문제와 결과는 음성으로 읽어 줌.
 */
@Composable
fun QuizScreen(state: UiState, onNext: () -> Unit, onAnswer: (String) -> Unit, onListen: () -> Unit) {
    val q = state.quiz
    Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(Modifier.weight(1.15f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("🚗 차 맞히기 퀴즈", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Color.White)
                Text("⭐ ${state.quizStars}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFC94D))
            }
            if (q == null) {
                Text(state.quizMessage ?: "지나가는 차를 보고 종류와 색깔을 맞혀 봐요!", fontSize = 26.sp, color = Color.White.copy(alpha = 0.85f))
            } else {
                Text(q.prompt, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    q.choices.forEachIndexed { i, c -> ChoiceButton(i + 1, c, q, state.quizPicked, onAnswer, Modifier.weight(1f)) }
                }
                state.quizPicked?.let { picked ->
                    val ok = picked == q.answer
                    Text(
                        if (ok) "🎉 정답이에요! 별 하나 ⭐" else "😊 아쉬워요! 정답은 ${q.answer}",
                        fontSize = 30.sp, fontWeight = FontWeight.Bold, color = if (ok) Color(0xFF7CE38B) else Color(0xFFFFB74D),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onNext, enabled = !state.quizLoading, modifier = Modifier.height(80.dp)) {
                    Text(if (q == null) "▶ 시작하기" else "다음 문제", fontSize = 26.sp)
                }
                FilledTonalButton(onClick = onListen, modifier = Modifier.height(80.dp)) {
                    Text(if (state.listening) "🎙 듣는 중…" else "🎙 말로 대답하기", fontSize = 24.sp)
                }
                if (state.quizLoading) CircularProgressIndicator()
            }
            Text("주행 중에는 말로만: '퀴즈' 라고 하면 문제, '빨간색!' · '버스!' · '1번!' 으로 대답", color = Color.Gray, fontSize = 16.sp)
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            QuizPicture(state.quizFrameJpeg, q, revealed = state.quizPicked != null)
        }
    }
}

@Composable
private fun ChoiceButton(no: Int, choice: String, q: QuizQuestion, picked: String?, onAnswer: (String) -> Unit, modifier: Modifier) {
    val icon = q.choiceIcons.getOrNull(q.choices.indexOf(choice)).orEmpty()
    val border = when {
        picked == null -> Color.White.copy(alpha = 0.2f)
        choice == q.answer -> Color(0xFF7CE38B)
        choice == picked -> Color(0xFFFF6B6B)
        else -> Color.White.copy(alpha = 0.1f)
    }
    Column(
        modifier.height(190.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF1C2330))
            .border(4.dp, border, RoundedCornerShape(24.dp)).clickable(enabled = picked == null) { onAnswer(choice) }.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (q.type == "color") {
            Box(Modifier.size(72.dp).clip(CircleShape).background(hexColor(icon)).border(2.dp, Color.White.copy(alpha = 0.4f), CircleShape))
        } else {
            Text(icon, fontSize = 64.sp)
        }
        Text("$no. $choice", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
    }
}

/** 방금 지나간 장면: 대답 전에는 흐리게 + 박스만, 대답하면 선명하게 */
@Composable
private fun QuizPicture(jpeg: ByteArray?, q: QuizQuestion?, revealed: Boolean) {
    val bmp = remember(jpeg) { jpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(24.dp)).background(Color(0xFF0B0F16))) {
        if (bmp != null && q != null) {
            Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
                val scale = minOf(boxW / bmp.width, boxH / bmp.height)
                val ox = (boxW - bmp.width * scale) / 2; val oy = (boxH - bmp.height * scale) / 2
                val b = q.detection.bbox
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(0xFFFFC94D), Offset(ox + b.x1 * scale, oy + b.y1 * scale), Size((b.x2 - b.x1) * scale, (b.y2 - b.y1) * scale), style = Stroke(6f))
                }
            }
            if (!revealed) {
                // 대답 전에는 가림막 (blur 는 Android 12+ 에서만 동작해 모든 기기에서 확실히 가리도록)
                Box(Modifier.fillMaxSize().background(Color(0xF00B0F16)))
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("❓", fontSize = 96.sp)
                    Text("대답하면 보여줄게요", fontSize = 22.sp, color = Color.White.copy(alpha = 0.8f))
                }
            }
            else Text("이 차였어요!", fontSize = 22.sp, color = Color.Black, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp).background(Color(0xFFFFC94D), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 4.dp))
        } else {
            Text("🚙 💨", fontSize = 80.sp, modifier = Modifier.align(Alignment.Center))
        }
    }
}
