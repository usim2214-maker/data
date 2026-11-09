package com.pleos.cardex

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pleos.cardex.data.Attraction
import com.pleos.cardex.data.CatalogVehicle
import com.pleos.cardex.data.DailyMission
import com.pleos.cardex.data.DecorItem
import com.pleos.cardex.data.HomeSummary
import com.pleos.cardex.data.LandmarkNearby
import com.pleos.cardex.data.Region
import com.pleos.cardex.data.Showroom
import com.pleos.cardex.data.VehicleCard
import com.pleos.cardex.pleos.NaviPlace
import com.pleos.cardex.ui.UiState
import com.pleos.cardex.ui.screens.HomeScreen
import com.pleos.cardex.ui.screens.SplashScreen
import com.pleos.cardex.ui.theme.CarDexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 새 메인 화면(쇼룸 + 내비 검색·추천), ☰ 메뉴, 시작 화면 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1400dp-h720dp-land-mdpi")
class HomeScreenShotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val seoul = Region("seoul", "서울", 37.57, 126.98, "남산타워", "🗼", listOf("#1E3A8A", "#F59E0B"), 10, "수도권", "city")
    private val car = VehicleCard("hyundai-ioniq-5-n", "현대", "아이오닉 5 N", "CUV", null, "현대 N 의 고성능 전기차", emptyList(), rarity = "legendary", eco = "ev", seenColor = "파란색")
    private val state = UiState(
        showroom = Showroom(
            car = car, background = seoul,
            floor = DecorItem("floor-marble", "floor", "marble", "골드 마블"), light = DecorItem("light-gold", "light", "#FFD54F", "챔피언 골드"),
            pedestal = DecorItem("pedestal-gold", "pedestal", "gold", "골드 포디움"), signText = "MY GARAGE",
        ),
        naviPlaces = listOf(
            NaviPlace("집", "서울 마포구 월드컵로", 37.56, 126.9, "home"),
            NaviPlace("회사", "경기 성남시 판교역로", 37.39, 127.11, "work"),
            NaviPlace("스타필드 하남", "경기 하남시 미사대로", 37.55, 127.22, "recent"),
        ),
        landmarks = listOf(LandmarkNearby(Attraction("brand-hms-seoul", "현대 모터스튜디오 서울", lat = 37.52, lon = 127.04, kind = "brand"), 2400, false, 40)),
        home = HomeSummary(
            dexOwned = 4, dexTotal = 28, legendaryOwned = 1, legendaryTotal = 3, synergyTotal = 385, weeklyRank = 3, weeklyPoints = 95,
            regionStamps = 3, regionTotal = 17, hiddenStamps = 2, collection = 4, itemsUnlocked = 14, itemsTotal = 47, nextGoal = "",
            daily = DailyMission(date = "2026-11-09", week = "2026-W46", daysLeft = 6, evWeek = true,
                target = CatalogVehicle("kia-ev6", "기아", "EV6", "CUV", "rare"), found = false, bonus = 50, streak = 4, bestStreak = 7, greenDays = 3),
        ),
    )

    private fun shot(name: String, wait: Boolean = true, content: @Composable () -> Unit) {
        OfficialTestImages.prime(rule.activity, OfficialTestImages.urlsFor(rule.activity, car.id))
        rule.setContent { CarDexTheme { Surface { content() } } }
        if (wait) runCatching { rule.waitUntil(8_000) { false } }
        rule.waitForIdle()
        val view = rule.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() = shot("home_main") { HomeScreen(state, {}, {}, {}, {}, {}, animate = false) }

    @Test fun drawer() = shot("home_menu") {
        ModalNavigationDrawer(drawerState = rememberDrawerState(DrawerValue.Open), drawerContent = { AppDrawer("home") {} }) {
            HomeScreen(state, {}, {}, {}, {}, {}, animate = false)
        }
    }

    @Test fun splash() = shot("splash", wait = false) { SplashScreen(progress = 0.7f, animate = false) }
}
