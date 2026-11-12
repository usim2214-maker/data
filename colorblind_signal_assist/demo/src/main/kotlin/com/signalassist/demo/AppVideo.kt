package com.signalassist.demo

import com.signalassist.core.Alert
import com.signalassist.core.AlertLevel
import com.signalassist.core.AlertType
import com.signalassist.core.Frame
import com.signalassist.core.OutputPlan
import com.signalassist.core.Scenario
import com.signalassist.core.ScenarioPlayer
import com.signalassist.core.SignalColor
import com.signalassist.core.SignalState
import com.signalassist.core.TonePattern
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * '실제 앱 화면' 시연 영상: 0.2.0 앱 소스를 Robolectric 으로 실행해 녹화한 화면(DemoRecordingTest)과
 * 같은 순간의 전방 영상·실제 지도를 나란히 놓는다.
 *
 *   ./gradlew :app:testDebugUnitTest --tests '*DemoRecordingTest*' -Precord=true
 *   ./gradlew :demo:renderAppVideo        # build/video/모두의운전석_실제앱_v0.2.mp4
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/video/모두의운전석_실제앱_v0.2.mp4").absoluteFile
    val audio = AudioTimeline()
    encodeVideo(out, 1280, 720, audio) { emit -> AppVideo(audio).render(emit) }
}

/** DemoRecordingTest 가 남긴 알림 기록 한 줄 */
private class LoggedDelivery(
    val index: Int, val tMs: Long, val type: AlertType, val level: AlertLevel, val tone: TonePattern,
    val message: String, val speech: String, val headline: String, val symbol: String,
    val planTone: Boolean, val planVoice: Boolean, val planFlash: Boolean, val planSilent: Boolean,
) {
    val alert get() = Alert(type, tMs, customMessage = message, customSymbol = symbol, customHeadline = headline)
    val plan get() = OutputPlan(planTone, planVoice, planFlash, false)
}

private class Recording(val dir: File) {
    val frames = (dir.listFiles { f -> f.name.startsWith("f_") } ?: emptyArray()).size
    val deliveries: List<LoggedDelivery> = File(dir, "deliveries.tsv").readLines().drop(1).filter { it.isNotBlank() }.map { l ->
        val c = l.split("\t")
        LoggedDelivery(c[0].toInt(), c[1].toLong(), AlertType.valueOf(c[2]), AlertLevel.valueOf(c[3]), TonePattern.valueOf(c[4]),
            c[5], c[6], c[7], c[8], c[9] == "true", c[10] == "true", c[11] == "true", c[12] == "true")
    }
    private var cachedIdx = -1
    private var cached: BufferedImage? = null
    fun frame(i: Int): BufferedImage {
        if (i != cachedIdx) { cached = ImageIO.read(File(dir, "f_%05d.png".format(i.coerceIn(0, frames - 1)))); cachedIdx = i }
        return cached!!
    }
}

private class AppVideo(private val audio: AudioTimeline) {
    private val recDir = listOf(File("../app/build/recording"), File("app/build/recording")).first { it.exists() }
    private val img = BufferedImage(1280, 720, BufferedImage.TYPE_3BYTE_BGR)
    private val g: Graphics2D = img.createGraphics().apply { setupHints() }
    private var total = 0L

    fun render(emit: (BufferedImage) -> Unit): Long {
        card(emit, 6_000) { opening() }
        staticScreens(emit)
        // 시나리오 1: 센텀시티 실제 도로 (3D)
        card(emit, 4_500) { centumIntro(g, it); Fonts.center(g, "오른쪽은 0.2.0 앱의 실제 화면입니다", 640f, 150f, Fonts.r(24f), Pal.amber) }
        val centum = CentumRun(audio, standaloneIndex = 1, precompute = false)
        total += centum.flyover(g, img, emit, 7_000)
        scene(emit, "centum", "시나리오 1 · 부산 센텀시티 실제 도로 (색각이상)", centum = centum)
        // 시나리오 2: 청각장애
        card(emit, 4_000) { sceneCard("시나리오 2", "청각장애 · 소리 알림을 화면으로", "벨트 경고음 · 내비 음성 · 타이어 경고 · 긴급차량 사이렌 · 방향지시등", "전체 화면 강조를 켠 설정 (0.2.0 기본값은 테두리 강조)") }
        scene(emit, "hearing_drive", "시나리오 2 · 청각장애 (전체 화면 강조 켬)", synthetic = ScenarioPlayer(Scenario.HEARING_DRIVE))
        card(emit, 7_000) { closing() }
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
        Fonts.center(g, "모두의 운전석 0.2.0", 640f, 230f, Fonts.b(64f), Pal.text)
        Fonts.center(g, "실제 앱 화면 시연", 640f, 300f, Fonts.r(34f), Pal.sky)
        Fonts.center(g, "앱 화면은 0.2.0 소스를 그대로 실행해 그렸습니다 (Robolectric · 에뮬레이터 없이)", 640f, 400f, Fonts.r(24f), Pal.text)
        Fonts.center(g, "같은 판단 엔진에 주행 상황을 0.1초마다 넣고, 앱 화면이 반응하는 모습을 그대로 녹화", 640f, 440f, Fonts.r(22f), Pal.dim)
        Fonts.center(g, "전방 영상·주행 상황은 시뮬레이션 (부산 센텀시티: OpenStreetMap + SUMO)", 640f, 520f, Fonts.r(20f), Pal.dim)
    }

    private fun sceneCard(top: String, title: String, line1: String, line2: String) {
        Fonts.center(g, top, 640f, 240f, Fonts.r(24f), Pal.dim)
        Fonts.center(g, title, 640f, 310f, Fonts.b(50f), Pal.text)
        Fonts.center(g, line1, 640f, 390f, Fonts.r(26f), Pal.sky)
        Fonts.center(g, line2, 640f, 440f, Fonts.r(22f), Pal.dim)
    }

    private fun closing() {
        Fonts.center(g, "모두의 운전석 0.2.0", 640f, 170f, Fonts.b(48f), Pal.text)
        listOf(
            "기능 연결 상태를 숨기지 않음: 신호 인식·내비·차속·마이크가 연결되지 않으면 그대로 표시",
            "체험 모드는 '가상 신호입니다. 실제 주행 판단에 사용하지 마세요.'를 항상 표시",
            "차속이 끊기거나 오래되면 '확인 불가'로 처리 (VehicleTelemetry)",
            "장애 이름 대신 필요한 도움으로 설정 · 글씨 더 크게 · 강조 방식 선택",
            "주행·설정·체험 화면을 분리하고, 주행 중에는 설정을 숨김",
        ).forEachIndexed { k, l -> Fonts.center(g, l, 640f, 260f + k * 50, Fonts.r(23f), Pal.text) }
        Fonts.center(g, "지도 데이터 © OpenStreetMap contributors · 신호 주기는 시뮬레이션 생성값", 640f, 640f, Fonts.r(18f), Pal.dim)
    }

    // ---- 정지 화면 3장 ----

    private fun staticScreens(emit: (BufferedImage) -> Unit) {
        val dir = File(recDir, "static")
        val shots = listOf(
            "01-driving-unconnected.png" to ("차량 보조 모드" to "연결되지 않은 기능은 '미연결'로 솔직하게 표시하고, 차량 연결부터 확인하게 합니다"),
            "02-settings.png" to ("내게 맞는 알림" to "장애 이름 대신 '필요한 도움'으로 고릅니다 (주차 중에만 설정)"),
            "03-demo.png" to ("체험 모드" to "가상 신호와 시나리오는 체험에서만 · 실제 주행 판단에 쓰지 말라는 안내를 항상 표시"),
        )
        for ((file, text) in shots) {
            val shot = ImageIO.read(File(dir, file))
            card(emit, 4_500) {
                Fonts.draw(g, text.first, 60f, 80f, Fonts.b(34f), Pal.text)
                Fonts.draw(g, text.second, 60f, 122f, Fonts.r(22f), Pal.sky)
                device(shot, 192, 150, 896, 525)
            }
        }
    }

    /** 차량 화면 테두리 + 실제 앱 화면 */
    private fun device(shot: BufferedImage, x: Int, y: Int, w: Int, h: Int) {
        g.round(x - 10, y - 10, w + 20, h + 20, 22f, Color(40, 44, 52))
        g.drawImage(shot, x, y, w, h, null)
        g.color = Color(70, 76, 88); g.stroke = BasicStroke(2f)
        g.drawRoundRect(x - 10, y - 10, w + 20, h + 20, 22, 22)
    }

    // ---- 녹화 장면 ----

    private fun scene(emit: (BufferedImage) -> Unit, name: String, title: String, centum: CentumRun? = null, synthetic: ScenarioPlayer? = null) {
        val rec = Recording(File(recDir, name))
        val n = rec.frames
        val byIndex = rec.deliveries.groupBy { it.index }
        val events = rec.deliveries.map { it.index }
        fun speedAt(i: Int): Float = centum?.player?.step(i)?.speedKmh ?: synthetic?.sample(i)?.vehicle?.speedKmh ?: 0f
        // 알림 앞뒤는 실시간, 나머지는 빠르게
        val tl = ArrayList<Pair<Int, Float>>()
        var t = 0.0
        val end = (n - 1) / 10.0
        val fastMove = if (centum != null) 6f else 3f
        val fastStop = if (centum != null) 15f else 3f
        while (t <= end) {
            val idx = (t * 10).roundToInt().coerceAtMost(n - 1)
            val near = events.any { e -> t in (e / 10.0 - 2.5)..(e / 10.0 + 4.0) } || t < 1.5
            val rate = if (near) 1f else if (speedAt(idx) < 0.5f) fastStop else fastMove
            tl += idx to rate
            t += rate / FPS
        }
        val minimap = centum?.let { CentumMinimap(it.map) }
        var cam: BufferedImage? = null
        var camIdx = -1
        var last: LoggedDelivery? = null
        var lastAt = 0L
        var lastIdx = -1
        val camImg = BufferedImage(960, 540, BufferedImage.TYPE_INT_RGB)
        tl.forEachIndexed { f, (idx, rate) ->
            val videoMs = f * 1000L / FPS
            for (i in (lastIdx + 1)..idx) byIndex[i]?.forEach { d ->
                if (!d.planSilent || d.type == AlertType.SIGNAL_STATUS) { last = d; lastAt = d.tMs }
                if (d.planTone) audio.add(total + videoMs, d.tone, OutputPlan(true, false, false, false))
            }
            lastIdx = idx
            if (camIdx != idx) {
                val frame: Frame = if (centum != null) {
                    val s = centum.player.step(idx)
                    val vis = s.tlsDistM != null && s.tlsDistM!! <= 150f && s.signalColor != SignalColor.UNKNOWN
                    centum.render3D(idx, s, if (vis) SignalState(s.signalColor) else null, (s.tlsDistM ?: 200f) + 12f)
                } else synthetic!!.sample(idx).frame
                camImg.setRGB(0, 0, frame.width, frame.height, frame.argb, 0, frame.width)
                cam = camImg; camIdx = idx
            }
            val nowMs = idx * 100L
            g.color = Pal.bg; g.fillRect(0, 0, 1280, 720)
            Fonts.draw(g, title, 24f, 44f, Fonts.b(24f), Pal.text)
            if (rate > 1f) g.chip(1150f, 20f, "▶▶ %.0f배속".format(rate), Pal.amber, Color.BLACK, size = 16f)
            // 왼쪽: 전방 영상 + 지도/상황
            g.drawImage(cam, 24, 76, 412, 232, null)
            g.round(30, 278, 200, 24, 8f, Color(0, 0, 0, 160))
            Fonts.draw(g, if (centum != null) "전방 시점 · 실제 건물 3D" else "전방 영상 (합성)", 38f, 295f, Fonts.r(13f), Pal.dim)
            if (centum != null && minimap != null) {
                val s = centum.player.step(idx)
                minimap.draw(g, s, 24, 318, 412, 230)
            } else {
                val smp = synthetic!!.sample(idx)
                g.round(24, 318, 412, 230, 16f, Pal.panel)
                Fonts.draw(g, "주행 상황 (시뮬레이션)", 40f, 346f, Fonts.r(15f), Pal.dim)
                val lines = cabinLines(smp.cabin) + listOfNotNull(if (smp.sirenNearby) "⚠ 뒤에서 긴급차량 사이렌" else null)
                Fonts.draw(g, "%.0f km/h".format(smp.vehicle.speedKmh), 40f, 384f, Fonts.b(26f), Pal.text)
                lines.take(5).forEachIndexed { k, l -> Fonts.draw(g, l, 40f, 418f + k * 26, Fonts.r(17f), if (l.startsWith("⚠")) Pal.amber else Pal.dim) }
            }
            // 오른쪽: 0.2.0 실제 앱 화면
            Fonts.draw(g, "실제 앱 화면 · 모두의 운전석 0.2.0", 458f, 70f, Fonts.r(15f), Pal.amber)
            device(rec.frame(idx), 458, 82, 798, 467)
            // 아래: 알림·음성·이유
            val d = last
            if (d != null && nowMs - lastAt < 6_000) {
                g.round(24, 566, 1232, 50, 14f, Pal.panel)
                g.round(24, 566, 10, 50, 5f, levelColor(d.level))
                Fonts.draw(g, "${d.symbol}  ${d.message}", 48f, 599f, Fonts.b(22f), Pal.text)
                val chips = listOfNotNull(("소리: " + toneName(d.tone)).takeIf { d.planTone }, "음성".takeIf { d.planVoice },
                    "화면 강조".takeIf { d.planFlash }, "소리 없음".takeIf { !d.planTone && !d.planVoice })
                var cx = 1256f - chips.sumOf { (Fonts.width(g, it, Fonts.r(13f)) + 30).toDouble() }.toFloat()
                for (c in chips) cx += g.chip(cx, 579f, c, Pal.line, size = 13f) + 8
                if (d.planVoice) Fonts.draw(g, "음성 안내 “${d.speech}”", 30f, 646f, Fonts.r(20f), Pal.sky)
                val why = if (d.type == AlertType.SIGNAL_STATUS) "운전자가 '지금 신호 확인'을 누름 → 최근 프레임이 모두 일치할 때만 확실하게 답함"
                else why(d.alert)
                g.round(24, 662, 1232, 42, 12f, Color(40, 36, 10))
                Fonts.draw(g, "왜?  $why", 40f, 690f, Fonts.r(19f), Pal.amber)
            }
            fadeBlack(g, videoMs, tl.size * 1000L / FPS, 1280, 720)
            emit(img)
        }
        total += tl.size * 1000L / FPS
    }
}
