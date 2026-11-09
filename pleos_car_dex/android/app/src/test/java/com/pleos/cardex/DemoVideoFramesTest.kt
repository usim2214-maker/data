package com.pleos.cardex

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.cardex.data.Attraction
import com.pleos.cardex.data.Challenge
import com.pleos.cardex.data.CollectionEntry
import com.pleos.cardex.data.CourseStop
import com.pleos.cardex.data.DecorCatalog
import com.pleos.cardex.data.DecorItem
import com.pleos.cardex.data.DriveCourse
import com.pleos.cardex.data.GeoPoint
import com.pleos.cardex.data.HomeSummary
import com.pleos.cardex.data.KeywordFact
import com.pleos.cardex.data.LandmarkNearby
import com.pleos.cardex.data.Leaderboard
import com.pleos.cardex.data.LimitedEvent
import com.pleos.cardex.data.MastersResponse
import com.pleos.cardex.data.PlacedPropOut
import com.pleos.cardex.data.RankRow
import com.pleos.cardex.data.Region
import com.pleos.cardex.data.RegionMaster
import com.pleos.cardex.data.Showroom
import com.pleos.cardex.data.Stamp
import com.pleos.cardex.data.StampBook
import com.pleos.cardex.data.VehicleCard
import com.pleos.cardex.pleos.DrivingSnapshot
import com.pleos.cardex.ui.Celebration
import com.pleos.cardex.ui.UiState
import com.pleos.cardex.ui.components.CarIllustration
import com.pleos.cardex.ui.components.CelebrationOverlay
import com.pleos.cardex.ui.screens.DrivingScreen
import com.pleos.cardex.ui.screens.OnboardingScreen
import com.pleos.cardex.ui.screens.RankingScreen
import com.pleos.cardex.ui.screens.ShowroomScreen
import com.pleos.cardex.ui.screens.TravelScreen
import com.pleos.cardex.ui.theme.CarDexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 시연 영상용 프레임 생성: 실제 앱 화면 컴포저블을 샘플 데이터로 렌더링하고, 버튼은 실제로 클릭해 진행한다.
 * 출력: build/demo_video/frames/NNNN.png + concat.txt (ffmpeg concat 목록). 영상 합성은 scripts/make_demo_video.sh
 * 실행: ./gradlew testDebugUnitTest --tests '*DemoVideoFramesTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h720dp-land-mdpi")
class DemoVideoFramesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    // ---------------- 샘플 데이터 ----------------
    private val gangwon = Region("gangwon", "강원", 37.8, 128.2, "설악산", "⛰️", listOf("#14532D", "#A7F3D0"), 40, "강원권", "mountain")
    private val busan = Region("busan", "부산", 35.18, 129.08, "광안대교", "🌉", listOf("#0C4A6E", "#F472B6"), 20, "영남권", "bridge")
    private val seoul = Region("seoul", "서울", 37.57, 126.98, "남산타워", "🗼", listOf("#1E3A8A", "#F59E0B"), 10, "수도권", "city")
    private val stops = listOf(
        CourseStop("mock-nami", "남이섬", 37.79, 127.52, "attraction", "gangwon", "지역 명소 · 새 지역 스탬프", newRegionStamp = true, newHiddenStamp = true),
        CourseStop("mock-seorak", "설악산 국립공원", 38.12, 128.47, "attraction", "gangwon", "강원의 대표 명산", newHiddenStamp = true),
        CourseStop("mock-fest", "속초 바다 축제 (시연용)", 38.2, 128.59, "festival", "gangwon", "이번 주말 진행 중인 축제", newHiddenStamp = true),
    )
    private val course = DriveCourse(
        "강원 바다·산 드라이브", "남이섬에서 설악산을 거쳐 속초 축제까지 — 강원 스탬프와 히든 3곳을 모아요.",
        stops, 4.6, 115, "mock", LimitedEvent("mock-fest", "속초 바다 축제 (시연용)", "20261116"), 87,
    )
    private fun home(synergy: Int, rank: Int, stamps: Int, hidden: Int, goal: String, titles: List<String> = emptyList()) = HomeSummary(
        synergy, rank, synergy / 4, 6, stamps, 17, hidden, 4, 14 + hidden, 47, titles, nextGoal = goal,
    )
    private val landmarks = listOf(
        LandmarkNearby(Attraction("a", "경복궁", lat = 37.58, lon = 126.98), 1500, true, 20),
        LandmarkNearby(Attraction("b", "N서울타워", lat = 37.55, lon = 126.99), 1900, false, 20),
        LandmarkNearby(Attraction("c", "서울 빛초롱 축제 (시연용)", lat = 37.57, lon = 126.98, kind = "festival"), 800, false, 30),
    )
    private val ioniq5n = VehicleCard("hyundai-ioniq-5-n", "현대", "아이오닉 5 N", "CUV", null, "현대 N 의 고성능 전기차", listOf(KeywordFact("마력", "최대 약 650마력")), rarity = "legendary")
    private val ioniq5 = VehicleCard("hyundai-ioniq-5", "현대", "아이오닉 5", "CUV", null, "레트로 감성의 전기 CUV", listOf(KeywordFact("가격", "5,000만 원대")), rarity = "common")
    private fun item(id: String, slot: String, key: String, name: String, unlocked: Boolean = true, hint: String = "") = DecorItem(id, slot, key, name, unlocked, hint)
    private val catalog = DecorCatalog(
        listOf(
            item("floor-concrete", "floor", "concrete", "콘크리트"), item("floor-asphalt", "floor", "asphalt", "아스팔트 차선"),
            item("floor-checker", "floor", "checker", "체커 플래그"), item("floor-wood", "floor", "wood", "우드 데크", false, "히든 스탬프 3개"),
            item("floor-neon", "floor", "neon", "네온 그리드"), item("floor-marble", "floor", "marble", "골드 마블"),
            item("prop-flag", "prop", "🚩", "깃발"), item("prop-checker", "prop", "🏁", "체커기"), item("prop-crown", "prop", "👑", "왕관"),
            item("region:gangwon", "prop", "⛰️", "강원 설악산"),
        ),
        unlocked = 22, total = 47,
    )
    private val book = StampBook(4, 17, listOf(Stamp(seoul, true), Stamp(gangwon, true), Stamp(busan, false)))

    // ---------------- 장면 ----------------
    private sealed interface Scene
    private data class Title(val big: String, val small: String, val lines: List<String> = emptyList()) : Scene
    private data object Onboarding : Scene
    private data class Travel(val course: DriveCourse?, val home: HomeSummary) : Scene
    private data class Driving(val visited: List<String>, val notice: String?) : Scene
    private data class Celebrate(val c: Celebration, val home: HomeSummary) : Scene
    private data object Ranking : Scene
    private data class Garage(val showroom: Showroom, val tab: Int) : Scene

    private var scene by mutableStateOf<Scene>(Title("", ""))
    private var caption by mutableStateOf("")
    private var step by mutableStateOf("")

    // ---------------- 프레임 기록 ----------------
    private val outDir = File("build/demo_video/frames").apply { deleteRecursively(); mkdirs() }
    private val concat = StringBuilder()
    private var frameNo = 0
    private val fps = 12

    private fun capture(seconds: Double) {
        if (rule.mainClock.autoAdvance) rule.waitForIdle()
        val view = rule.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val name = "%04d.png".format(frameNo++)
        File(outDir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        concat.append("file 'frames/$name'\nduration ${"%.3f".format(seconds)}\n")
    }

    /** 정지 장면: 프레임 1장 + 길이 */
    private fun hold(seconds: Double) = capture(seconds)

    /** 애니메이션 장면: 시계를 멈추고 프레임 단위로 진행하며 캡처 (무한 애니메이션이 있어도 멈추지 않게) */
    private fun animatedScene(s: Scene, cap: String, st: String, seconds: Double) {
        rule.mainClock.autoAdvance = false
        scene = s; caption = cap; step = st
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat((seconds * fps).toInt()) {
            rule.mainClock.advanceTimeBy(1000L / fps)
            capture(1.0 / fps)
        }
    }

    /** 정지 장면 전환 (무한 애니메이션 없는 화면) */
    private fun show(s: Scene, cap: String, st: String) {
        rule.mainClock.autoAdvance = true
        scene = s; caption = cap; step = st
        tick()
    }

    private fun tick() = rule.waitForIdle()

    @Test fun demo() {
        rule.setContent { CarDexTheme { Surface { Frame() } } }

        // 1. 타이틀
        show(Title("CarDex", "달리면서 모으는 여행 도감", listOf("Pleos Connect 차량용 앱 · AI 드라이브 코스 · 지역 스탬프 · 쇼룸")), "", "")
        hold(3.5)

        // 2. 첫 실행 안내 (실제 버튼 클릭으로 넘김)
        show(Onboarding, "첫 실행 안내 — 서비스 소개와 위치·주행 정보 사용 목적을 먼저 설명해요", "1")
        hold(2.5)
        rule.onNodeWithText("다음").performClick(); tick(); hold(2.5)
        rule.onNodeWithText("다음").performClick(); tick(); hold(3.0)

        // 3. 여행 홈 -> AI 코스 추천 (실제 '코스 추천받기' 클릭)
        val home0 = home(180, 5, 3, 2, "⛰️ 강원 마스터까지 35점")
        show(Travel(null, home0), "여행 홈 — 내 점수·순위·스탬프와 '다음 목표'를 한눈에", "2")
        hold(3.0)
        caption = "왕복 시간을 고르고 'AI 코스 추천' — 아직 안 가 본 지역·축제·명소를 엮어 줘요"
        tick(); hold(2.5)
        rule.onNodeWithText("코스 추천받기").performClick()
        show(Travel(course, home0), "AI 추천 코스 — 새 지역 스탬프 + 히든 스탬프 3곳 + 축제 한정 배지 기회", "3")
        rule.onNodeWithText("🏁 챌린지 시작 + Pleos 길안내").performScrollTo(); tick() // 코스 카드 전체가 보이도록
        hold(4.5)

        // 4. 챌린지 시작 -> 주행 모드 (실제 버튼 클릭)
        rule.onNodeWithText("🏁 챌린지 시작 + Pleos 길안내").performClick()
        show(Driving(emptyList(), "🏁 챌린지 시작! 완주하면 +87"), "챌린지 시작 → Pleos 내비로 길안내 · 주행 중엔 화면이 '주행 모드'로 잠겨요", "4")
        hold(3.5)
        show(Driving(listOf("mock-nami"), "✨ 히든 스탬프 '남이섬' 획득! +35 · 코스 1/3"), "명소 500m 안에 들어서면 Pleos 근접 알림으로 히든 스탬프 자동 획득 (음성 안내)", "4")
        hold(3.5)
        show(Driving(listOf("mock-nami", "mock-seorak"), "✨ 히든 스탬프 '설악산 국립공원' 획득! +35 · 코스 2/3"), "버튼은 '남은 곳 길안내' 하나 — 운전에 집중할 수 있게", "4")
        hold(3.0)

        // 5. 완주 / 마스터 축하 (정차 후 화면 연출)
        val home1 = home(472, 2, 4, 5, "🏆 이번 주 1위까지 6점", listOf("강원"))
        animatedScene(Celebrate(Celebration("🎖️", "코스 완주 + 축제 한정 배지!", "강원 바다·산 드라이브 · 완주 +87 · 배지 +50"), home1), "정차하면 축하 연출 — 축제 기간 안에 완주해야만 받는 한정 배지", "5", 3.0)
        animatedScene(Celebrate(Celebration("👑", "강원 마스터 등극!", "쇼룸에 왕관·골드 아이템이 열렸어요"), home1), "이번 달 강원 여행 점수 1위 → '강원 마스터' 칭호 (다른 사람이 넘으면 빼앗겨요)", "5", 3.0)

        // 6. 랭킹
        show(Ranking, "속도가 아닌 '방문·수집'으로만 경쟁 — 주간 랭킹과 시·도별 지역 마스터", "6")
        hold(4.0)

        // 7. 쇼룸 꾸미기
        val base = Showroom(car = ioniq5, floor = catalog.items[0], signText = "MY GARAGE")
        animatedScene(Garage(base, 0), "쇼룸 — 대표 차량을 전시하고 여행으로 얻은 아이템으로 꾸며요", "7", 2.0)
        val s1 = base.copy(car = ioniq5n)
        animatedScene(Garage(s1, 0), "대표 차량 변경 — 실제 차의 특징을 살린 카툰 일러스트 (로고 없음)", "7", 2.0)
        val s2 = s1.copy(background = gangwon, backgroundMastered = true, titles = listOf("강원"))
        animatedScene(Garage(s2, 1), "배경: 방문한 지역의 풍경 — 강원 설악산", "7", 2.0)
        val s3 = s2.copy(
            floor = catalog.items[4], light = item("light-aurora", "light", "#B388FF", "오로라"),
            pedestal = item("pedestal-neon", "pedestal", "neon", "네온 링"), signText = "강원 MASTER",
        )
        animatedScene(Garage(s3, 2), "바닥·조명·전시대·네온 간판 — 코스 완주·배지·마스터로 해금", "7", 2.5)
        val s4 = s3.copy(props = listOf(
            PlacedPropOut("prop-crown", 0.12f, 0.84f, 1.4f, catalog.items[8]),
            PlacedPropOut("region:gangwon", 0.88f, 0.84f, 1.3f, catalog.items[9]),
            PlacedPropOut("prop-checker", 0.2f, 0.62f, 1f, catalog.items[7]),
            PlacedPropOut("prop-flag", 0.8f, 0.62f, 1f, catalog.items[6]),
        ))
        animatedScene(Garage(s4, 6), "소품은 드래그로 자유롭게 배치 — 꾸미고 싶어서 다시 여행을 떠나요", "7", 3.0)

        // 8. 마무리
        show(Title("CarDex", "달릴수록 넓어지는 나의 대한민국", listOf(
            "Pleos SDK: FusedLocation(위치·근접 알림) · NaviHelper(길안내) · Vehicle(주행 상태) · TextToSpeech(음성)",
            "한국관광공사 TourAPI · LLM 코스 추천 · 덜 알려진 지역일수록 높은 점수",
        )), "", "")
        hold(4.5)

        File("build/demo_video/concat.txt").writeText(concat.toString() + "file 'frames/%04d.png'\n".format(frameNo - 1))
    }

    // ---------------- 화면 구성 ----------------
    @Composable
    private fun Frame() {
        Box(Modifier.fillMaxSize()) {
            when (val s = scene) {
                is Title -> TitleCard(s)
                Onboarding -> OnboardingScreen(onFinish = {})
                is Travel -> TravelScreen(s.course, false, landmarks, null, { _, _ -> }, {}, {}, {}, s.home)
                is Driving -> Column(Modifier.fillMaxSize()) {
                    // 주행 모드는 하단에 알림이 있어 자막을 위쪽 별도 줄에 둔다
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) { CaptionBar(caption, step, Modifier) }
                    Box(Modifier.weight(1f)) { DrivingScreen(
                    UiState(
                        isDriving = true, drivingSource = "차량 주행 상태",
                        challenge = Challenge(course, "2026-11-09T00:00:00Z", "2026-11-10T00:00:00Z", visited = s.visited),
                        driving = DrivingSnapshot(GeoPoint(37.9, 127.9), source = "Pleos FusedLocation"),
                        weekly = Leaderboard("2026-W46", null, emptyList(), RankRow(4, "me", "나", 60, true), 8),
                        notice = s.notice,
                    ),
                    {}, {}, null,
                ) }
                }
                is Celebrate -> {
                    TravelScreen(null, false, landmarks, null, { _, _ -> }, {}, {}, {}, s.home)
                    CelebrationOverlay(s.c, {}, animate = true)
                }
                Ranking -> {
                    val rows = listOf(
                        RankRow(1, "r5", "주말캠핑족", 124), RankRow(2, "me", "나", 118, isMe = true), RankRow(3, "r1", "강원도드라이버", 100),
                        RankRow(4, "r6", "서울출퇴근러", 68), RankRow(5, "r3", "전국일주중", 64),
                    )
                    RankingScreen(
                        Leaderboard("2026-W46", null, rows, rows[1], 7),
                        MastersResponse("2026-11", listOf(
                            RegionMaster(gangwon, RankRow(1, "me", "나", 152, true), 152, 0, true),
                            RegionMaster(busan, RankRow(1, "r2", "부산갈매기", 120), 0, 121),
                            RegionMaster(seoul, RankRow(1, "r6", "서울출퇴근러", 30), 20, 11),
                        ), listOf("강원")),
                        gangwon, {},
                    )
                }
                is Garage -> ShowroomScreen(
                    showroom = s.showroom, collection = listOf(
                        CollectionEntry(ioniq5, "2026-11-08T00:00:00Z", "2026-11-08T00:00:00Z"),
                        CollectionEntry(ioniq5n, "2026-11-09T00:00:00Z", "2026-11-09T00:00:00Z"),
                    ),
                    stampBook = book, decor = catalog, onRefresh = {}, onFeature = {}, onBackground = {}, onEquip = { _, _ -> },
                    onAddProp = {}, onMoveProp = { _, _, _, _ -> }, onResizeProp = {}, onRemoveProp = {}, animate = true, initialTab = s.tab,
                )
            }
            if (caption.isNotEmpty() && scene !is Driving) CaptionBar(caption, step, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
        }
    }

    @Composable
    private fun CaptionBar(text: String, step: String, modifier: Modifier) {
        Row(
            modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.78f))
                .padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (step.isNotEmpty()) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(Color(0xFF4DA3FF)), contentAlignment = Alignment.Center) {
                    Text(step, color = Color.White, fontWeight = FontWeight.Black, fontSize = 18.sp)
                }
            }
            Text(text, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun TitleCard(t: Title) {
        Row(
            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF0D1117), Color(0xFF1E3A8A), Color(0xFF6D28D9)))).padding(56.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp),
        ) {
            CarIllustration("hyundai-ioniq-5-n", "CUV", "legendary", Modifier.size(460.dp, 280.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(t.big, color = Color(0xFF8EC5FF), fontSize = 64.sp, fontWeight = FontWeight.Black)
                Text(t.small, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                t.lines.forEach { Text(it, color = Color.White.copy(alpha = 0.85f), fontSize = 20.sp, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
}
