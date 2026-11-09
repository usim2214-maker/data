package com.pleos.cardex

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pleos.cardex.data.Attraction
import com.pleos.cardex.data.CatalogVehicle
import com.pleos.cardex.data.Challenge
import com.pleos.cardex.data.CollectionEntry
import com.pleos.cardex.data.CourseStop
import com.pleos.cardex.data.DailyMission
import com.pleos.cardex.data.DecorCatalog
import com.pleos.cardex.data.DecorItem
import com.pleos.cardex.data.DriveCourse
import com.pleos.cardex.data.HomeSummary
import com.pleos.cardex.data.KeywordFact
import com.pleos.cardex.data.LandmarkNearby
import com.pleos.cardex.data.Leaderboard
import com.pleos.cardex.data.MastersResponse
import com.pleos.cardex.data.PlacedPropOut
import com.pleos.cardex.data.QuizQuestion
import com.pleos.cardex.data.RankRow
import com.pleos.cardex.data.Region
import com.pleos.cardex.data.RegionMaster
import com.pleos.cardex.data.Showroom
import com.pleos.cardex.data.Stamp
import com.pleos.cardex.data.StampBook
import com.pleos.cardex.data.SynergyBonus
import com.pleos.cardex.data.SynergyScore
import com.pleos.cardex.data.TripReport
import com.pleos.cardex.data.VehicleCard
import com.pleos.cardex.pleos.NaviPlace
import com.pleos.cardex.ui.Celebration
import com.pleos.cardex.ui.UiState
import com.pleos.cardex.ui.components.CarViews
import com.pleos.cardex.ui.components.CelebrationOverlay
import com.pleos.cardex.ui.components.FlipCard
import com.pleos.cardex.ui.components.OfficialCarImages
import com.pleos.cardex.ui.components.TripReportCard
import com.pleos.cardex.ui.screens.CollectionScreen
import com.pleos.cardex.ui.screens.DrivingScreen
import com.pleos.cardex.ui.screens.HomeScreen
import com.pleos.cardex.ui.screens.QuizScreen
import com.pleos.cardex.ui.screens.RankingScreen
import com.pleos.cardex.ui.screens.ShowroomScreen
import com.pleos.cardex.ui.screens.SplashScreen
import com.pleos.cardex.ui.screens.TravelScreen
import com.pleos.cardex.ui.theme.CarDexTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * 시연 영상 (자연스러운 흐름): 실제 앱 화면을 애니메이션 시계에 맞춰 1초 24프레임으로 그려 ffmpeg 로 바로 인코딩.
 * 커서가 실제 버튼 위치로 이동해 터치(실제 클릭·드래그 입력)를 넣는다.
 * 시나리오: 시작 → 메인 → 추천 드라이브 선택·경로 설정 → 주행 중 차량 인식·수집 → 주차 리포트 → 도감 → 쇼룸 → 퀴즈 → 랭킹
 * 실행: ./scripts/make_demo_video.sh  (결과: docs/demo/cardex_demo.mp4)
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1400dp-h720dp-land-mdpi")
class DemoFlowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val fps = 24
    private val frameMs = 1000L / fps

    // ---------------- 시연 데이터 ----------------
    private val vehicles: List<CatalogVehicle> = run {
        val arr = org.json.JSONObject(File("../../backend/app/data/vehicle_catalog.json").readText()).getJSONArray("vehicles")
        val order = mapOf("legendary" to 0, "rare" to 1, "common" to 2)
        (0 until arr.length()).map { arr.getJSONObject(it) }.sortedBy { order[it.getString("rarity")] }
            .map { CatalogVehicle(it.getString("id"), it.getString("brand"), it.getString("model"), it.getString("body_type"), it.getString("rarity")) }
    }
    private fun card(id: String, line: String, seen: String, eco: String?, facts: List<Pair<String, String>>) = vehicles.first { it.id == id }.let {
        VehicleCard(id, it.brand, it.model, it.bodyType, null, line, facts.map { (k, v) -> KeywordFact(k, v) }, rarity = it.rarity, eco = eco, seenColor = seen)
    }
    private val ev9 = card("kia-ev9", "3열 대형 전기 SUV", "흰색", "ev", listOf("가격" to "7,300만 원대~", "차급" to "대형 SUV", "승차인원" to "6~7인승"))
    private val grandeur = card("hyundai-grandeur", "대한민국 대표 준대형 세단", "검은색", "eco_option", listOf("가격" to "3,800만 원대~", "차급" to "준대형 세단", "승차인원" to "5인승"))
    private val k5 = card("kia-k5", "스포티한 중형 세단", "회색", "eco_option", listOf("가격" to "2,800만 원대~", "차급" to "중형 세단", "승차인원" to "5인승"))
    private val ioniq6 = card("hyundai-ioniq-6", "유선형 전기 세단", "흰색", "ev", listOf("가격" to "5,200만 원대~", "차급" to "중형 세단", "승차인원" to "5인승"))
    private val ioniq5n = card("hyundai-ioniq-5-n", "현대 N 의 고성능 전기차", "파란색", "ev", listOf("가격" to "7,500만 원대~", "마력" to "최대 약 650마력", "차급" to "고성능 CUV"))

    private val seoul = Region("seoul", "서울", 37.57, 126.98, "남산타워", "🗼", listOf("#1E3A8A", "#F59E0B"), 10, "수도권", "city")
    private val gyeonggi = Region("gyeonggi", "경기", 37.4, 127.5, "수원 화성", "🏯", listOf("#134E4A", "#FDE68A"), 30, "수도권", "city")
    private val busan = Region("busan", "부산", 35.18, 129.08, "광안대교", "🌉", listOf("#0C4A6E", "#F472B6"), 20, "영남권", "bridge")
    private fun item(id: String, slot: String, key: String, name: String, unlocked: Boolean = true, hint: String = "") = DecorItem(id, slot, key, name, unlocked, hint)
    private val decor = DecorCatalog(
        listOf(
            item("floor-concrete", "floor", "concrete", "콘크리트"), item("floor-asphalt", "floor", "asphalt", "아스팔트 차선"),
            item("floor-checker", "floor", "checker", "체커 플래그"), item("floor-wood", "floor", "wood", "우드 데크"),
            item("floor-neon", "floor", "neon", "네온 그리드"), item("floor-marble", "floor", "marble", "골드 마블"),
            item("light-white", "light", "#FFFFFF", "화이트 스포트"), item("light-gold", "light", "#FFD54F", "챔피언 골드"),
            item("pedestal-basic", "pedestal", "basic", "기본 턴테이블"), item("pedestal-gold", "pedestal", "gold", "골드 포디움"),
            item("prop-flag", "prop", "🚩", "깃발"), item("prop-checker", "prop", "🏁", "체커기"), item("prop-trophy", "prop", "🏆", "트로피"),
            item("eco-bike", "prop", "🚲", "자전거"), item("eco-tree", "prop", "🌳", "큰 나무"),
        ),
        unlocked = 22, total = 59,
    )
    private val stamps = StampBook(3, 17, listOf(Stamp(seoul, true), Stamp(gyeonggi, true), Stamp(busan, false)))
    private val stops = listOf(
        CourseStop("brand-hms-goyang", "현대 모터스튜디오 고양", 37.67, 126.75, "brand", "gyeonggi", "브랜드 체험 스팟 · 전설 아이템", newHiddenStamp = true),
        CourseStop("mock-lake", "일산 호수공원", 37.66, 126.77, "attraction", "gyeonggi", "지역 명소", newHiddenStamp = true),
        CourseStop("mock-heyri", "파주 헤이리 예술마을", 37.79, 126.7, "attraction", "gyeonggi", "새 지역 명소 · 히든", newHiddenStamp = true),
    )
    private val course = DriveCourse(
        "🌿 고양·파주 모터스튜디오 드라이브", "모터스튜디오 고양에서 호수공원을 지나 헤이리까지, 가까운 3곳을 짧게 잇는 저탄소 코스예요.",
        stops, 1.6, 95, "mock", null, 77, estKm = 96.0, estCo2Kg = 13.4, eco = true,
    )
    private val landmarks = listOf(
        LandmarkNearby(Attraction("brand-hms-goyang", "현대 모터스튜디오 고양", lat = 37.67, lon = 126.75, kind = "brand"), 5200, false, 40),
        LandmarkNearby(Attraction("mock-lake", "일산 호수공원", lat = 37.66, lon = 126.77), 6100, false, 30),
    )
    private fun homeSummary(dex: Int, legendary: Int, synergy: Int) = HomeSummary(
        dexOwned = dex, dexTotal = 28, legendaryOwned = legendary, legendaryTotal = 3, synergyTotal = synergy, weeklyRank = 3, weeklyPoints = 95,
        regionStamps = 3, regionTotal = 17, hiddenStamps = 2, collection = dex, itemsUnlocked = 22, itemsTotal = 59, nextGoal = "현대 차량 1종 더 모으면 '현대 컬렉터' +50",
        brandSpots = 1, brandTotal = 8,
        daily = DailyMission(date = "2026-11-09", week = "2026-W46", daysLeft = 6, evWeek = true,
            target = vehicles.first { it.id == "kia-ev6" }, found = false, bonus = 50, streak = 4, bestStreak = 7, greenDays = 3),
    )
    private fun entry(c: VehicleCard, n: Int, colors: List<String>) = CollectionEntry(c, "2026-11-08T00:00:00Z", "2026-11-09T00:00:00Z", n, seenColors = colors)
    private val baseShowroom = Showroom(
        car = ev9, background = seoul, floor = decor.items[0], light = decor.items[6], pedestal = decor.items[8], signText = "MY GARAGE",
        props = listOf(PlacedPropOut("prop-flag", 0.12f, 0.84f, 1.2f, decor.items[10])),
    )
    private val quizImg = File("build/demo_video/road_scene.png").takeIf { it.exists() }?.readBytes()

    // ---------------- 화면 상태 ----------------
    private enum class Scr { Splash, Home, Travel, Navi, Driving, Cam, Collection, Showroom, Quiz, Ranking, End }
    private var scr by mutableStateOf(Scr.Splash)
    private var splashP by mutableFloatStateOf(0f)
    private var drawerOpen by mutableStateOf(false)
    private var caption by mutableStateOf<String?>(null)
    private var cursor by mutableStateOf<Offset?>(null)
    private var pressed by mutableFloatStateOf(0f)
    private var camT by mutableFloatStateOf(0f)
    private var naviT by mutableFloatStateOf(0f)
    private var reveal by mutableStateOf<VehicleCard?>(null)
    private var revealFlipped by mutableStateOf(false)
    private var ui by mutableStateOf(
        UiState(
            onboardingDone = true,
            showroom = baseShowroom,
            collection = listOf(entry(ev9, 2, listOf("흰색", "검은색")), entry(grandeur, 5, listOf("검은색")), entry(k5, 1, listOf("회색")), entry(ioniq6, 1, listOf("흰색"))),
            synergy = SynergyScore(320, 190, 130, listOf(SynergyBonus("전동화 컬렉터", 50, ""), SynergyBonus("차 쉬는 날", 30, ""))),
            catalog = vehicles, home = homeSummary(4, 0, 320), landmarks = landmarks, decor = decor, stampBook = stamps,
            naviPlaces = listOf(NaviPlace("집", "서울 마포구 월드컵로", 37.56, 126.9, "home"), NaviPlace("회사", "경기 성남시 판교역로", 37.39, 127.11, "work")),
            quizStars = 4,
        ),
    )

    // ---------------- 프레임 출력 (ffmpeg 파이프) ----------------
    private var ff: Process? = null
    private var out: OutputStream? = null
    private lateinit var bmp: Bitmap
    private lateinit var buf: ByteBuffer
    private var frames = 0

    private fun startEncoder(w: Int, h: Int) {
        val dst = File("build/demo_flow").apply { mkdirs() }
        ff = ProcessBuilder(
            "ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgra", "-s", "${w}x$h", "-r", "$fps", "-i", "-",
            "-vf", "format=yuv420p", "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-movflags", "+faststart", File(dst, "cardex_demo.mp4").absolutePath,
        ).redirectErrorStream(true).start()
        out = ff!!.outputStream.buffered(1 shl 22)
        bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf = ByteBuffer.allocate(w * h * 4)
    }

    private fun frame(n: Int = 1, each: (Int) -> Unit = {}) = repeat(n) { i ->
        each(i)
        rule.mainClock.advanceTimeBy(frameMs)
        val view = rule.activity.window.decorView.rootView
        bmp.eraseColor(android.graphics.Color.BLACK)
        view.draw(android.graphics.Canvas(bmp))
        buf.clear(); bmp.copyPixelsToBuffer(buf)
        out!!.write(buf.array()); frames++
    }

    private fun hold(sec: Double) = frame((sec * fps).roundToInt())
    private fun ease(t: Float) = if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2).let { it * it } / 2

    // ---------------- 커서·터치 ----------------
    private fun centerOf(text: String, dy: Float = 0f): Offset {
        val b = rule.onAllNodesWithText(text, substring = true, useUnmergedTree = true).onFirst().fetchSemanticsNode().boundsInRoot
        return Offset((b.left + b.right) / 2, (b.top + b.bottom) / 2 + dy)
    }

    private fun moveTo(p: Offset, sec: Double = 0.6) {
        val from = cursor ?: Offset(p.x + 160f, p.y + 120f)
        val n = (sec * fps).roundToInt().coerceAtLeast(1)
        frame(n) { i -> val t = ease((i + 1f) / n); cursor = Offset(from.x + (p.x - from.x) * t, from.y + (p.y - from.y) * t) }
    }

    private fun tap(p: Offset) {
        moveTo(p)
        frame(3) { i -> pressed = (i + 1) / 3f }
        rule.onRoot().performTouchInput { click(p) }
        frame(5) { i -> pressed = 1f - (i + 1) / 5f }
    }

    private fun tapText(text: String, dy: Float = 0f) = tap(centerOf(text, dy))

    /** 실제 드래그 입력을 프레임마다 조금씩 (스크롤·360° 회전) */
    private fun drag(from: Offset, dx: Float, dy: Float, sec: Double) {
        moveTo(from)
        rule.onRoot().performTouchInput { down(from) }
        pressed = 1f
        val n = (sec * fps).roundToInt()
        var last = from
        frame(n) { i ->
            val t = ease((i + 1f) / n)
            val p = Offset(from.x + dx * t, from.y + dy * t)
            rule.onRoot().performTouchInput { moveTo(p) }
            cursor = p; last = p
        }
        rule.onRoot().performTouchInput { up() }
        pressed = 0f
        frame(4)
    }

    private fun go(s: Scr) { drawerOpen = false; scr = s }

    private fun openMenu(item: String) {
        tap(if (scr == Scr.Home) Offset(46f, 46f) else Offset(32f, 28f)) // ☰ 아이콘 위치
        hold(0.6)
        tapText(item)
    }

    // ---------------- 시나리오 ----------------
    @Test fun demo() {
        val ctx = rule.activity
        // 공식 이미지: 시연에 나오는 차 (턴테이블·회전용 360° 프레임 포함)
        val spinIds = listOf("kia-ev9", "hyundai-ioniq-5-n")
        val urls = vehicles.flatMap { OfficialTestImages.urlsFor(ctx, it.id) } + spinIds.flatMap { id ->
            when (val v = OfficialCarImages.views(ctx, id)) {
                is CarViews.Spin -> (0 until v.frames).map { v.withColor(null, if (id == "kia-ev9") "흰색" else "파란색").frameUrl(it) }
                else -> emptyList()
            }
        }
        OfficialTestImages.prime(ctx, urls)
        runBlocking { urls.forEach { OfficialCarImages.load(ctx, it) } } // 메모리에 올려 깜빡임 없이
        val rear = loadRear()

        rule.mainClock.autoAdvance = false
        rule.setContent { Host(rear) }
        startEncoder(rule.activity.window.decorView.width, rule.activity.window.decorView.height)
        try { script() } finally { out?.close(); ff?.waitFor() }
        println("demo frames: $frames (${frames / fps}s)")
    }

    private fun script() {
        // 1) 시작 화면
        frame(60) { i -> splashP = (i + 1) / 60f }
        scr = Scr.Home; splashP = 0f
        caption = "앱을 켜면 내 대표 차량이 있는 쇼룸과 길찾기가 바로"
        hold(3.2)

        // 2) 추천 드라이브 선택 → 코스 추천 → 경로 설정
        caption = "추천에서 'AI 추천 드라이브' 선택"
        tapText("AI 추천 드라이브"); go(Scr.Travel); hold(1.0)
        caption = "가까운 곳 위주의 저탄소 코스로 추천받기"
        tapText("저탄소 코스"); ui = ui.copy(ecoCourse = true); hold(0.4)
        tapText("코스 추천받기"); ui = ui.copy(courseLoading = true); hold(1.2)
        ui = ui.copy(courseLoading = false, course = course, courseBy = "Pleos LLM"); hold(1.2)
        drag(Offset(520f, 600f), 0f, -420f, 1.2); hold(0.8)
        caption = "코스를 Pleos 내비로 보내 길안내 시작"
        tapText("챌린지 시작")
        ui = ui.copy(challenge = Challenge(course, "2026-11-09T10:00:00Z", "2026-11-10T10:00:00Z"), notice = "🧭 Pleos 내비로 3곳 경로를 보냈어요")
        hold(1.0)
        go(Scr.Navi); cursor = null
        frame(84) { i -> naviT = (i + 1) / 84f }

        // 3) 주행 중 차량 인식 → 수집
        caption = "주행 중에는 화면이 잠기고 음성으로만 안내"
        ui = ui.copy(notice = null, isDriving = true, drivingSource = "차량 주행 상태"); go(Scr.Driving); hold(2.0)
        caption = "앞 차를 YOLOv8 이 감지 → 도감 28종 중 차종 판별"
        go(Scr.Cam); frame(144) { i -> camT = (i + 1) / 144f }
        caption = "카드 수집! (화면은 정차 후, 주행 중엔 음성 안내)"
        ui = ui.copy(notice = "🃏 새 카드: 현대 아이오닉 5 N — 전설!"); go(Scr.Driving); hold(2.2)

        // 4) 주차 → 주행 리포트 → 카드 공개
        caption = "주차하면 오늘의 드라이브 리포트"
        ui = ui.copy(isDriving = false, notice = null, tripReport = TripReport(
            distanceKm = 23.4f, driveMinutes = 41, newCards = listOf("현대 아이오닉 5 N"), newLegendary = 1, stamps = listOf("🏢 현대 모터스튜디오 고양"),
            points = 140, dailyFound = false, streak = 5, greenNew = 0, greenDays = 3, totalKm = 312.5f, trips = 12,
            headline = "23.4km 드라이브 · 새 카드 1장 · 스탬프 1개 · 140점", tts = "", nextGoal = "현대 차량 1종 더 모으면 '현대 컬렉터' +50",
        ))
        go(Scr.Home); hold(3.0)
        tapText("확인"); ui = ui.copy(tripReport = null)
        reveal = ioniq5n; hold(1.2)
        ui = ui.copy(celebration = Celebration("🌟", "LEGENDARY 카드 획득!", "현대 아이오닉 5 N · 내가 본 파란색 그대로")); hold(2.6)
        ui = ui.copy(celebration = null)
        caption = "카드 뒷면에는 내가 고른 정보"
        revealFlipped = true; hold(2.0); revealFlipped = false; hold(0.8)
        reveal = null
        ui = ui.copy(
            collection = listOf(entry(ioniq5n, 1, listOf("파란색"))) + ui.collection,
            home = homeSummary(5, 1, 460), synergy = SynergyScore(460, 290, 170, listOf(SynergyBonus("전동화 컬렉터", 50, ""), SynergyBonus("차 쉬는 날", 30, ""))),
        )
        hold(0.6)

        // 5) 도감
        caption = "☰ 메뉴 → 도감"
        openMenu("모은 차 카드"); go(Scr.Collection); hold(1.4)
        caption = "모은 차는 실제 차로, 드래그하면 360° 회전"
        val dragHint = centerOf("드래그해서 360°", dy = -70f)
        drag(dragHint, -260f, 0f, 2.4); hold(0.6)
        caption = "색상 칩으로 다른 색도 보기"
        val chipRow = centerOf("🎯파란색")
        tap(Offset(chipRow.x - 120f, chipRow.y))
        hold(1.6)
        caption = "못 모은 차는 실루엣 — 다음엔 무엇을 만날까?"
        drag(Offset(900f, 620f), 0f, -380f, 1.6); hold(1.4)
        drag(Offset(900f, 260f), 0f, 380f, 1.2); hold(0.4)
        caption = "새 카드를 쇼룸 대표 차량으로"
        tapText("쇼룸에 전시"); ui = ui.copy(showroom = ui.showroom.copy(car = ioniq5n)); hold(0.8)

        // 6) 쇼룸 꾸미기
        caption = "쇼룸 꾸미기 — 여행·수집으로 아이템 해금"
        openMenu("대표 차·배경 꾸미기"); go(Scr.Showroom); hold(1.6)
        tapText("바닥"); hold(0.6)
        tapText("골드 마블"); ui = ui.copy(showroom = ui.showroom.copy(floor = decor.items[5])); hold(1.0)
        tapText("조명"); hold(0.5)
        tapText("챔피언 골드"); ui = ui.copy(showroom = ui.showroom.copy(light = decor.items[7])); hold(1.0)
        tapText("전시대"); hold(0.5)
        tapText("골드 포디움"); ui = ui.copy(showroom = ui.showroom.copy(pedestal = decor.items[9])); hold(2.0)

        // 7) 퀴즈 · 랭킹
        if (quizImg != null) {
            caption = "아이와 함께 — 지나간 차 색깔 맞히기"
            openMenu("아이와 차 맞히기"); go(Scr.Quiz); hold(1.6)
            tapText("파란색"); ui = ui.copy(quizPicked = "파란색", quizStars = 5); hold(2.2)
        }
        caption = "주간 랭킹 · 지역 마스터 (속도 경쟁 없이 방문·수집으로)"
        openMenu("주간 랭킹"); go(Scr.Ranking); hold(2.6)

        // 8) 메인으로 돌아와 마무리
        caption = null
        tap(Offset(1370f, 30f)); go(Scr.Home); cursor = null; hold(3.0)
        go(Scr.End); hold(3.5)
    }

    private fun loadRear(): ImageBitmap? {
        val ctx = rule.activity
        val v = OfficialCarImages.views(ctx, "hyundai-ioniq-5-n") as? CarViews.Spin ?: return null
        val u = v.withColor(null, "파란색").frameUrl(21) // 뒤쪽 3/4
        val f = OfficialCarImages.cacheFile(ctx, u)
        if (!f.exists()) OfficialTestImages.prime(ctx, listOf(u))
        return BitmapFactory.decodeFile(f.path)?.asImageBitmap()
    }

    // ---------------- 화면 구성 ----------------
    @Composable
    private fun Host(rear: ImageBitmap?) = CarDexTheme {
        Surface {
            Box(Modifier.fillMaxSize()) {
                Crossfade(scr, animationSpec = tween(450), label = "scr") { s ->
                    when (s) {
                        Scr.Splash -> SplashScreen(progress = splashP)
                        Scr.Navi -> NaviIllustration(naviT)
                        Scr.Driving -> DrivingScreen(ui, {}, {}, null)
                        Scr.Cam -> RoadCam(camT, rear)
                        Scr.End -> EndCard()
                        else -> Menu(s)
                    }
                }
                ui.tripReport?.let { TripReportCard(it) {} }
                reveal?.let { c ->
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                        FlipCard(c, Modifier.size(360.dp, 500.dp), flipped = revealFlipped)
                    }
                }
                ui.celebration?.let { CelebrationOverlay(it, {}) }
                ui.notice?.takeIf { !ui.isDriving }?.let { msg ->
                    Text(msg, color = Color.Black, fontSize = 22.sp, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp)
                        .clip(RoundedCornerShape(16.dp)).background(Color(0xFFFFC94D)).padding(16.dp))
                }
                caption?.let { c ->
                    Text(c, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.BottomStart).padding(18.dp).clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.62f)).padding(horizontal = 16.dp, vertical = 9.dp))
                }
                cursor?.let { p ->
                    Canvas(Modifier.fillMaxSize()) {
                        if (pressed > 0f) drawCircle(Color.White.copy(alpha = 0.35f * pressed), 34f + 16f * pressed, p)
                        drawCircle(Color.White.copy(alpha = 0.9f), 13f, p)
                        drawCircle(Color.Black.copy(alpha = 0.4f), 13f, p, style = Stroke(2f))
                    }
                }
            }
        }
    }

    @Composable
    private fun Menu(s: Scr) {
        val drawer = rememberDrawerState(DrawerValue.Closed)
        LaunchedEffect(drawerOpen) { if (drawerOpen) drawer.open() else drawer.close() }
        val route = when (s) { Scr.Collection -> "collection"; Scr.Showroom -> "showroom"; Scr.Travel -> "travel"; Scr.Quiz -> "quiz"; Scr.Ranking -> "ranking"; else -> "home" }
        ModalNavigationDrawer(drawerState = drawer, drawerContent = { AppDrawer(route) {} }) {
            if (s == Scr.Home) {
                HomeScreen(ui, onMenu = { drawerOpen = true }, onSearch = {}, onPlace = {}, onVoice = {}, onOpen = {})
            } else {
                val title = mapOf(Scr.Travel to "여행", Scr.Collection to "도감", Scr.Showroom to "쇼룸", Scr.Quiz to "퀴즈", Scr.Ranking to "랭킹")[s].orEmpty()
                SubScreen(title, onMenu = { drawerOpen = true }, onHome = {}) {
                    when (s) {
                        Scr.Travel -> TravelScreen(ui.course, ui.courseLoading, landmarks, ui.challenge, { _, _ -> }, {}, {}, {}, ui.home, ui.courseBy, ui.ecoCourse, {})
                        Scr.Collection -> CollectionScreen(ui.collection, ui.synergy, ui.showroom.car?.id, {}, {}, {}, onColor = { id, code ->
                            ui = ui.copy(collection = ui.collection.map { if (it.card.id == id) it.copy(card = it.card.copy(colorCode = code)) else it })
                        }, catalog = vehicles, home = ui.home)
                        Scr.Showroom -> ShowroomScreen(ui.showroom, ui.collection, stamps, decor, {}, {}, {}, { _, _ -> }, {}, { _, _, _, _ -> }, {}, {}, initialTab = 2)
                        Scr.Quiz -> QuizScreen(ui, {}, {}, {})
                        Scr.Ranking -> {
                            val rows = listOf(RankRow(1, "r5", "주말캠핑족", 124), RankRow(2, "me", "나", 118, isMe = true), RankRow(3, "r1", "강원도드라이버", 100),
                                RankRow(4, "r6", "서울출퇴근러", 68), RankRow(5, "r3", "전국일주중", 64))
                            RankingScreen(Leaderboard("2026-W46", null, rows, rows[1], 7), MastersResponse("2026-11", listOf(
                                RegionMaster(gyeonggi, RankRow(1, "me", "나", 152, true), 152, 0, true),
                                RegionMaster(busan, RankRow(1, "r2", "부산갈매기", 120), 0, 121),
                                RegionMaster(seoul, RankRow(1, "r6", "서울출퇴근러", 30), 20, 11),
                            ), listOf("경기")), gyeonggi, {})
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    /** 내비 연동 장면 (실제 Pleos 내비 화면 대신 쓰는 단순한 지도 그림) */
    @Composable
    private fun NaviIllustration(t: Float) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0E1525))) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                for (i in 0..14) drawLine(Color(0xFF1B2638), Offset(0f, h * i / 14f), Offset(w, h * i / 14f + h * 0.2f), 18f)
                for (i in 0..10) drawLine(Color(0xFF1B2638), Offset(w * i / 10f, 0f), Offset(w * i / 10f - w * 0.15f, h), 14f)
                val pts = listOf(Offset(w * 0.18f, h * 0.82f), Offset(w * 0.42f, h * 0.55f), Offset(w * 0.55f, h * 0.62f), Offset(w * 0.78f, h * 0.25f))
                val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                val m = android.graphics.PathMeasure(path.asAndroidPath(), false)
                val seg = android.graphics.Path(); m.getSegment(0f, m.length * ease(t.coerceIn(0f, 0.85f) / 0.85f), seg, true)
                drawContext.canvas.nativeCanvas.drawPath(seg, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#4DA3FF"); style = android.graphics.Paint.Style.STROKE; strokeWidth = 14f; strokeCap = android.graphics.Paint.Cap.ROUND
                })
                drawCircle(Color(0xFF7CE38B), 16f, pts[0])
                pts.drop(1).forEachIndexed { i, p -> if (t > 0.3f + i * 0.2f) drawCircle(Color(0xFFFFC94D), 14f, p) }
            }
            Column(Modifier.padding(28.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xCC111827)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("🧭 Pleos 내비 길안내", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                stops.forEachIndexed { i, s -> Text("${i + 1}. ${s.name}", color = Color.White.copy(alpha = if (t > 0.3f + i * 0.2f) 1f else 0.4f), fontSize = 20.sp) }
                Text("약 1시간 36분 · 96km", color = Color(0xFF8AB4F8), fontSize = 20.sp)
            }
            Text("내비 연동 예시 화면 (실제 Pleos 내비 화면이 아님)", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
        }
    }

    /** 주행 영상 시점: 차선이 흐르고, 앞 차를 감지(박스) → 차종 판별 → 카드 (합성 장면) */
    @Composable
    private fun RoadCam(t: Float, rear: ImageBitmap?) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height; val hz = h * 0.42f
                drawRect(Brush.verticalGradient(listOf(Color(0xFF8DB7E6), Color(0xFFD9E8F5)), endY = hz))
                drawRect(Color(0xFF5E7A55), Offset(0f, hz - 30f), Size(w, 30f))
                val road = Path().apply { moveTo(w * 0.46f, hz); lineTo(w * 0.54f, hz); lineTo(w * 1.05f, h); lineTo(-w * 0.05f, h); close() }
                drawPath(road, Brush.verticalGradient(listOf(Color(0xFF55585E), Color(0xFF2E3035)), startY = hz, endY = h))
                // 흐르는 차선
                val flow = (t * 9f) % 1f
                for (k in 0 until 9) {
                    val z0 = ((k + flow) / 9f); val z1 = z0 + 0.045f
                    fun y(z: Float) = hz + (h - hz) * z * z
                    fun x(z: Float, lane: Float) = w * 0.5f + (lane * w * 0.55f) * z * z
                    listOf(-0.33f, 0.33f).forEach { lane ->
                        drawLine(Color.White.copy(alpha = 0.85f), Offset(x(z0, lane), y(z0)), Offset(x(z1, lane), y(z1)), 2f + 10f * z1)
                    }
                }
                // 앞 차 (공식 이미지 뒤쪽 프레임), 점점 가까워짐
                val near = ease((t / 0.55f).coerceIn(0f, 1f))
                val cw = w * (0.12f + 0.2f * near); val ch = cw * 0.55f
                val cx = w * (0.52f - 0.04f * near); val cy = hz + (h - hz) * (0.18f + 0.3f * near)
                rear?.let { img ->
                    drawImage(img, dstOffset = IntOffset((cx - cw / 2).toInt(), (cy - ch).toInt()), dstSize = IntSize(cw.toInt(), ch.toInt()))
                }
                // 감지 박스 (YOLOv8)
                if (t > 0.35f) {
                    val a = ((t - 0.35f) / 0.1f).coerceIn(0f, 1f)
                    val l = cx - cw * 0.42f; val tp = cy - ch * 0.82f; val bw = cw * 0.84f; val bh = ch * 0.8f
                    drawRoundRect(Color(0xFFFFC94D).copy(alpha = a), Offset(l, tp), Size(bw, bh), CornerRadius(8f), style = Stroke(5f))
                    drawRect(Color(0xFFFFC94D).copy(alpha = a), Offset(l, tp - 34f), Size(150f, 34f))
                    drawContext.canvas.nativeCanvas.drawText("car 0.92", l + 10f, tp - 9f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb((255 * a).toInt(), 0, 0, 0); textSize = 22f; isFakeBoldText = true
                    })
                }
            }
            Column(Modifier.align(Alignment.TopEnd).padding(24.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xD9111827)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("● REC  주행 영상", color = Color(0xFFFF6B6B), fontSize = 16.sp)
                Text(if (t > 0.35f) "① YOLOv8 차량 감지 ✓" else "① 차량 찾는 중…", color = Color.White, fontSize = 20.sp)
                if (t > 0.55f) Text("② 차종 판별: 현대 아이오닉 5 N ✓", color = Color.White, fontSize = 20.sp)
                if (t > 0.7f) Text("③ 차체 색: 파란색 → 공식 색상 매칭", color = Color.White, fontSize = 20.sp)
                if (t > 0.82f) Text("④ 도감 등록 · LEGENDARY 🌟", color = Color(0xFFFFD54F), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Text("시연용 합성 주행 장면", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
        }
    }

    @Composable
    private fun EndCard() {
        Box(Modifier.fillMaxSize()) {
            SplashScreen(progress = 0f, animate = false)
            Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("수집 · 쇼룸 · 여행 · 가족 퀴즈 — Pleos Connect 차량 앱", color = Color.White.copy(alpha = 0.8f), fontSize = 20.sp)
                Text("FusedLocation · NaviHelper · Vehicle · TTS · STT · LLM", color = Color(0xFF8AB4F8), fontSize = 16.sp)
            }
        }
    }
}
