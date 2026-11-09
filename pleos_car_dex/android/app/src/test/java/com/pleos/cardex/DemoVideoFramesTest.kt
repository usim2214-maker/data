package com.pleos.cardex

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.test.performScrollToIndex
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
    private fun home(synergy: Int, rank: Int, stamps: Int, hidden: Int, goal: String, titles: List<String> = emptyList(), dex: Int = 4, legendary: Int = 0) = HomeSummary(
        dexOwned = dex, dexTotal = 28, legendaryOwned = legendary, legendaryTotal = 3,
        synergyTotal = synergy, weeklyRank = rank, weeklyPoints = synergy / 4, gapToNext = 6, regionStamps = stamps, regionTotal = 17,
        hiddenStamps = hidden, collection = dex, itemsUnlocked = 14 + hidden, itemsTotal = 47, titles = titles, nextGoal = goal,
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
    private data class Recognize(val revealed: Int) : Scene
    private data object Keywords : Scene
    private data class CardMake(val flipped: Boolean, val card: VehicleCard, val legendary: Boolean = false) : Scene
    private data class Dex(val entries: List<VehicleCard>, val home: HomeSummary) : Scene
    private data object LegendaryGet : Scene

    // 카드 정보는 차량 정보 목록에서 가져온다 (AI 요약 미사용)
    private val aiCards: org.json.JSONObject? = null
    private val myKeywords: List<String> = aiCards?.getJSONArray("keywords")?.let { a -> (0 until a.length()).map { a.getString(it) } }
        ?: listOf("가격", "브랜드", "마력", "연비")
    private val aiNote: String = aiCards?.let { "※ 뒷면 요약: ${it.getString("model")} 실제 생성 결과 (${it.getString("generated_at").take(10)})" }
        ?: "※ 정보: 차량 정보 목록 (수치는 예시값 · 검수 필요)"

    private fun card(id: String, brand: String, model: String, body: String, rarity: String, oneLiner: String, sample: List<Pair<String, String>>): VehicleCard {
        val ai = aiCards?.getJSONObject("cards")?.optJSONObject(id)
        val facts = ai?.getJSONArray("facts")?.let { a -> (0 until a.length()).map { KeywordFact(a.getJSONObject(it).getString("keyword"), a.getJSONObject(it).getString("value")) } }
            ?: sample.map { KeywordFact(it.first, it.second) }
        return VehicleCard(id, brand, model, body, null, ai?.getString("one_liner") ?: oneLiner, facts, rarity = rarity)
    }

    private val ev9Card = card("kia-ev9", "기아", "EV9", "SUV", "rare", "3열 대형 전기 SUV",
        listOf("가격" to "약 7,000만 원대~", "브랜드" to "기아 (대한민국)", "마력" to "최대 약 380마력 (듀얼모터)", "연비" to "전비 약 4km/kWh"))
    private val ioniq5nCard = card("hyundai-ioniq-5-n", "현대", "아이오닉 5 N", "CUV", "legendary", "현대 N 의 고성능 전기차",
        listOf("가격" to "약 7,600만 원대~", "브랜드" to "현대자동차 N (고성능)", "마력" to "최대 약 650마력 (N 그린 부스트)", "연비" to "전비 약 3.5km/kWh"))
    private val g80Card = card("genesis-g80", "제네시스", "G80", "세단", "rare", "럭셔리 세단",
        listOf("가격" to "약 5,800만 원대~", "브랜드" to "제네시스", "마력" to "약 304~380마력", "연비" to "약 9~10km/L"))
    private val dexBefore by lazy { listOf(ioniq5, g80Card, card("kia-k5", "기아", "K5", "세단", "common", "스포티 중형 세단", emptyList()),
        card("hyundai-casper", "현대", "캐스퍼", "경형 SUV", "common", "귀여운 경형 SUV", emptyList())) }
    private val dexAfter by lazy { listOf(ioniq5nCard, ev9Card) + dexBefore }
    private val dexCatalog: List<com.pleos.cardex.data.CatalogVehicle> = File("../../backend/app/data/vehicle_catalog.json").takeIf { it.exists() }?.let { f ->
        val arr = org.json.JSONObject(f.readText()).getJSONArray("vehicles")
        val order = mapOf("legendary" to 0, "rare" to 1, "common" to 2)
        (0 until arr.length()).map { i -> arr.getJSONObject(i).let { o ->
            com.pleos.cardex.data.CatalogVehicle(o.getString("id"), o.getString("brand"), o.getString("model"), o.getString("body_type"), o.getString("rarity"))
        } }.sortedBy { order[it.rarity] ?: 3 }
    }.orEmpty()

    // 차량 인식 장면: RoadSceneRenderTest 가 그린 장면 + scripts/detect_demo_scene.py 의 실제 YOLOv8 감지 결과
    private data class Det(val label: String, val conf: Double, val x1: Int, val y1: Int, val x2: Int, val y2: Int)
    private val roadImage = File("build/demo_video/road_scene.png").takeIf { it.exists() }
        ?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
    private val roadCrop = File("build/demo_video/road_scene_crop.png").takeIf { it.exists() }
        ?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
    private val roadDets: List<Det> = File("build/demo_video/road_scene.json").takeIf { it.exists() }?.let { f ->
        val arr = org.json.JSONObject(f.readText()).getJSONArray("detections")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i); val b = o.getJSONObject("bbox")
            Det(o.getString("label"), o.getDouble("confidence"), b.getInt("x1"), b.getInt("y1"), b.getInt("x2"), b.getInt("y2"))
        }.sortedByDescending { it.conf }
    }.orEmpty()

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
        show(Title("CarDex", "도로 위 차량을 카드로 모으는 차량 도감", listOf("Pleos Connect 차량용 앱 · YOLOv8 차량 인식 · AI 맞춤 요약 카드 · 쇼룸")), "", "")
        hold(3.5)

        // 2. 첫 실행 안내 (실제 버튼 클릭으로 넘김)
        show(Onboarding, "첫 실행 안내 — 서비스 소개와 위치·주행 정보 사용 목적을 먼저 설명해요", "1")
        hold(2.5)
        rule.onNodeWithText("다음").performClick(); tick(); hold(2.0)
        rule.onNodeWithText("다음").performClick(); tick(); hold(2.0)

        // 3. 도감 홈
        show(Dex(dexBefore, home(180, 5, 3, 2, "도로에서 마주친 차량으로 다음 카드를 만들어 보세요", dex = 4, legendary = 0)), "앱의 첫 화면은 차량 도감 — 28종 중 4종 수집", "2")
        hold(2.5)
        // 실제 스크롤로 미발견 칸(실루엣)까지 내려가기: 0=요약카드, 1=필터, 2~5=보유 카드, 6~=미발견
        rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToIndex(6)
        caption = "아직 못 만난 차는 '???' 실루엣 — 전설 차량은 어디서 만날까?"; tick()
        hold(2.8)

        // 4. 차량 인식 -> 키워드 -> 정보 목록 -> 카드
        if (roadImage != null && roadDets.isNotEmpty()) {
            val cap = "YOLOv8 로 차량 인식 — 화면 속 차량을 찾아요 (박스는 실제 감지 결과)"
            show(Recognize(0), cap, "3"); hold(1.0)
            roadDets.indices.forEach { i -> show(Recognize(i + 1), cap, "3"); hold(0.7) }
            show(Recognize(roadDets.size + 1), "가장 가깝게 보인 차를 골라 카드로 만들어요", "3")
            hold(2.0)
        }
        show(Keywords, "카드 뒷면에 담을 정보는 내가 직접 골라요 — 도감의 차량 정보 목록에서 꺼내 보여줘요", "4")
        hold(3.0)
        show(CardMake(false, ev9Card), "카드 앞면 — 차량 일러스트", "5")
        hold(2.2)
        animatedScene(CardMake(true, ev9Card), "카드 뒷면 — 내가 고른 정보", "5", 3.0)

        // 5. 전설 차량 등장
        show(CardMake(false, ioniq5nCard, legendary = true), "희귀도가 있어요 — 일반 · 희귀 · 전설", "6")
        hold(1.5)
        animatedScene(LegendaryGet, "전설 차량을 만나면 특별 연출!", "6", 2.8)

        // 6. 도감이 채워짐 + 세트 보너스
        show(Dex(dexAfter, home(352, 3, 3, 2, "현대 차량 1종 더 모으면 '현대 컬렉터' +50", dex = 6, legendary = 1)), "도감 6/28 · 전설 1/3 — 브랜드 세트·재회 보너스로 시너지 점수", "7")
        hold(3.5)

        // 7. 쇼룸에 전시
        val base = Showroom(car = ioniq5n, floor = catalog.items[0], signText = "MY GARAGE")
        animatedScene(Garage(base, 0), "쇼룸 — 가장 아끼는 카드를 대표 차량으로 전시해요", "8", 2.5)
        val s2 = base.copy(background = gangwon, backgroundMastered = true, titles = listOf("강원"),
            floor = catalog.items[4], light = item("light-aurora", "light", "#B388FF", "오로라"),
            pedestal = item("pedestal-neon", "pedestal", "neon", "네온 링"), signText = "강원 MASTER")
        animatedScene(Garage(s2, 2), "배경·바닥·조명·전시대·간판 — 수집과 여행으로 아이템이 열려요", "8", 2.5)
        val s3 = s2.copy(props = listOf(
            PlacedPropOut("prop-crown", 0.12f, 0.84f, 1.4f, catalog.items[8]),
            PlacedPropOut("region:gangwon", 0.88f, 0.84f, 1.3f, catalog.items[9]),
            PlacedPropOut("prop-checker", 0.2f, 0.62f, 1f, catalog.items[7]),
            PlacedPropOut("prop-flag", 0.8f, 0.62f, 1f, catalog.items[6]),
        ))
        animatedScene(Garage(s3, 6), "소품은 드래그로 자유롭게 배치", "8", 2.5)

        // 8. 희귀 차량은 새 지역에서 -> 여행 (서브)
        val homeT = home(352, 3, 3, 2, "⛰️ 강원 마스터까지 35점", dex = 6, legendary = 1)
        show(Travel(null, homeT), "희귀 차량은 새 지역에서 — AI 가 아직 안 가 본 지역·축제로 코스를 추천해요", "9")
        hold(2.0)
        rule.onNodeWithText("코스 추천받기").performClick()
        show(Travel(course, homeT), "AI 추천 코스 — 새 지역 스탬프 + 명소 히든 스탬프 + 축제 한정 배지", "9")
        rule.onNodeWithText("🏁 챌린지 시작 + Pleos 길안내").performScrollTo(); tick()
        hold(3.0)
        rule.onNodeWithText("🏁 챌린지 시작 + Pleos 길안내").performClick()
        show(Driving(listOf("mock-nami"), "✨ 히든 스탬프 '남이섬' 획득! +35 · 코스 1/3"), "Pleos 내비로 길안내 · 주행 중엔 '주행 모드'로 잠기고 알림은 음성으로", "10")
        hold(3.0)
        animatedScene(Celebrate(Celebration("👑", "강원 마스터 등극!", "쇼룸에 왕관·골드 아이템이 열렸어요"), homeT), "정차하면 축하 연출 — 지역 마스터 칭호와 쇼룸 아이템", "10", 2.5)

        // 9. 랭킹
        show(Ranking, "속도가 아닌 '수집·방문'으로만 경쟁 — 주간 랭킹과 지역 마스터", "11")
        hold(3.0)

        // 10. 마무리
        show(Title("CarDex", "달릴수록 채워지는 나만의 차량 도감", listOf(
            "YOLOv8 차량 인식 · 내가 고른 정보 카드 · 28종 도감 · 쇼룸",
            "Pleos SDK: FusedLocation · NaviHelper · Vehicle · TextToSpeech  |  한국관광공사 TourAPI",
        )), "", "")
        hold(4.0)

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
                is Recognize -> RecognizeScene(s.revealed)
                Keywords -> com.pleos.cardex.ui.screens.KeywordSettingsScreen(myKeywords, {})
                is CardMake -> CardMakeScene(s.flipped, s.card, s.legendary)
                LegendaryGet -> {
                    CardMakeScene(false, ioniq5nCard, true)
                    CelebrationOverlay(Celebration("🌟", "LEGENDARY 카드 획득!", "현대 아이오닉 5 N · 전설 1/3"), {}, animate = true)
                }
                is Dex -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) { CaptionBar(caption, step, Modifier) }
                    Box(Modifier.weight(1f)) { com.pleos.cardex.ui.screens.CollectionScreen(
                    entries = s.entries.map { CollectionEntry(it, "2026-11-09T00:00:00Z", "2026-11-09T00:00:00Z", seenCount = if (it.id == "genesis-g80") 2 else 1) },
                    synergy = com.pleos.cardex.data.SynergyScore(s.home.synergyTotal, 180, 160, listOf(com.pleos.cardex.data.SynergyBonus("재회", 2, ""), com.pleos.cardex.data.SynergyBonus("그룹 트리오", 100, ""))),
                    featuredId = "hyundai-ioniq-5-n", onRefresh = {}, onFeature = {}, onDelete = {},
                    catalog = dexCatalog, home = s.home,
                ) }
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
            if (caption.isNotEmpty() && scene !is Driving && scene !is Dex) CaptionBar(caption, step, Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp))
        }
    }

    @Composable
    private fun RecognizeScene(revealed: Int) {
        val img = roadImage ?: return
        Row(Modifier.fillMaxSize().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1.45f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("🔎 차량 인식", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(if (revealed == 0) "분석 중…" else "YOLOv8n 감지 ${minOf(revealed, roadDets.size)}대",
                        color = Color(0xFF4DA3FF), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(Modifier.fillMaxWidth().aspectRatio(img.width / img.height.toFloat()).clip(RoundedCornerShape(16.dp))) {
                    androidx.compose.foundation.Image(img.asImageBitmap(), null, Modifier.fillMaxSize())
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        val sx = size.width / img.width; val sy = size.height / img.height
                        roadDets.take(minOf(revealed, roadDets.size)).forEach { d ->
                            val tl = androidx.compose.ui.geometry.Offset(d.x1 * sx, d.y1 * sy)
                            val sz = androidx.compose.ui.geometry.Size((d.x2 - d.x1) * sx, (d.y2 - d.y1) * sy)
                            drawRect(Color(0xFF4DA3FF), tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
                            drawRect(Color(0xFF4DA3FF), tl, androidx.compose.ui.geometry.Size(132f, 30f))
                        }
                    }
                    // 라벨 텍스트 (박스 좌상단)
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                        val sx = constraints.maxWidth / img.width.toFloat(); val sy = constraints.maxHeight / img.height.toFloat()
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        roadDets.take(minOf(revealed, roadDets.size)).forEach { d ->
                            Text(
                                "${d.label} ${"%.2f".format(d.conf)}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.offset(with(density) { (d.x1 * sx + 6).toDp() }, with(density) { (d.y1 * sy + 4).toDp() }),
                            )
                        }
                    }
                }
                Text("※ 시연용 그림 · 박스는 백엔드 YOLOv8 감지 코드의 실제 출력", color = Color.Gray, fontSize = 15.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("차량 → 카드", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                val done = revealed > roadDets.size
                Step("①", "YOLOv8 차량 인식", roadDets.take(minOf(revealed, roadDets.size)).joinToString(" · ") { "${it.label} ${"%.2f".format(it.conf)}" }.ifEmpty { "분석 중…" }, revealed > 0)
                Step("②", "가장 가깝게 보인 차 선택", if (done) "카드로 만들 차량" else "", done)
                if (done && roadCrop != null) {
                    androidx.compose.foundation.Image(
                        roadCrop.asImageBitmap(), null,
                        Modifier.size(220.dp, (220f * roadCrop.height / roadCrop.width).dp).clip(RoundedCornerShape(12.dp)),
                    )
                }
                Step("③", "차종 판별 → 정보 목록 조회", if (done) "내가 고른 항목만 꺼내기" else "", false)
                Step("④", "카드 완성 → 도감 등록", if (done) "앞면 일러스트 · 뒷면 요약" else "", false)
            }
        }
    }

    @Composable
    private fun CardMakeScene(flipped: Boolean, card: VehicleCard, legendary: Boolean = false) {
        Row(Modifier.fillMaxSize().padding(28.dp), horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (legendary) "전설 차량 발견!" else "새 차량 카드!", color = if (legendary) Color(0xFFFFB300) else Color(0xFFFFC94D), fontSize = 40.sp, fontWeight = FontWeight.Black)
                Step("①", "YOLOv8 차량 인식", "car ${"%.2f".format(roadDets.minOfOrNull { it.conf } ?: 0.4)}", true)
                Step("②", "내가 고른 정보", myKeywords.joinToString(" · "), true)
                Step("③", "차량 정보 목록에서 꺼내기", if (flipped) "카드 뒷면에 표시" else "준비 중…", flipped)
                Text(aiNote, color = Color.Gray, fontSize = 15.sp)
            }
            com.pleos.cardex.ui.components.FlipCard(card, Modifier.padding(bottom = 60.dp).size(330.dp, 470.dp), flipped = flipped)
        }
    }

    @Composable
    private fun Step(no: String, title: String, detail: String, ok: Boolean) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(no, color = if (ok) Color(0xFF4DA3FF) else Color.Gray, fontSize = 22.sp, fontWeight = FontWeight.Black)
            Column {
                Text(title + if (ok) "  ✓" else "", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                if (detail.isNotEmpty()) Text(detail, color = Color.White.copy(alpha = 0.75f), fontSize = 17.sp)
            }
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
