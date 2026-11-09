package com.pleos.cardex

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pleos.cardex.data.CatalogVehicle
import com.pleos.cardex.data.CollectionEntry
import com.pleos.cardex.data.DailyMission
import com.pleos.cardex.data.HomeSummary
import com.pleos.cardex.data.SynergyBonus
import com.pleos.cardex.data.SynergyScore
import com.pleos.cardex.data.VehicleCard
import com.pleos.cardex.ui.screens.CollectionScreen
import com.pleos.cardex.ui.theme.CarDexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 앱을 켰을 때 첫 화면(도감 탭) — 차량 가로형 디스플레이 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1400dp-h720dp-land-mdpi")
class MainScreenShotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val catalog: List<CatalogVehicle> = run {
        val arr = org.json.JSONObject(File("../../backend/app/data/vehicle_catalog.json").readText()).getJSONArray("vehicles")
        val order = mapOf("legendary" to 0, "rare" to 1, "common" to 2)
        (0 until arr.length()).map { arr.getJSONObject(it) }.sortedBy { order[it.getString("rarity")] }
            .map { CatalogVehicle(it.getString("id"), it.getString("brand"), it.getString("model"), it.getString("body_type"), it.getString("rarity")) }
    }

    private fun card(id: String, line: String, seen: String, eco: String? = null) = catalog.first { it.id == id }.let {
        VehicleCard(id, it.brand, it.model, it.bodyType, null, line, emptyList(), rarity = it.rarity, eco = eco, seenColor = seen)
    }

    private fun capture(name: String, tall: Boolean = false) {
        val owned = listOf(
            CollectionEntry(card("hyundai-ioniq-5-n", "현대 N 의 고성능 전기차", "파란색", "ev"), "2026-11-08T00:00:00Z", "2026-11-09T00:00:00Z", 3, seenColors = listOf("파란색", "회색", "흰색")),
            CollectionEntry(card("kia-ev9", "3열 대형 전기 SUV", "흰색", "ev"), "2026-11-08T00:00:00Z", "2026-11-09T00:00:00Z", 2, seenColors = listOf("흰색", "검은색")),
            CollectionEntry(card("hyundai-grandeur", "대한민국 대표 준대형 세단", "검은색", "eco_option"), "2026-11-07T00:00:00Z", "2026-11-09T00:00:00Z", 5, seenColors = listOf("검은색")),
            CollectionEntry(card("kia-k5", "스포티한 중형 세단", "회색", "eco_option"), "2026-11-06T00:00:00Z", "2026-11-06T00:00:00Z", 1, seenColors = listOf("회색")),
        )
        val ctx = rule.activity
        OfficialTestImages.prime(ctx, catalog.flatMap { OfficialTestImages.urlsFor(ctx, it.id) })
        val home = HomeSummary(
            dexOwned = 4, dexTotal = 28, legendaryOwned = 1, legendaryTotal = 3, synergyTotal = 385, weeklyRank = 3, weeklyPoints = 95,
            gapToNext = 12, regionStamps = 3, regionTotal = 17, hiddenStamps = 2, collection = 4, itemsUnlocked = 14, itemsTotal = 47,
            nextGoal = "현대 차량 1종 더 모으면 '현대 컬렉터' +50", brandSpots = 1, brandTotal = 8,
            daily = DailyMission(date = "2026-11-09", week = "2026-W46", daysLeft = 6, evWeek = true, target = catalog.first { it.id == "kia-ev6" }, found = false, bonus = 50, streak = 4, bestStreak = 7, greenDays = 3),
        )
        val tabs = listOf("도감" to Icons.Filled.CollectionsBookmark, "수집" to Icons.Filled.CameraAlt, "퀴즈" to Icons.Filled.Quiz, "쇼룸" to Icons.Filled.DirectionsCar,
            "여행" to Icons.Filled.Map, "랭킹" to Icons.Filled.EmojiEvents, "설정" to Icons.Filled.Tune)
        rule.setContent {
            CarDexTheme {
                Surface(Modifier.fillMaxSize()) {
                    Row {
                        NavigationRail { tabs.forEachIndexed { i, (l, ic) -> NavigationRailItem(selected = i == 0, onClick = {}, icon = { Icon(ic, l) }, label = { Text(l) }) } }
                        Box(Modifier.fillMaxSize()) {
                            CollectionScreen(
                                entries = owned,
                                synergy = SynergyScore(385, 230, 155, listOf(SynergyBonus("전동화 컬렉터", 50, ""), SynergyBonus("컬러 컬렉터", 15, ""), SynergyBonus("차 쉬는 날", 30, ""))),
                                featuredId = "hyundai-ioniq-5-n", onRefresh = {}, onFeature = {}, onDelete = {}, catalog = catalog, home = home,
                            )
                        }
                    }
                }
            }
        }
        runCatching { rule.waitUntil(30_000) { false } }
        rule.waitForIdle()
        val view = rule.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun main() = capture("main_screen")

    @Config(qualifiers = "w1400dp-h1500dp-land-mdpi")
    @Test fun mainScrolled() = capture("main_screen_full")
}
