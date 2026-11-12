package com.signalassist.demo

import com.signalassist.core.AlertLevel
import com.signalassist.core.AlertType
import com.signalassist.core.OutputPlan
import com.signalassist.core.SignalColor
import com.signalassist.core.SignalState
import com.signalassist.core.TonePattern
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * '내비와 함께 쓰기' 시연 영상: 운전자는 내비 화면을 보고, 신호 알림은 내비 위 카드로 뜬다.
 * 카드의 제목·문장·버튼·아이콘·떠 있는 시간은 앱 코드(AlertNotifier.buildCard)가 센텀 주행에서 실제로 만든 값이다.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*NavCompanionTest*' -Precord=true
 *   ./gradlew :demo:renderNavVideo        # build/video/모두의운전석_내비와함께.mp4
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/video/모두의운전석_내비와함께.mp4").absoluteFile
    val audio = AudioTimeline()
    encodeVideo(out, 1280, 720, audio) { emit -> NavVideo(audio).render(emit) }
}

private class RecordedCard(
    val index: Int, val type: AlertType, val level: AlertLevel, val title: String, val text: String,
    val actions: List<String>, val timeoutMs: Long, val icon: BufferedImage, val speech: String,
    val tone: TonePattern, val planTone: Boolean, val tapped: Boolean,
)

private class NavVideo(private val audio: AudioTimeline) {
    private val dir = listOf(File("../app/build/recording/nav_cards"), File("app/build/recording/nav_cards")).first { it.exists() }
    private val cards = File(dir, "cards.tsv").readLines().drop(1).filter { it.isNotBlank() }.map { l ->
        val c = l.split("\t")
        RecordedCard(c[0].toInt(), AlertType.valueOf(c[2]), AlertLevel.valueOf(c[3]), c[4], c[5], c[6].split("|"), c[7].toLong(),
            ImageIO.read(File(dir, c[8])), c[9], TonePattern.valueOf(c[10]), c[11] == "true", c[12] == "tap_query")
    }
    private val img = BufferedImage(1280, 720, BufferedImage.TYPE_3BYTE_BGR)
    private val g: Graphics2D = img.createGraphics().apply { setupHints() }
    private var total = 0L
    private var lastRoad = "해운대로"

    fun render(emit: (BufferedImage) -> Unit): Long {
        card(emit, 7_000) { opening() }
        card(emit, 5_500) { bootScene(it) }
        drive(emit)
        card(emit, 8_000) { closing() }
        g.dispose()
        return total
    }

    private fun card(emit: (BufferedImage) -> Unit, ms: Long, draw: (Long) -> Unit) {
        val frames = (ms * FPS / 1000.0).roundToInt()
        repeat(frames) { f ->
            val t = f * 1000L / FPS
            g.color = Pal.bg; g.fillRect(0, 0, 1280, 720)
            draw(t)
            fadeBlack(g, t, ms, 1280, 720)
            emit(img)
        }
        total += ms
    }

    private fun opening() {
        Fonts.center(g, "모두의 운전석 · 내비와 함께 쓰기", 640f, 200f, Fonts.b(54f), Pal.text)
        Fonts.center(g, "운전 중엔 내비를 봐야 합니다. 이 앱 화면만 보고 있을 수는 없습니다.", 640f, 280f, Fonts.r(28f), Pal.sky)
        Fonts.center(g, "그래서 알림이 내비 위로 찾아갑니다", 640f, 330f, Fonts.b(30f), Pal.amber)
        listOf(
            "① 시동을 걸면 알림 엔진이 저절로 켜집니다 (앱을 열 필요 없음)",
            "② 내비 위에 한눈 카드: 모양 아이콘 + 두세 단어 + 한 문장",
            "③ 카드에서 바로 '다시 듣기' · '지금 신호' (내비 화면은 그대로)",
            "④ 소리·음성은 그대로, 같은 종류 카드는 바꿔 끼워 쌓이지 않음",
        ).forEachIndexed { k, l -> Fonts.center(g, l, 640f, 410f + k * 46, Fonts.r(24f), Pal.text) }
    }

    private fun bootScene(t: Long) {
        Fonts.center(g, "시동 ON", 640f, 180f, Fonts.b(56f), Pal.text)
        Fonts.center(g, "부팅이 끝나면 BootReceiver 가 알림 엔진(포그라운드 서비스)을 켭니다", 640f, 240f, Fonts.r(24f), Pal.sky)
        if (t > 1_200) {
            val a = ((t - 1_200) / 400f).coerceIn(0f, 1f)
            val y = (300 + (1 - a) * -30).toInt()
            g.round(290, y, 700, 96, 18f, Color(36, 39, 46))
            g.round(310, y + 20, 56, 56, 12f, Pal.green)
            Fonts.center(g, "i", 338f, y + 60f, Fonts.b(32f), Color.WHITE)
            Fonts.draw(g, "모두의 운전석", 384f, y + 40f, Fonts.b(22f), Pal.text)
            Fonts.draw(g, "내비와 함께 동작 중 · 알림은 내비 위 카드로 뜹니다", 384f, y + 72f, Fonts.r(19f), Pal.dim)
        }
        if (t > 2_400) {
            Fonts.center(g, "첫 설정을 마친 경우에만 · 설정에서 끌 수 있음 · 체험(가상 신호) 모드로는 절대 시작하지 않음", 640f, 470f, Fonts.r(20f), Pal.dim)
            Fonts.center(g, "운전자는 바로 내비를 켜고 출발하면 됩니다", 640f, 540f, Fonts.b(30f), Pal.amber)
        }
    }

    private fun closing() {
        Fonts.center(g, "내비와 함께 쓰기", 640f, 150f, Fonts.b(48f), Pal.text)
        listOf(
            "카드 내용은 앱 코드(AlertNotifier)가 만든 실제 알림 · 화면 모양은 차량 헤드업 알림을 본뜬 그림",
            "앱 화면이 보이는 동안엔 카드를 띄우지 않음 (화면이 직접 크게 보여 줌 · 중복 방지)",
            "긴급 8초 · 경고 6초 · 주의 5초 · 안내 4초 동안 떠 있음",
            "마이크(사이렌 감지)는 시동 직후 화면 없이 켤 수 없어, 앱을 한 번 열면 다시 켜짐",
            "다음 단계: Pleos NaviHelper 연결 → 경로의 회전 안내도 카드로 (SDK 연결 대기)",
        ).forEachIndexed { k, l -> Fonts.center(g, l, 640f, 240f + k * 52, Fonts.r(23f), Pal.text) }
        Fonts.center(g, "예시 내비 화면은 실제 내비 앱이 아닙니다 · 지도 데이터 © OpenStreetMap contributors", 640f, 640f, Fonts.r(18f), Pal.dim)
    }

    // ---- 주행: 예시 내비 화면 + 카드 ----

    private fun drive(emit: (BufferedImage) -> Unit) {
        val centum = CentumRun(audio, standaloneIndex = null, precompute = false)
        val player = centum.player
        val n = player.frameCount
        val byIndex = cards.groupBy { it.index }
        val events = cards.map { it.index }
        val tl = ArrayList<Pair<Int, Float>>()
        var t = 0.0
        val end = (n - 1) / 10.0
        while (t <= end) {
            val idx = (t * 10).roundToInt().coerceAtMost(n - 1)
            val near = events.any { e -> t in (e / 10.0 - 3.0)..(e / 10.0 + 6.0) } || t < 2.0
            val rate = if (near) 1f else if (player.step(idx).speedKmh < 0.5f) 15f else 6f
            tl += idx to rate
            t += rate / FPS
        }
        val minimap = CentumMinimap(centum.map)
        val camImg = BufferedImage(960, 540, BufferedImage.TYPE_INT_RGB)
        var camIdx = -1
        var lastIdx = -1
        var shown: RecordedCard? = null
        var shownAt = 0L // 영상 시간(ms)
        tl.forEachIndexed { f, (idx, rate) ->
            val videoMs = f * 1000L / FPS
            for (i in (lastIdx + 1)..idx) byIndex[i]?.forEach { c ->
                shown = c; shownAt = videoMs + if (c.tapped) TAP_MS else 0L
                if (c.planTone) audio.add(total + shownAt, c.tone, OutputPlan(true, false, false, false))
            }
            lastIdx = idx
            val s = player.step(idx)
            if (camIdx != idx) {
                val vis = s.tlsDistM != null && s.tlsDistM!! <= 150f && s.signalColor != SignalColor.UNKNOWN
                val frame = centum.render3D(idx, s, if (vis) SignalState(s.signalColor) else null, (s.tlsDistM ?: 200f) + 12f)
                camImg.setRGB(0, 0, frame.width, frame.height, frame.argb, 0, frame.width)
                camIdx = idx
            }
            g.color = Pal.bg; g.fillRect(0, 0, 1280, 720)
            // 예시 내비 화면 (지도 크게)
            minimap.draw(g, s, 0, 0, 1280, 720)
            navChrome(s)
            // 전방 카메라 (작게)
            g.round(942, 452, 322, 188, 14f, Color(0, 0, 0, 200))
            g.drawImage(camImg, 948, 458, 310, 174, null)
            g.chip(954f, 464f, "전방 카메라 (시뮬레이션)", Color(0, 0, 0, 170), size = 12f)
            if (rate > 1f) g.chip(1140f, 400f, "▶▶ %.0f배속".format(rate), Pal.amber, Color.BLACK, size = 15f)
            // 카드
            val c = shown
            if (c != null) {
                val age = videoMs - shownAt
                if (c.tapped && age < 0) {
                    // 빨간불 카드는 이미 사라졌다 -> 정차 중 상태 알림의 '지금 신호' 버튼을 누른다
                    statusCard()
                    tap(age + TAP_MS)
                } else if (age < c.timeoutMs) {
                    val slide = when { age < 250 -> age / 250f; age > c.timeoutMs - 250 -> (c.timeoutMs - age) / 250f; else -> 1f }
                    drawCard(c, slide)
                    caption(c)
                }
            }
            fadeBlack(g, videoMs, tl.size * 1000L / FPS, 1280, 720)
            emit(img)
        }
        total += tl.size * 1000L / FPS
    }

    /** 내비 상단 안내 바 + '예시 화면' 표시 */
    private fun navChrome(s: com.signalassist.core.TraceStep) {
        g.color = Color(18, 20, 24); g.fillRect(0, 0, 500, 12)
        g.round(16, 10, 470, 88, 16f, Color(20, 60, 110, 245))
        Fonts.draw(g, "↑", 34f, 76f, Fonts.b(46f), Color.WHITE)
        if (s.road.isNotBlank() && !s.road.startsWith(":")) lastRoad = s.road
        Fonts.draw(g, "직진 · $lastRoad", 88f, 54f, Fonts.b(28f), Color.WHITE)
        Fonts.draw(g, "목적지까지 %.1f km".format(s.navDistM / 1000f), 88f, 86f, Fonts.r(20f), Color(200, 220, 240))
        g.round(16, 642, 190, 62, 14f, Color(0, 0, 0, 200))
        Fonts.draw(g, "%.0f".format(s.speedKmh), 32f, 688f, Fonts.b(38f), Color.WHITE)
        Fonts.draw(g, "km/h", 112f, 688f, Fonts.r(20f), Pal.dim)
        g.chip(222f, 660f, "예시 내비 화면 (실제 내비 앱 아님)", Color(0, 0, 0, 190), Pal.amber, size = 15f)
    }

    /** 차량 헤드업 알림 모양의 카드 (내용은 앱이 만든 그대로) */
    private fun drawCard(c: RecordedCard, slide: Float) {
        val w = 760; val h = 196; val x = CARD_X
        val y = (16 - (1 - slide) * (h + 30)).toInt()
        g.round(x + 4, y + 6, w, h, 26f, Color(0, 0, 0, 120))
        g.round(x, y, w, h, 26f, Color(34, 37, 44, 250))
        g.round(x, y, 12, h, 6f, Color(c.icon.getRGB(96, 110) or (0xFF shl 24)))
        g.drawImage(c.icon, x + 26, y + 18, 104, 104, null)
        Fonts.draw(g, c.title, x + 150f, y + 62f, Fonts.b(38f), Pal.text)
        Fonts.draw(g, c.text, x + 150f, y + 104f, Fonts.r(23f), Color(210, 214, 222))
        // 버튼 (큰 터치 영역)
        var bx = x + 150
        for (a in c.actions) {
            val bw = (Fonts.width(g, a, Fonts.b(22f)) + 56).toInt()
            g.round(bx, y + 128, bw, 52, 26f, Color(58, 63, 74))
            Fonts.center(g, a, bx + bw / 2f, y + 162f, Fonts.b(22f), Pal.sky)
            bx += bw + 16
        }
        Fonts.draw(g, "모두의 운전석", x + w - 150f, y + 30f, Fonts.r(15f), Pal.dim)
    }

    /** 늘 떠 있는 상태 알림 (알림 센터): '내비와 함께 동작 중' + 버튼 두 개 */
    private fun statusCard() {
        val w = 760; val h = 150; val x = CARD_X; val y = 16
        g.round(x + 4, y + 6, w, h, 26f, Color(0, 0, 0, 120))
        g.round(x, y, w, h, 26f, Color(34, 37, 44, 250))
        g.round(x + 26, y + 22, 52, 52, 12f, Pal.green)
        Fonts.center(g, "i", x + 52f, y + 60f, Fonts.b(30f), Color.WHITE)
        Fonts.draw(g, "모두의 운전석", x + 96f, y + 44f, Fonts.b(22f), Pal.text)
        Fonts.draw(g, "내비와 함께 동작 중 · 알림은 내비 위 카드로 뜹니다", x + 96f, y + 72f, Fonts.r(18f), Pal.dim)
        var bx = x + 96
        for (a in listOf("다시 듣기", "지금 신호")) {
            val bw = (Fonts.width(g, a, Fonts.b(22f)) + 56).toInt()
            g.round(bx, y + 86, bw, 52, 26f, Color(58, 63, 74))
            Fonts.center(g, a, bx + bw / 2f, y + 120f, Fonts.b(22f), Pal.sky)
            bx += bw + 16
        }
    }

    /** '지금 신호' 버튼을 손가락으로 누르는 표시 */
    private fun tap(t: Long) {
        val x = CARD_X + 96
        val bw0 = (Fonts.width(g, "다시 듣기", Fonts.b(22f)) + 56).toInt()
        val bw1 = (Fonts.width(g, "지금 신호", Fonts.b(22f)) + 56).toInt()
        val cx = x + bw0 + 16 + bw1 / 2.0; val cy = 16 + 112.0
        val r = 30 + 10 * (t / TAP_MS.toDouble())
        g.color = Color(255, 255, 255, 90); g.fill(Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r))
        g.color = Color.WHITE; g.stroke = BasicStroke(3f); g.draw(Ellipse2D.Double(cx - 26, cy - 26, 52.0, 52.0))
        g.chip(cx.toFloat() - 120, cy.toFloat() + 40, "정차 중, 상태 알림의 '지금 신호'를 누름", Color(0, 0, 0, 200), Pal.amber, size = 16f)
    }

    /** 아래 자막: 소리·음성 + 설명 */
    private fun caption(c: RecordedCard) {
        g.round(222, 560, 712, 76, 14f, Color(0, 0, 0, 200))
        val sound = if (c.planTone) "소리: ${toneName(c.tone)}" else "소리 없음"
        Fonts.draw(g, "$sound  →  음성 “${c.speech}”", 238f, 590f, Fonts.r(17f), Pal.sky)
        val note = when {
            c.tapped -> "내비 화면을 떠나지 않고 바로 답을 들음 · 확신할 때만 단정"
            c.level == AlertLevel.URGENT -> "긴급: 카드 8초 · 음악을 잠시 멈추고 즉시 안내"
            else -> "카드 %.0f초 후 자동으로 사라짐 · 같은 종류의 새 카드는 바꿔 끼움".format(c.timeoutMs / 1000f)
        }
        Fonts.draw(g, note, 238f, 622f, Fonts.r(17f), Pal.dim)
    }

    private companion object { const val TAP_MS = 1_200L; const val CARD_X = 504 }
}
