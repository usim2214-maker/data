package com.pleos.cardex

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.material3.Surface
import com.pleos.cardex.data.CollectionEntry
import com.pleos.cardex.data.DecorCatalog
import com.pleos.cardex.data.DecorItem
import com.pleos.cardex.data.KeywordFact
import com.pleos.cardex.data.PlacedPropOut
import com.pleos.cardex.data.Region
import com.pleos.cardex.data.Showroom
import com.pleos.cardex.data.Stamp
import com.pleos.cardex.data.StampBook
import com.pleos.cardex.data.VehicleCard
import com.pleos.cardex.ui.screens.ShowroomScreen
import com.pleos.cardex.ui.theme.CarDexTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 쇼룸 디자인 확인용 스크린샷 (app/build/screenshots 폴더에 PNG). 서버 없이 샘플 데이터로 렌더링. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1400dp-h720dp-land-mdpi")
class ShowroomScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val busan = Region("busan", "부산", 35.18, 129.08, "광안대교", "🌉", listOf("#0C4A6E", "#F472B6"), 20, "영남권", "bridge")
    private val jeju = Region("jeju", "제주", 33.5, 126.53, "한라산", "🍊", listOf("#EA580C", "#99F6E4"), 40, "제주권", "island")
    private val gangwon = Region("gangwon", "강원", 37.8, 128.2, "설악산", "⛰️", listOf("#14532D", "#A7F3D0"), 40, "강원권", "mountain")
    private val seoul = Region("seoul", "서울", 37.57, 126.98, "남산타워", "🗼", listOf("#1E3A8A", "#F59E0B"), 10, "수도권", "city")
    private val car = VehicleCard(
        "hyundai-ioniq-5", "현대", "아이오닉 5", "CUV", sampleImage(), "레트로 감성의 전기 CUV",
        listOf(KeywordFact("가격", "5,000만 원대")), rarity = "rare",
    )

    private val ioniq5n = VehicleCard(
        "hyundai-ioniq-5-n", "현대", "아이오닉 5 N", "CUV", null, "현대 N 의 고성능 전기차",
        listOf(KeywordFact("마력", "최대 약 650마력")), rarity = "legendary",
    )

    private fun item(id: String, slot: String, key: String, name: String, unlocked: Boolean = true, hint: String = "") =
        DecorItem(id, slot, key, name, unlocked, hint)

    private val catalog = DecorCatalog(
        listOf(
            item("floor-concrete", "floor", "concrete", "콘크리트"), item("floor-asphalt", "floor", "asphalt", "아스팔트 차선"),
            item("floor-checker", "floor", "checker", "체커 플래그"), item("floor-wood", "floor", "wood", "우드 데크", false, "히든 스탬프 3개"),
            item("floor-neon", "floor", "neon", "네온 그리드"), item("floor-marble", "floor", "marble", "골드 마블", false, "지역 마스터 1곳"),
            item("prop-flag", "prop", "🚩", "깃발"), item("prop-checker", "prop", "🏁", "체커기"), item("prop-trophy", "prop", "🏆", "트로피", false, "지역 마스터 1곳"),
            item("region:busan", "prop", "🌉", "부산 광안대교"), item("region:jeju", "prop", "🍊", "제주 한라산", false, "제주 스탬프 획득"),
            DecorItem("spot:brand-hmg-taean", "prop", "🏎️", "서킷 레이스카", true, "HMG 드라이빙 익스피리언스 센터 방문", legendary = true),
            DecorItem("spot:brand-hms-busan", "prop", "🌊", "부산 바다 파도", false, "현대 모터스튜디오 부산 방문", legendary = true),
            DecorItem("spot:brand-genesis-gangnam", "prop", "🦅", "윙 엠블럼 조형", false, "제네시스 강남 방문", legendary = true),
        ),
        unlocked = 14, total = 47,
    )
    private val book = StampBook(4, 17, listOf(Stamp(seoul, true), Stamp(busan, true), Stamp(gangwon, true), Stamp(jeju, false)))
    private val collection = listOf(CollectionEntry(car, "2026-11-08T00:00:00Z", "2026-11-08T00:00:00Z"))

    private fun props(vararg p: Triple<String, Float, Float>) = p.map { (k, x, y) ->
        PlacedPropOut(k, x, y, 1f, catalog.items.firstOrNull { it.key == k } ?: item("x", "prop", k, k))
    }

    private fun shot(name: String, showroom: Showroom, tab: Int) {
        rule.setContent {
            CarDexTheme {
                Surface {
                    ShowroomScreen(
                        showroom = showroom, collection = collection, stampBook = book, decor = catalog,
                        onRefresh = {}, onFeature = {}, onBackground = {}, onEquip = { _, _ -> }, onAddProp = {},
                        onMoveProp = { _, _, _, _ -> }, onResizeProp = {}, onRemoveProp = {}, animate = false, initialTab = tab,
                    )
                }
            }
        }
        rule.waitForIdle()
        val out = File("build/screenshots").apply { mkdirs() }
        // Robolectric 에서는 PixelCopy 대신 뷰를 소프트웨어 캔버스에 직접 그린다
        val view = rule.activity.window.decorView.rootView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun busanMaster() = shot(
        "showroom_busan_master",
        Showroom(
            car = car, background = busan, floor = catalog.items[2], light = item("light-gold", "light", "#FFD54F", "챔피언 골드"),
            pedestal = item("pedestal-gold", "pedestal", "gold", "골드 포디움"), signText = "부산 MASTER",
            props = props(Triple("🏆", 0.1f, 0.85f), Triple("🌉", 0.9f, 0.84f), Triple("🏁", 0.2f, 0.62f), Triple("🚩", 0.8f, 0.62f)),
            backgroundMastered = true, titles = listOf("부산"),
        ),
        tab = 2,
    )

    @Test fun gangwonNeon() = shot(
        "showroom_gangwon_neon",
        Showroom(
            car = ioniq5n, background = gangwon, floor = catalog.items[4], light = item("light-aurora", "light", "#B388FF", "오로라"),
            pedestal = item("pedestal-neon", "pedestal", "neon", "네온 링"), signText = "ROAD TRIPPER",
            props = props(Triple("🚩", 0.12f, 0.86f), Triple("🏁", 0.88f, 0.86f)),
        ),
        tab = 6,
    )

    @Test fun brandLegendaryProps() = shot(
        "showroom_brand_legendary",
        Showroom(
            car = ioniq5n, background = busan, floor = catalog.items[2], light = item("light-gold", "light", "#FFD54F", "챔피언 골드"),
            pedestal = item("pedestal-gold", "pedestal", "gold", "골드 포디움"), signText = "MOTORSTUDIO",
            props = props(Triple("🏎️", 0.15f, 0.85f), Triple("🏁", 0.85f, 0.85f)),
        ),
        tab = 6,
    )

    @Test fun defaultGarage() = shot("showroom_default", Showroom(car = null), tab = 1)

    private fun sampleImage(): String? =
        File("src/test/resources/sample_car.png").takeIf { it.exists() }?.absoluteFile?.toURI()?.toString()
}
