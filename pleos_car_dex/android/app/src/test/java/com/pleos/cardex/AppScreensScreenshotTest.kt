package com.pleos.cardex

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.pleos.cardex.data.Attraction
import com.pleos.cardex.data.Challenge
import com.pleos.cardex.data.CourseStop
import com.pleos.cardex.data.DriveCourse
import com.pleos.cardex.data.GeoPoint
import com.pleos.cardex.data.HomeSummary
import com.pleos.cardex.data.LandmarkNearby
import com.pleos.cardex.data.Leaderboard
import com.pleos.cardex.data.LimitedEvent
import com.pleos.cardex.data.MastersResponse
import com.pleos.cardex.data.RankRow
import com.pleos.cardex.data.Region
import com.pleos.cardex.data.RegionMaster
import com.pleos.cardex.pleos.DrivingSnapshot
import com.pleos.cardex.ui.Celebration
import com.pleos.cardex.ui.UiState
import com.pleos.cardex.ui.components.CelebrationOverlay
import com.pleos.cardex.ui.screens.DrivingScreen
import com.pleos.cardex.ui.screens.OnboardingScreen
import com.pleos.cardex.ui.screens.RankingScreen
import com.pleos.cardex.ui.screens.TravelScreen
import com.pleos.cardex.ui.theme.CarDexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 주요 화면 스크린샷 (app/build/screenshots 폴더에 PNG). 샘플 데이터로 렌더링 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1400dp-h720dp-land-mdpi")
class AppScreensScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent { CarDexTheme { Surface { content() } } }
        rule.waitForIdle()
        val view = rule.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(File("build/screenshots").apply { mkdirs() }, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val home = HomeSummary(
        synergyTotal = 420, weeklyRank = 3, weeklyPoints = 95, gapToNext = 12, regionStamps = 3, regionTotal = 17,
        hiddenStamps = 2, collection = 4, itemsUnlocked = 14, itemsTotal = 47, nextGoal = "⛰️ 강원 마스터까지 35점",
    )
    private val stops = listOf(
        CourseStop("mock-nami", "남이섬", 37.79, 127.52, "attraction", "gangwon", "지역 명소 · 새 지역 스탬프", newRegionStamp = true, newHiddenStamp = true),
        CourseStop("mock-seorak", "설악산 국립공원", 38.12, 128.47, "attraction", "gangwon", "강원의 대표 명산", newHiddenStamp = true),
        CourseStop("mock-fest-x", "속초 오징어 축제 (시연용)", 38.2, 128.59, "festival", "gangwon", "이번 주말 진행 중인 축제", newHiddenStamp = true),
    )
    private val course = DriveCourse(
        "강원 바다·산 드라이브", "남이섬에서 설악산을 거쳐 속초 축제까지, 강원 스탬프와 히든 3곳을 모아요.",
        stops, 4.6, 115, "mock", LimitedEvent("mock-fest-x", "속초 오징어 축제 (시연용)", "20261116"), 87,
    )
    private val landmarks = listOf(
        LandmarkNearby(Attraction("a", "경복궁", lat = 37.58, lon = 126.98), 1500, true, 20),
        LandmarkNearby(Attraction("b", "N서울타워", lat = 37.55, lon = 126.99), 1900, false, 20),
        LandmarkNearby(Attraction("c", "서울 빛초롱 축제 (시연용)", lat = 37.57, lon = 126.98, kind = "festival"), 800, false, 30),
    )

    @Test fun travel() = shot("travel_home") {
        TravelScreen(
            course = course, loading = false, landmarks = landmarks,
            challenge = Challenge(course, "2026-11-09T00:00:00Z", "2026-11-10T00:00:00Z", visited = listOf("mock-nami")),
            onRecommend = { _, _ -> }, onStartChallenge = {}, onResumeNavigation = {}, onAbandon = {}, home = home,
        )
    }

    @Test fun travelCourse() = shot("travel_course") {
        TravelScreen(course = course, loading = false, landmarks = landmarks, challenge = null,
            onRecommend = { _, _ -> }, onStartChallenge = {}, onResumeNavigation = {}, onAbandon = {}, home = home)
    }

    @Test fun onboarding() = shot("onboarding") { OnboardingScreen(onFinish = {}) }

    @Test fun celebration() = shot("celebration") {
        TravelScreen(null, false, landmarks, null, { _, _ -> }, {}, {}, {}, home)
        CelebrationOverlay(Celebration("👑", "강원 마스터 등극!", "쇼룸에 왕관·골드 아이템이 열렸어요"), {}, animate = false)
    }

    @Test fun ranking() = shot("ranking") {
        val gangwon = Region("gangwon", "강원", 37.8, 128.2, "설악산", "⛰️", listOf("#14532D", "#A7F3D0"), 40, "강원권", "mountain")
        val busan = Region("busan", "부산", 35.18, 129.08, "광안대교", "🌉", listOf("#0C4A6E", "#F472B6"), 20, "영남권", "bridge")
        val rows = listOf(
            RankRow(1, "r5", "주말캠핑족", 105), RankRow(2, "r1", "강원도드라이버", 100), RankRow(3, "me", "나", 95, isMe = true),
            RankRow(4, "r6", "서울출퇴근러", 68), RankRow(5, "r3", "전국일주중", 64),
        )
        RankingScreen(
            weekly = Leaderboard("2026-W46", null, rows, rows[2], 6),
            masters = MastersResponse(
                "2026-11",
                listOf(
                    RegionMaster(busan, RankRow(1, "me", "나", 190, true), 190, 0, true),
                    RegionMaster(gangwon, RankRow(1, "r1", "강원도드라이버", 140), 106, 35),
                ),
                listOf("부산"),
            ),
            currentRegion = gangwon, onLoad = {},
        )
    }

    @Test fun driving() = shot("driving_mode") {
        DrivingScreen(
            state = UiState(
                isDriving = true, drivingSource = "차량 주행 상태",
                challenge = Challenge(course, "2026-11-09T00:00:00Z", "2026-11-10T00:00:00Z", visited = listOf("mock-nami")),
                driving = DrivingSnapshot(GeoPoint(37.9, 127.9), source = "Pleos FusedLocation"),
                weekly = Leaderboard("2026-W46", null, emptyList(), RankRow(3, "me", "나", 95, true), 6),
                notice = "✨ 히든 스탬프 '남이섬' 획득! +35 · 코스 1/3",
            ),
            onResumeNavigation = {}, onToggleVoice = {}, onExitSimulation = null,
        )
    }
}
