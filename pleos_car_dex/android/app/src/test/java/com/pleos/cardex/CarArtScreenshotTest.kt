package com.pleos.cardex

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.cardex.ui.components.CarIllustration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 시연 차종 카툰 일러스트 모음 (app/build/screenshots 폴더에 PNG) */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1200dp-h560dp-land-mdpi")
class CarArtScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val cars = listOf(
        Triple("hyundai-ioniq-5", "CUV", "현대 아이오닉 5") to "common",
        Triple("kia-ev9", "SUV", "기아 EV9") to "rare",
        Triple("genesis-g80", "세단", "제네시스 G80") to "rare",
        Triple("hyundai-grandeur", "세단", "현대 그랜저") to "common",
        Triple("kia-sorento", "SUV", "기아 쏘렌토") to "common",
        Triple("hyundai-ioniq-5-n", "CUV", "현대 아이오닉 5 N") to "legendary",
    )

    @Test fun gallery() {
        rule.setContent {
            Column(Modifier.fillMaxSize().background(Color(0xFF0D1117)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                cars.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        row.forEach { (c, rarity) ->
                            Column(
                                Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF1C2330)).padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CarIllustration(c.first, c.second, rarity, Modifier.size(360.dp, 200.dp))
                                Text(c.third, color = Color.White, fontSize = 20.sp)
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val view = rule.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, "car_gallery.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
