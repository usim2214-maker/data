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
@Config(sdk = [34], qualifiers = "w1400dp-h860dp-land-mdpi")
class CarArtScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // 카탈로그 28종 전체 (backend/app/data/vehicle_catalog.json)
    private val cars: List<Pair<Triple<String, String, String>, String>> = run {
        val arr = org.json.JSONObject(File("../../backend/app/data/vehicle_catalog.json").readText()).getJSONArray("vehicles")
        val order = mapOf("legendary" to 0, "rare" to 1, "common" to 2)
        (0 until arr.length()).map { i -> arr.getJSONObject(i) }
            .sortedBy { order[it.getString("rarity")] ?: 3 }
            .map { Triple(it.getString("id"), it.getString("body_type"), "${it.getString("brand")} ${it.getString("model")}") to it.getString("rarity") }
    }

    @Test fun gallery() {
        rule.setContent {
            Column(Modifier.fillMaxSize().background(Color(0xFF0D1117)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cars.chunked(7).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (c, rarity) ->
                            Column(
                                Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF1C2330)).padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CarIllustration(c.first, c.second, rarity, Modifier.size(180.dp, 150.dp))
                                Text(c.third, color = com.pleos.cardex.ui.theme.rarityColor(rarity), fontSize = 15.sp)
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

    /** 큰 크기 디테일 확인용 */
    @Test fun detail() {
        val picks = listOf(Triple("hyundai-ioniq-5", "CUV", "rare"), Triple("kia-ev9", "SUV", "rare"), Triple("genesis-g80", "세단", "rare"), Triple("hyundai-ioniq-5-n", "CUV", "legendary"))
        rule.setContent {
            Column(Modifier.fillMaxSize().background(Color(0xFF1C2330)).padding(10.dp)) {
                picks.chunked(2).forEach { row ->
                    Row { row.forEach { (id, b, r) -> CarIllustration(id, b, r, Modifier.size(690.dp, 420.dp)) } }
                }
            }
        }
        rule.waitForIdle()
        val view = rule.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, "car_detail.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
