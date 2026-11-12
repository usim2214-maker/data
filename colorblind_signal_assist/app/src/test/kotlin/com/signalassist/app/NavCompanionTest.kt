package com.signalassist.app

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Looper
import com.signalassist.app.alert.AlertNotifier
import com.signalassist.app.alert.GlanceCard
import com.signalassist.core.Alert
import com.signalassist.core.AlertType
import com.signalassist.core.DriverNeed
import com.signalassist.core.DriverProfile
import com.signalassist.core.DrivingAssistEngine
import com.signalassist.core.OutputPlan
import com.signalassist.core.TraceParser
import com.signalassist.core.TracePlayer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** '내비와 함께 쓰기': 내비 위 한눈 카드, 카드 버튼, 시동 시 자동 시작 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavCompanionTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val silent = OutputPlan(tone = false, voice = false, visualFlash = false, largeText = false)

    @Before fun grant() {
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        AssistBus.uiVisible = false
    }

    @After fun reset() { AssistBus.uiVisible = false }

    private fun posted(): List<Notification> = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications

    @Test fun redCardIsShortShapedAndOffersSignalQuery() {
        val card = GlanceCard.of(Alert(AlertType.RED_STOP, 0))
        assertEquals("■ 정지 · 빨간불", card.title)
        assertEquals(GlanceCard.Shape.OCTAGON_STOP, card.shape)
        assertEquals(6_000L, card.timeoutMs)
        assertTrue(card.offerSignalQuery)
        val icon = card.icon(96)
        assertEquals(96, icon.width)
        // 아이콘 가운데는 채워진 모양 (투명하지 않음)
        assertTrue(icon.getPixel(48, 30) ushr 24 > 0)
    }

    @Test fun urgentStaysLongerAndVehicleCardHasNoSignalQuery() {
        assertEquals(8_000L, GlanceCard.of(Alert(AlertType.RED_URGENT, 0)).timeoutMs)
        val belt = GlanceCard.of(Alert(AlertType.SEATBELT_UNBUCKLED, 0))
        assertFalse(belt.offerSignalQuery)
        assertEquals("⚠ 벨트", belt.title)
    }

    @Test fun cardHasActionsThatGoStraightToService() {
        val n = AlertNotifier(app).buildCard(Alert(AlertType.YELLOW_CAUTION, 0))
        assertEquals("▲ 주의 · 노란불", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("다시 듣기", "지금 신호"), n.actions.map { it.title.toString() })
        val pi = shadowOf(n.actions[1].actionIntent)
        assertTrue(pi.isServiceIntent)
        assertEquals(AssistService.ACTION_QUERY, pi.savedIntent.action)
        assertTrue(n.extras.containsKey(Notification.EXTRA_LARGE_ICON) || n.getLargeIcon() != null)
    }

    @Test fun cardShownOverNavButNotWhileAppOnScreen() {
        val notifier = AlertNotifier(app)
        AssistBus.uiVisible = true
        notifier.notify(Alert(AlertType.RED_STOP, 0), silent)
        assertTrue(posted().isEmpty())
        AssistBus.uiVisible = false
        notifier.notify(Alert(AlertType.RED_STOP, 0), silent)
        notifier.notify(Alert(AlertType.GREEN_GO, 1), silent) // 같은 신호 카드는 바꿔 끼운다
        assertEquals(1, posted().size)
        assertEquals("● 진행 · 초록불", posted()[0].extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun bootStartsEngineOnlyWhenOnboardedAndEnabled() {
        val settings = Settings(app)
        settings.onboarded = false
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
        settings.onboarded = true
        settings.navCompanion = false
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
        settings.navCompanion = true
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(AssistService::class.java.name, shadowOf(app).nextStartedService.component?.className)
    }

    @Test fun navCompanionDefaultsOn() {
        assertTrue(Settings(app).navCompanion)
    }

    /**
     * 시연 영상용: 센텀 주행에서 실제로 뜨는 카드(제목·문장·버튼·아이콘)를 그대로 저장한다.
     *   ./gradlew :app:testDebugUnitTest --tests '*NavCompanionTest*' -Precord=true
     */
    @Test fun recordNavCards() {
        assumeTrue(System.getProperty("record") == "true")
        val out = File("build/recording/nav_cards").apply { deleteRecursively(); mkdirs() }
        val trace = listOf(File("../sim/data/centum_trace.csv"), File("sim/data/centum_trace.csv")).first { it.exists() }
        val player = TracePlayer(TraceParser.parse(trace.readLines()))
        val profile = DriverProfile(setOf(DriverNeed.COLOR_VISION))
        val engine = DrivingAssistEngine(profile)
        val notifier = AlertNotifier(app)
        val log = StringBuilder("index\tt_ms\ttype\tlevel\ttitle\ttext\tactions\ttimeout_ms\ticon\tspeech\n")
        var k = 0
        for (i in 0 until player.frameCount) {
            val sample = player.sample(i)
            for (d in engine.process(sample.toInput()).deliveries) {
                val a = d.alert
                val n = notifier.buildCard(a)
                val card = GlanceCard.of(a)
                val iconName = "icon_%03d.png".format(k++)
                File(out, iconName).outputStream().use { card.icon(192).compress(Bitmap.CompressFormat.PNG, 100, it) }
                val actions = n.actions.joinToString("|") { it.title.toString() }
                log.append("$i\t${sample.timestampMs}\t${a.type}\t${a.level}\t${n.extras.getString(Notification.EXTRA_TITLE)}\t" +
                    "${n.extras.getCharSequence(Notification.EXTRA_TEXT)}\t$actions\t${card.timeoutMs}\t$iconName\t${a.speech}\n")
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        File(out, "cards.tsv").writeText(log.toString())
        println("RECORDED nav_cards $k")
    }
}
