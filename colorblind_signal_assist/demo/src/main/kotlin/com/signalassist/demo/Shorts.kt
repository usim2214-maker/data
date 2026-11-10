package com.signalassist.demo

import com.signalassist.core.AlertLevel
import com.signalassist.core.Delivery
import com.signalassist.core.DriverProfile
import com.signalassist.core.DrivingAssistEngine
import com.signalassist.core.LampDetection
import com.signalassist.core.Scenario
import com.signalassist.core.ScenarioPlayer
import com.signalassist.core.ScenarioSample
import com.signalassist.core.SignalColor
import com.signalassist.core.SignalState
import com.signalassist.core.TripRecorder
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 숏폼(세로 1080x1920, 약 40초) 렌더러. 공모전 숏폼 챌린지용.
 *   ./gradlew :demo:renderShorts
 */
private const val SW = 1080
private const val SH = 1920

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/video/모두의운전석_숏폼.mp4").absoluteFile
    val r = ShortsRenderer()
    encodeVideo(out, SW, SH, r.audio) { emit -> r.render(emit) }
}

private class Clip(
    val scenario: Scenario,
    val fromMs: Long,
    val toMs: Long,
    val headline: String,
    val sub: String,
    val summaryHoldMs: Long = 0,
)

private class ShortsRenderer {
    val audio = AudioTimeline()
    private var globalMs = 0L

    private val clips = listOf(
        Clip(Scenario.STOP_AND_GO, 10_500, 16_800, "색 대신 소리로", "초록불이 되면 두 음이 올라갑니다"),
        Clip(Scenario.RED_LIGHT_RISK, 3_000, 8_200, "위험할 때만 긴급 경고", "신호등까지 거리를 재서 판단합니다"),
        Clip(Scenario.HEARING_DRIVE, 300, 3_800, "경고음 대신 화면으로", "소리를 듣기 어려운 운전자를 위해"),
        Clip(Scenario.HEARING_DRIVE, 28_300, 32_800, "사이렌도 화면으로", "실내 마이크로 긴급차량 감지"),
        Clip(Scenario.NIGHT_ARRIVAL, 12_500, 16_200, "내리기 전 뒷좌석 확인", "아이·반려동물 방치 예방", summaryHoldMs = 2_800),
    )

    fun render(emit: (BufferedImage) -> Unit): Long {
        val img = BufferedImage(SW, SH, BufferedImage.TYPE_3BYTE_BGR)
        target = img
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)

        card(g, emit, 3_600) { t -> hook(g, t) }
        card(g, emit, 2_400) { t -> hook2(g, t) }
        for (c in clips) playClip(g, emit, c)
        card(g, emit, 5_000) { t -> ending(g, t) }
        g.dispose()
        return globalMs
    }

    private fun frames(ms: Long) = (ms * FPS / 1000.0).roundToInt()

    private fun card(g: Graphics2D, emit: (BufferedImage) -> Unit, ms: Long, draw: (Long) -> Unit) {
        repeat(frames(ms)) { f ->
            val t = f * 1000L / FPS
            g.color = Pal.bg; g.fillRect(0, 0, SW, SH)
            draw(t)
            fade(g, t, ms)
            emit(target)
        }
        globalMs += ms
    }

    /** 프레임 버퍼 (그리기 대상이자 인코더로 보내는 이미지) */
    private lateinit var target: BufferedImage

    private fun fade(g: Graphics2D, t: Long, len: Long) {
        val f = 220L
        val a = when { t < f -> 1f - t.toFloat() / f; t > len - f -> (t - (len - f)).toFloat() / f; else -> 0f }
        if (a <= 0f) return
        val old = g.composite
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a.coerceIn(0f, 1f))
        g.color = Color.BLACK; g.fillRect(0, 0, SW, SH)
        g.composite = old
    }

    // ---- 카드 ----

    /** 같은 신호등을 보통 시야와 적록색맹(2형 색각) 시야로 나란히 */
    private fun hook(g: Graphics2D, t: Long) {
        Fonts.center(g, "빨간불과 초록불이", SW / 2f, 330f, Fonts.b(84f), Pal.text)
        Fonts.center(g, "구분되지 않는다면?", SW / 2f, 440f, Fonts.b(84f), Pal.text)
        val red = Color(255, 40, 30)
        val green = Color(0, 230, 170)
        val litRed = (t / 900) % 2 == 0L
        listOf("보통 시야" to false, "적록색맹 시야 (시뮬레이션)" to true).forEachIndexed { row, (label, cvd) ->
            val y = 640 + row * 420
            Fonts.center(g, label, SW / 2f, y.toFloat(), Fonts.r(40f), if (cvd) Pal.amber else Pal.dim)
            g.round(190, y + 40, 700, 230, 40f, Color(22, 22, 24))
            listOf(red, green).forEachIndexed { i, c ->
                val on = (i == 0) == litRed
                val col = if (on) c else Color(c.red / 7 + 18, c.green / 7 + 18, c.blue / 7 + 18)
                g.color = if (cvd) deutan(col) else col
                g.fillOval(270 + i * 340, y + 70, 170, 170)
            }
        }
    }

    private fun hook2(g: Graphics2D, t: Long) {
        Fonts.center(g, "경고음과 사이렌을", SW / 2f, 620f, Fonts.b(80f), Pal.text)
        Fonts.center(g, "듣기 어렵다면?", SW / 2f, 730f, Fonts.b(80f), Pal.text)
        val a = ((t - 600) / 500f).coerceIn(0f, 1f)
        val old = g.composite
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a)
        Fonts.center(g, "차량 정보를", SW / 2f, 1040f, Fonts.r(56f), Pal.sky)
        Fonts.center(g, "운전자에게 맞는 감각으로", SW / 2f, 1120f, Fonts.r(56f), Pal.sky)
        Fonts.center(g, "바꿔 알려 드립니다", SW / 2f, 1200f, Fonts.r(56f), Pal.sky)
        g.composite = old
    }

    private fun ending(g: Graphics2D, t: Long) {
        val cols = listOf(Pal.vermilion, Pal.amber, Pal.green, Pal.green)
        val lit = ((t / 700) % 4).toInt()
        for (i in 0 until 4) {
            g.color = if (i == lit) cols[i] else Pal.off
            g.fillOval((SW / 2f + (i - 1.5f) * 150 - 55).toInt(), 470, 110, 110)
        }
        Fonts.center(g, "모두의 운전석", SW / 2f, 790f, Fonts.b(110f), Pal.text)
        Fonts.center(g, "색·소리·작은 경고등에 의존하는", SW / 2f, 920f, Fonts.r(48f), Pal.text)
        Fonts.center(g, "차량 정보를 다른 감각으로", SW / 2f, 990f, Fonts.r(48f), Pal.text)
        g.chip(SW / 2f - 230, 1080f, "색각이상 · 청각장애 · 저시력 · 고령", Pal.line, size = 34f)
        Fonts.center(g, "Pleos Connect 차량 앱", SW / 2f, 1280f, Fonts.b(46f), Pal.sky)
        Fonts.center(g, "#HMG차량용앱개발공모전", SW / 2f, 1380f, Fonts.b(54f), Pal.amber)
        Fonts.center(g, "신호 인식 장면은 합성 영상 기반 시뮬레이션", SW / 2f, 1520f, Fonts.r(30f), Pal.dim)
    }

    // ---- 시나리오 장면 ----

    private fun playClip(g: Graphics2D, emit: (BufferedImage) -> Unit, c: Clip) {
        val profile = DriverProfile(c.scenario.targetNeeds)
        val player = ScenarioPlayer(c.scenario)
        val engine = DrivingAssistEngine(profile)
        val trip = TripRecorder()
        var summary: String? = null
        var signal = SignalState.UNKNOWN
        var lamps: List<LampDetection> = emptyList()
        var distance: Float? = null
        var banner: Delivery? = null
        var flash: Delivery? = null
        var flashAt = 0L
        var index = -1
        lateinit var sample: ScenarioSample
        lateinit var cam: BufferedImage

        fun step(toIndex: Int, live: Boolean) {
            while (index < toIndex) {
                index++
                sample = player.sample(index)
                val out = engine.process(sample.toInput())
                out.signal?.let { signal = it.state; lamps = it.detection.lamps; distance = it.distanceM }
                trip.update(out)?.let { summary = it.summaryKo() }
                if (!live) continue
                val now = sample.timestampMs
                for (d in out.deliveries) {
                    if (banner == null || !d.plan.silent) banner = d
                    if (d.plan.visualFlash) { flash = d; flashAt = now }
                    if (d.plan.tone) audio.add(globalMs + (now - c.fromMs), d.alert.tone)
                }
            }
        }

        // 장면 시작 전까지는 조용히 엔진을 돌려 상태를 맞춘다
        step((c.fromMs / 100).toInt() - 1, live = false)
        val clipMs = c.toMs - c.fromMs
        val total = clipMs + c.summaryHoldMs
        repeat(frames(total)) { f ->
            val t = f * 1000L / FPS
            val st = c.fromMs + min(t, clipMs)
            step(min((st / 100).toInt(), player.frameCount - 1), live = true)
            cam = toImage(sample)
            val now = sample.timestampMs

            g.color = Pal.bg; g.fillRect(0, 0, SW, SH)
            Fonts.draw(g, "시뮬레이션 · 합성 영상", 48f, 80f, Fonts.r(28f), Pal.dim)
            var cx = 48f
            for (n in profile.needs) cx += g.chip(cx, 100f, n.labelKo, Pal.line, size = 28f) + 12
            Fonts.draw(g, c.headline, 48f, 270f, Fonts.b(76f), Pal.text)
            Fonts.draw(g, c.sub, 48f, 340f, Fonts.r(40f), Pal.sky)

            // 전방 영상
            val camY = 390
            val camH = (SW * sample.frame.height.toFloat() / sample.frame.width).toInt()
            g.drawImage(cam, 0, camY, SW, camH, null)
            val s = SW / sample.frame.width.toFloat()
            lamps.firstOrNull { it.score >= 0.12f && it.shape.name == "CIRCLE" }?.let { l ->
                val r = (l.radius * 2.4f).coerceAtLeast(10f)
                val col = when (l.color) { SignalColor.RED -> Pal.vermilion; SignalColor.YELLOW -> Pal.amber; else -> Pal.green }
                g.color = col; g.stroke = BasicStroke(5f)
                g.drawRect(((l.centerX - r) * s).toInt(), (camY + (l.centerY - r) * s).toInt(), (2 * r * s).toInt(), (2 * r * s).toInt())
                val label = l.color.labelKo + (distance?.let { " · %.0fm".format(it) } ?: "")
                g.chip((l.centerX + r) * s + 10, camY + (l.centerY - r) * s - 6, label, Color(0, 0, 0, 180), size = 30f)
            }
            val v = sample.vehicle
            g.chip(24f, camY + camH - 70f, "%.0f km/h".format(v.speedKmh), Color(0, 0, 0, 170), size = 34f)

            // 앱 화면
            val panelY = camY + camH + 40
            g.round(40, panelY, SW - 80, 430, 36f, Pal.panel)
            // 신호등이 없는 장면(교차로 사이 도로)에서는 신호 패널 대신 내비·차량 상태를 보여준다
            val hasLights = c.scenario.phases.any { it.state != null }
            if (profile.signalAssist && hasLights) {
                val cell = 190f; val left = (SW - cell * 4) / 2f; val top = panelY + 40f
                g.round(left, top, cell * 4, cell, 30f, Color(12, 12, 14))
                val lit = listOf(signal.color == SignalColor.RED, signal.color == SignalColor.YELLOW, signal.leftTurnArrow, signal.color == SignalColor.GREEN)
                val cols = listOf(Pal.vermilion, Pal.amber, Pal.green, Pal.green)
                for (i in 0 until 4) {
                    val x = left + cell * (i + 0.5f); val y = top + cell / 2
                    g.color = if (lit[i]) cols[i] else Pal.off
                    g.fill(shape(i, x, y, cell * 0.34f))
                    if (lit[i]) { g.color = Color.WHITE; g.stroke = BasicStroke(6f); g.drawRoundRect((x - cell / 2 + 8).toInt(), (top + 8).toInt(), (cell - 16).toInt(), (cell - 16).toInt(), 24, 24) }
                }
                val (sym, label) = when (signal.color) {
                    SignalColor.RED -> "■" to "정지"; SignalColor.YELLOW -> "▲" to "주의"
                    SignalColor.GREEN -> "●" to "진행"; SignalColor.UNKNOWN -> "?" to "인식 중"
                }
                Fonts.center(g, "$sym $label", SW / 2f, top + cell + 150, Fonts.b(96f), Pal.text)
            } else {
                val nav = sample.navigation
                val navText = if (nav?.guiding == true && nav.nextManeuver != null && nav.distanceToManeuverM != null) {
                    "${nav.nextManeuver!!.symbol} ${(nav.distanceToManeuverM!! / 10).roundToInt() * 10}m ${nav.nextManeuver!!.labelKo}"
                } else "경로 안내 없음"
                g.round(70, panelY + 30, SW - 140, 130, 26f, Color(20, 30, 45))
                Fonts.draw(g, navText, 110f, panelY + 118f, Fonts.b(64f), Pal.text)
                cabinLines(sample.cabin).take(4).forEachIndexed { k, l ->
                    Fonts.draw(g, l, 100f, panelY + 230f + k * 50, Fonts.r(40f), if (l.startsWith("⚠")) Pal.amber else Pal.dim)
                }
            }

            // 알림 배너
            val by = panelY + 470
            banner?.let { b ->
                g.round(40, by, SW - 80, 250, 36f, Pal.panel)
                g.round(40, by, 18, 250, 9f, levelColor(b.alert.level))
                Fonts.wrap(g, "${b.alert.symbol} ${b.alert.message}", Fonts.b(50f), SW - 180f).take(2).forEachIndexed { k, l ->
                    Fonts.draw(g, l, 90f, by + 78f + k * 64, Fonts.b(50f), Pal.text)
                }
                val chips = when {
                    b.plan.silent -> listOf("화면 표시만")
                    !b.plan.tone && !b.plan.voice -> listOf("소리 없이", "화면 깜빡임")
                    else -> listOfNotNull("소리: " + toneName(b.alert.tone), "음성".takeIf { b.plan.voice })
                }
                var x = 90f
                for (ch in chips) x += g.chip(x, by + 170f, ch, Pal.line, size = 30f) + 12
            }

            // 화면 깜빡임 (청각장애 프로필, 경고 이상)
            flash?.let { fl ->
                val dur = if (fl.alert.level == AlertLevel.URGENT) 2_400 else 1_800
                val ft = now - flashAt
                if (ft in 0..dur) {
                    val keys = floatArrayOf(1f, 0.2f, 1f, 0.2f, 1f, 0f)
                    val p = ft.toFloat() / dur * (keys.size - 1)
                    val i = p.toInt().coerceAtMost(keys.size - 2)
                    val peak = if (fl.plan.dimmed) 0.45f else 0.9f
                    val a = (keys[i] + (keys[i + 1] - keys[i]) * (p - i)) * peak
                    val old = g.composite
                    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a.coerceIn(0f, 1f))
                    g.color = when (fl.alert.level) {
                        AlertLevel.URGENT -> Pal.vermilion; AlertLevel.WARNING -> Color(170, 70, 0)
                        AlertLevel.CAUTION -> Color(120, 110, 20); AlertLevel.INFO -> Color(0, 90, 140)
                    }
                    g.fillRect(0, 0, SW, SH)
                    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (a / peak).coerceIn(0f, 1f))
                    Fonts.center(g, flashHeadline(fl.alert), SW / 2f, 820f, Fonts.b(170f), Color.WHITE)
                    Fonts.wrap(g, fl.alert.message, Fonts.b(64f), SW - 140f).forEachIndexed { k, l ->
                        Fonts.center(g, l, SW / 2f, 1000f + k * 84, Fonts.b(64f), Color.WHITE)
                    }
                    g.composite = old
                }
            }

            // 주행 요약
            if (t > clipMs && summary != null) {
                val old = g.composite
                g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.93f)
                g.color = Pal.bg; g.fillRect(0, 0, SW, SH)
                g.composite = old
                g.round(70, 620, SW - 140, 640, 40f, Pal.panel)
                Fonts.draw(g, "주차 · 주행 요약", 130f, 730f, Fonts.b(60f), Pal.text)
                val lines = summary!!.lines().flatMap { Fonts.wrap(g, it, Fonts.r(44f), SW - 280f) }
                lines.forEachIndexed { k, l -> Fonts.draw(g, l, 130f, 840f + k * 70, Fonts.r(44f), if (k == lines.lastIndex) Pal.green else Pal.text) }
                g.chip(130f, 1150f, "가족에게 공유", Pal.line, size = 36f)
            }
            fade(g, t, total)
            emit(target)
        }
        globalMs += total
    }

    /** Machado(2009) 2형 색각(녹색맹) 시뮬레이션 행렬, 선형 RGB 에서 적용 */
    private fun deutan(c: Color): Color {
        fun lin(v: Int): Double { val x = v / 255.0; return if (x <= 0.04045) x / 12.92 else ((x + 0.055) / 1.055).pow(2.4) }
        fun srgb(x: Double): Int { val y = x.coerceIn(0.0, 1.0); return ((if (y <= 0.0031308) y * 12.92 else 1.055 * y.pow(1 / 2.4) - 0.055) * 255).roundToInt() }
        val r = lin(c.red); val gg = lin(c.green); val b = lin(c.blue)
        return Color(
            srgb(0.367322 * r + 0.860646 * gg - 0.227968 * b),
            srgb(0.280085 * r + 0.672501 * gg + 0.047413 * b),
            srgb(-0.011820 * r + 0.042940 * gg + 0.968881 * b),
        )
    }
}
