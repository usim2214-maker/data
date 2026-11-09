package com.pleos.cardex

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pleos.cardex.ui.components.carStyleFor
import com.pleos.cardex.ui.components.drawCartoonCar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 차량 인식 시연용 '측면 카메라' 도로 장면 (직접 그린 원본 이미지 — 저작권 문제 없음).
 * 출력: build/demo_video/road_scene.png -> scripts/detect_demo_scene.py 가 백엔드 YOLOv8 감지 코드로 분석
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-mdpi")
class RoadSceneRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun render() {
        rule.setContent {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                // 하늘 + 건물
                drawRect(Brush.verticalGradient(listOf(Color(0xFF8EC9F5), Color(0xFFD8EEFB)), endY = h * 0.5f), size = Size(w, h * 0.5f))
                val bh = listOf(0.30f, 0.22f, 0.36f, 0.26f, 0.32f, 0.2f, 0.28f)
                bh.forEachIndexed { i, f ->
                    val bw = w / bh.size
                    drawRect(if (i % 2 == 0) Color(0xFFB0BEC5) else Color(0xFF90A4AE), Offset(i * bw, h * (0.5f - f)), Size(bw * 0.92f, h * f))
                    for (r in 0 until 5) for (c in 0 until 3) drawRect(Color(0xFFE3F2FD), Offset(i * bw + 12f + c * bw * 0.28f, h * (0.5f - f) + 14f + r * 26f), Size(bw * 0.16f, 12f))
                }
                // 인도 + 가로수
                drawRect(Color(0xFFBDBDBD), Offset(0f, h * 0.5f), Size(w, h * 0.08f))
                listOf(0.1f, 0.42f, 0.78f).forEach { x ->
                    drawRect(Color(0xFF6D4C41), Offset(w * x, h * 0.36f), Size(10f, h * 0.16f))
                    drawCircle(Color(0xFF43A047), h * 0.07f, Offset(w * x + 5f, h * 0.34f))
                }
                // 도로 (2차선) + 차선
                drawRect(Color(0xFF424242), Offset(0f, h * 0.58f), Size(w, h * 0.42f))
                var x = 0f
                while (x < w) { drawRect(Color(0xFFFFFFFF), Offset(x, h * 0.775f), Size(60f, 6f)); x += 110f }
                drawRect(Color(0xFFFFD54F), Offset(0f, h * 0.59f), Size(w, 4f))
                // 먼 차선: 작은 차 2대, 가까운 차선: 큰 차 1대
                drawCartoonCar(carStyleFor("genesis-g80", "세단", "rare"), w * 0.22f, h * 0.75f, w * 0.26f)
                drawCartoonCar(carStyleFor("kia-sorento", "SUV", "common"), w * 0.72f, h * 0.75f, w * 0.25f)
                drawCartoonCar(carStyleFor("kia-ev9", "SUV", "rare"), w * 0.46f, h * 0.97f, w * 0.42f)
            }
        }
        rule.waitForIdle()
        val view = rule.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(File("build/demo_video").apply { mkdirs() }, "road_scene.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
