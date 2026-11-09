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
        val ctx = rule.activity
        val urls = cars.flatMap { OfficialTestImages.urlsFor(ctx, it.first.first) }
        OfficialTestImages.prime(ctx, urls)
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
        runCatching { rule.waitUntil(30_000) { urls.all { com.pleos.cardex.ui.components.OfficialCarImages.cached(it) != null } } }
        rule.waitForIdle()
        val view = rule.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, "car_gallery.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** 실제 차 여러 각도 (아이오닉 5 N 360° 프레임 / 제네시스 G80 갤러리) */
    @Test fun detail() {
        val ctx = rule.activity
        val style = com.pleos.cardex.ui.components.carStyleFor("hyundai-ioniq-5-n", "CUV", "legendary")
        val urls = OfficialTestImages.urlsFor(ctx, "hyundai-ioniq-5-n", allFrames = true)
        OfficialTestImages.prime(ctx, urls)
        val turns = listOf(0f, 0.12f, 0.3f, 0.5f, 0.65f, 0.85f)
        rule.setContent {
            Column(Modifier.fillMaxSize().background(Color(0xFF1C2330)).padding(10.dp)) {
                turns.chunked(3).forEach { row ->
                    Row { row.forEach { t -> com.pleos.cardex.ui.components.OfficialCarView("hyundai-ioniq-5-n", style, Modifier.size(460.dp, 280.dp), turn = t) } }
                }
            }
        }
        runCatching { rule.waitUntil(60_000) { urls.all { com.pleos.cardex.ui.components.OfficialCarImages.cached(it) != null } } }
        rule.waitForIdle()
        val view = rule.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, "car_detail.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
