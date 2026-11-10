package com.signalassist.demo

import com.signalassist.core.Alert
import com.signalassist.core.AlertLevel
import com.signalassist.core.AlertType
import com.signalassist.core.CabinState
import com.signalassist.core.Delivery
import com.signalassist.core.DriverNeed
import com.signalassist.core.DriverProfile
import com.signalassist.core.DrivingAssistEngine
import com.signalassist.core.DrivingOutput
import com.signalassist.core.Gear
import com.signalassist.core.LampDetection
import com.signalassist.core.Scenario
import com.signalassist.core.ScenarioPlayer
import com.signalassist.core.ScenarioSample
import com.signalassist.core.SignalColor
import com.signalassist.core.SignalState
import com.signalassist.core.TonePattern
import com.signalassist.core.TripRecorder
import com.signalassist.core.TurnSignal
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 공모전 시연 영상 렌더러.
 * 앱과 같은 엔진(DrivingAssistEngine)으로 시나리오를 돌리고, 전방 영상(합성) + 앱 화면 + 알림을 그려 mp4 로 만든다.
 *
 *   ./gradlew :demo:renderVideo            # build/video/모두의운전석_시연.mp4
 *   ./gradlew :demo:renderVideo --args="출력.mp4"
 */
private const val W = 1280
private const val H = 720
private const val FPS = 30
private const val SR = 44_100

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/video/모두의운전석_시연.mp4").absoluteFile
    out.parentFile.mkdirs()
    val silent = File(out.parentFile, "video_noaudio.mp4")
    val wav = File(out.parentFile, "audio.wav")

    val r = VideoRenderer()
    val ffmpeg = ProcessBuilder(
        "ffmpeg", "-y", "-loglevel", "error", "-f", "rawvideo", "-pix_fmt", "bgr24", "-s", "${W}x$H", "-r", "$FPS",
        "-i", "-", "-c:v", "libx264", "-preset", "medium", "-crf", "21", "-pix_fmt", "yuv420p", silent.path,
    ).redirectErrorStream(true).start()
    val sink = ffmpeg.outputStream.buffered(1 shl 20)
    r.render { frame -> sink.write((frame.raster.dataBuffer as DataBufferByte).data) }
    sink.close()
    check(ffmpeg.waitFor() == 0) { "ffmpeg video encode failed: " + ffmpeg.inputStream.bufferedReader().readText() }

    r.audio.writeWav(wav, r.totalMs)
    val mux = ProcessBuilder(
        "ffmpeg", "-y", "-loglevel", "error", "-i", silent.path, "-i", wav.path,
        "-c:v", "copy", "-c:a", "aac", "-b:a", "160k", "-shortest", "-movflags", "+faststart", out.path,
    ).redirectErrorStream(true).start()
    check(mux.waitFor() == 0) { "ffmpeg mux failed: " + mux.inputStream.bufferedReader().readText() }
    silent.delete(); wav.delete()
    println("시연 영상: ${out.path} (%.0f초)".format(r.totalMs / 1000.0))
}

// ---------------------------------------------------------------- 오디오

/** 앱 TonePlayer 와 같은 알림음을 타임라인에 섞는다 */
private class AudioTimeline {
    private data class Note(val f: Double, val ms: Int, val gap: Int = 60)
    private val patterns = mapOf(
        TonePattern.RISING_DOUBLE to listOf(Note(523.0, 150), Note(784.0, 220)),
        TonePattern.SINGLE_LONG to listOf(Note(660.0, 500)),
        TonePattern.FALLING_TRIPLE to listOf(Note(880.0, 150), Note(660.0, 150), Note(440.0, 260)),
        TonePattern.RAPID_BEEPS to List(6) { Note(1000.0, 80, 50) },
        TonePattern.SOFT_CHIME to listOf(Note(1047.0, 120)),
        TonePattern.DOUBLE_MID to listOf(Note(740.0, 140, 120), Note(740.0, 140)),
    )
    private val events = ArrayList<Pair<Long, TonePattern>>()

    fun add(atMs: Long, p: TonePattern) { events += atMs to p }

    fun writeWav(file: File, totalMs: Long) {
        val n = (totalMs * SR / 1000).toInt() + SR
        val mix = FloatArray(n)
        for ((at, p) in events) {
            var pos = (at * SR / 1000).toInt()
            for (note in patterns.getValue(p)) {
                val len = note.ms * SR / 1000
                val fade = min(len / 4, SR / 100)
                for (i in 0 until len) {
                    if (pos + i >= n) break
                    val env = when { i < fade -> i.toDouble() / fade; i > len - fade -> (len - i).toDouble() / fade; else -> 1.0 }
                    mix[pos + i] += (sin(2 * PI * note.f * i / SR) * env * 0.5).toFloat()
                }
                pos += len + note.gap * SR / 1000
            }
        }
        DataOutputStream(FileOutputStream(file).buffered()).use { o ->
            fun le32(v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff); o.write(v shr 16 and 0xff); o.write(v shr 24 and 0xff) }
            fun le16(v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff) }
            o.writeBytes("RIFF"); le32(36 + n * 2); o.writeBytes("WAVEfmt "); le32(16); le16(1); le16(1)
            le32(SR); le32(SR * 2); le16(2); le16(16); o.writeBytes("data"); le32(n * 2)
            for (v in mix) le16((v.coerceIn(-1f, 1f) * 30000).toInt())
        }
    }
}

// ---------------------------------------------------------------- 그리기 도구

private object Pal {
    val bg = Color(14, 15, 18)
    val panel = Color(24, 26, 31)
    val line = Color(48, 52, 60)
    val text = Color(236, 238, 242)
    val dim = Color(150, 156, 166)
    val vermilion = Color(213, 94, 0)
    val amber = Color(240, 228, 66)
    val green = Color(0, 158, 115)
    val sky = Color(86, 180, 233)
    val off = Color(45, 45, 48)
}

private object Fonts {
    private const val DIR = "/usr/share/fonts/truetype/nanum/"
    private fun load(file: String, fallback: String) =
        File(DIR + file).takeIf { it.exists() }?.let { Font.createFont(Font.TRUETYPE_FONT, it) } ?: Font(fallback, Font.PLAIN, 12)

    val bold: Font = load("NanumSquareB.ttf", Font.SANS_SERIF)
    val regular: Font = load("NanumBarunGothic.ttf", Font.SANS_SERIF)
    private val symbol = Font("DejaVu Sans", Font.PLAIN, 12)

    fun b(size: Float): Font = bold.deriveFont(size)
    fun r(size: Float): Font = regular.deriveFont(size)

    /** 글자마다 폰트에 없으면 DejaVu Sans 로 대체해서 그린다. 그린 폭을 돌려준다 */
    fun draw(g: Graphics2D, text: String, x: Float, y: Float, font: Font, color: Color): Float {
        g.color = color
        var cx = x
        val s = text.replace("🚨", "⚠")
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val f = if (font.canDisplay(cp)) font else symbol.deriveFont(font.style, font.size2D)
            var j = i + Character.charCount(cp)
            while (j < s.length) {
                val c2 = s.codePointAt(j)
                if ((if (font.canDisplay(c2)) font else null) != (if (f == font) font else null)) break
                j += Character.charCount(c2)
            }
            val run = s.substring(i, j)
            g.font = f
            g.drawString(run, cx, y)
            cx += g.fontMetrics.stringWidth(run)
            i = j
        }
        return cx - x
    }

    fun width(g: Graphics2D, text: String, font: Font): Float {
        val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        val gg = img.createGraphics()
        val w = draw(gg, text, 0f, 0f, font, Color.WHITE)
        gg.dispose()
        return w
    }

    fun center(g: Graphics2D, text: String, cx: Float, y: Float, font: Font, color: Color) =
        draw(g, text, cx - width(g, text, font) / 2, y, font, color)

    /** 폭에 맞춰 줄바꿈 */
    fun wrap(g: Graphics2D, text: String, font: Font, maxW: Float): List<String> {
        val out = ArrayList<String>()
        var cur = ""
        for (word in text.split(" ")) {
            val next = if (cur.isEmpty()) word else "$cur $word"
            if (width(g, next, font) > maxW && cur.isNotEmpty()) { out += cur; cur = word } else cur = next
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }
}

private fun Graphics2D.round(x: Number, y: Number, w: Number, h: Number, r: Float, c: Color) {
    color = c
    fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), r, r))
}

private fun Graphics2D.chip(x: Float, y: Float, text: String, bg: Color, fg: Color = Pal.text, size: Float = 17f): Float {
    val f = Fonts.r(size)
    val w = Fonts.width(this, text, f) + 22
    round(x, y, w, size + 16, 12f, bg)
    Fonts.draw(this, text, x + 11, y + size + 5, f, fg)
    return w
}

// ---------------------------------------------------------------- 영상 구성

private sealed class Segment {
    class Card(val ms: Long, val draw: (Graphics2D, Long) -> Unit) : Segment()
    class Run(val index: Int, val scenario: Scenario, val trimMs: Long, val tripSummaryHoldMs: Long = 0) : Segment()
}

private class VideoRenderer {
    val audio = AudioTimeline()
    var totalMs = 0L
        private set

    private val scenarios = listOf(
        Scenario.STOP_AND_GO, Scenario.RED_LIGHT_RISK, Scenario.YELLOW_DILEMMA,
        Scenario.SIGNAL_OUT_OF_VIEW, Scenario.HEARING_DRIVE, Scenario.NIGHT_ARRIVAL,
    )

    private val intros = mapOf(
        "stop_and_go" to listOf("신호등의 빨강·초록을 색으로 구분하기 어려운 운전자", "신호 변화를 '음높이 방향'과 모양 기호로 전달합니다"),
        "red_light_risk" to listOf("빨간불을 알아채지 못하고 그대로 접근하는 상황", "신호등까지 거리를 재서, 정말 제동이 필요할 때만 긴급 경고"),
        "yellow_dilemma" to listOf("정지선 바로 앞에서 황색으로 바뀌는 상황", "급정거로 인한 뒤차 추돌을 막기 위해 '정지' 대신 '주의 진행'"),
        "signal_out_of_view" to listOf("정지선 바로 앞에 서서 신호등이 카메라 화면을 벗어나는 상황", "인식 실패를 숨기지 않고 '직접 확인하세요'라고 알립니다"),
        "hearing_drive" to listOf("경고음·방향지시등 소리·내비 음성·사이렌을 듣기 어려운 운전자", "모든 소리 정보를 화면 깜빡임과 큰 글씨로 바꿉니다 (소리 없음)"),
        "night_arrival" to listOf("작은 경고등을 놓치기 쉽고, 깜빡하기 쉬운 운전자", "경고등은 음성으로, 겹치는 알림은 중재, 내릴 때 뒷좌석 확인"),
    )

    private val segments: List<Segment> = buildList {
        add(Segment.Card(6_000) { g, t -> opening(g, t) })
        add(Segment.Card(6_000) { g, t -> profiles(g, t) })
        scenarios.forEachIndexed { i, s ->
            add(Segment.Card(3_500) { g, t -> scenarioIntro(g, t, i, s) })
            val trim = when (s.name) { "signal_out_of_view" -> 12_000L; "night_arrival" -> 17_000L; else -> s.durationMs }
            add(Segment.Run(i, s, trim, if (s.name == "night_arrival") 5_000 else 0))
        }
        add(Segment.Card(9_000) { g, t -> closing(g, t) })
    }

    fun render(emit: (BufferedImage) -> Unit) {
        val img = BufferedImage(W, H, BufferedImage.TYPE_3BYTE_BGR)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        val frameMs = 1000.0 / FPS
        for (seg in segments) {
            when (seg) {
                is Segment.Card -> {
                    val frames = (seg.ms / frameMs).roundToInt()
                    repeat(frames) { f ->
                        val t = (f * frameMs).toLong()
                        g.color = Pal.bg; g.fillRect(0, 0, W, H)
                        seg.draw(g, t)
                        fadeEdges(g, t, seg.ms)
                        emit(img)
                    }
                    totalMs += seg.ms
                }
                is Segment.Run -> {
                    val run = ScenarioRun(seg, totalMs)
                    val ms = seg.trimMs + seg.tripSummaryHoldMs
                    val frames = (ms / frameMs).roundToInt()
                    repeat(frames) { f ->
                        run.drawAt(g, (f * frameMs).toLong())
                        fadeEdges(g, (f * frameMs).toLong(), ms)
                        emit(img)
                    }
                    totalMs += ms
                }
            }
        }
        g.dispose()
    }

    private fun fadeEdges(g: Graphics2D, t: Long, len: Long) {
        val fade = 300L
        val a = when { t < fade -> 1f - t.toFloat() / fade; t > len - fade -> (t - (len - fade)).toFloat() / fade; else -> 0f }
        if (a <= 0f) return
        val old = g.composite
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a.coerceIn(0f, 1f))
        g.color = Color.BLACK; g.fillRect(0, 0, W, H)
        g.composite = old
    }

    // ---- 카드 ----

    private fun opening(g: Graphics2D, t: Long) {
        Fonts.center(g, "모두의 운전석", W / 2f, 250f, Fonts.b(72f), Pal.text)
        Fonts.center(g, "색·소리·작은 경고등에 의존하는 차량 정보를", W / 2f, 330f, Fonts.r(30f), Pal.text)
        Fonts.center(g, "운전자 특성에 맞는 '다른 감각'으로 전달하는 Pleos Connect 앱", W / 2f, 372f, Fonts.r(30f), Pal.text)
        // 신호 4구 장식
        val cols = listOf(Pal.vermilion, Pal.amber, Pal.green, Pal.green)
        val lit = ((t / 900) % 4).toInt()
        for (i in 0 until 4) {
            val cx = W / 2f + (i - 1.5f) * 70
            g.color = if (i == lit) cols[i] else Pal.off
            g.fillOval((cx - 22).toInt(), 430, 44, 44)
        }
        Fonts.center(g, "시연 영상 · 신호 인식은 합성 영상 기반 시뮬레이션 (Pleos 공개 API에 카메라 미제공)", W / 2f, 560f, Fonts.r(20f), Pal.dim)
        Fonts.center(g, "앱과 같은 판단 엔진으로 렌더링 · 알림음은 실제 앱의 소리 패턴", W / 2f, 592f, Fonts.r(20f), Pal.dim)
    }

    private fun profiles(g: Graphics2D, t: Long) {
        Fonts.center(g, "운전자가 자기 특성을 고르면, 켤 기능과 알림 감각이 정해집니다", W / 2f, 110f, Fonts.b(34f), Pal.text)
        val items = listOf(
            Triple(DriverNeed.COLOR_VISION, "신호 색 → 소리 패턴 · 음성 · 모양 기호", Pal.vermilion),
            Triple(DriverNeed.HEARING, "경고음 · 방향지시등 · 내비 음성 · 사이렌 → 화면 깜빡임", Pal.sky),
            Triple(DriverNeed.LOW_VISION, "작은 경고등 → 음성 + 큰 글씨", Pal.amber),
            Triple(DriverNeed.ATTENTION, "깜빡한 방향지시등 · 늦은 출발 · 뒷좌석 → 한 번 더, 겹치면 중재", Pal.green),
        )
        items.forEachIndexed { i, (need, desc, c) ->
            val y = 190 + i * 110
            val appear = ((t - i * 400) / 400f).coerceIn(0f, 1f)
            if (appear <= 0f) return@forEachIndexed
            val old = g.composite
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, appear)
            g.round(160, y, 960, 86, 20f, Pal.panel)
            g.round(160, y, 12, 86, 6f, c)
            Fonts.draw(g, need.labelKo, 200f, y + 54f, Fonts.b(32f), Pal.text)
            Fonts.draw(g, desc, 420f, y + 52f, Fonts.r(24f), Pal.dim)
            g.composite = old
        }
        Fonts.center(g, "여러 개를 함께 고를 수 있습니다", W / 2f, 660f, Fonts.r(20f), Pal.dim)
    }

    private fun scenarioIntro(g: Graphics2D, t: Long, i: Int, s: Scenario) {
        Fonts.center(g, "시나리오 ${i + 1} / ${scenarios.size}", W / 2f, 230f, Fonts.r(24f), Pal.dim)
        Fonts.center(g, s.titleKo, W / 2f, 300f, Fonts.b(52f), Pal.text)
        val lines = intros[s.name].orEmpty()
        lines.forEachIndexed { k, l -> Fonts.center(g, l, W / 2f, 380f + k * 44, Fonts.r(27f), if (k == 0) Pal.text else Pal.sky) }
        val profile = s.targetNeeds.joinToString(" + ") { it.labelKo }
        Fonts.center(g, "프로필: $profile", W / 2f, 520f, Fonts.r(22f), Pal.dim)
    }

    private fun closing(g: Graphics2D, t: Long) {
        Fonts.center(g, "모두의 운전석", W / 2f, 110f, Fonts.b(48f), Pal.text)
        val colX = listOf(90f, 700f)
        Fonts.draw(g, "사용하는 차량 데이터", colX[0], 190f, Fonts.b(28f), Pal.sky)
        listOf(
            "Pleos Vehicle State — 벨트·문·타이어·조명·좌석",
            "Pleos NaviHelper — 경로·다음 안내 지점",
            "AAOS Car API — 차속·기어·주행 중 화면 제약",
            "실내 마이크 — 사이렌 감지 (기기 안 분석)",
        ).forEachIndexed { k, l -> Fonts.draw(g, "• $l", colX[0], 236f + k * 40, Fonts.r(20f), Pal.text) }
        Fonts.draw(g, "안전을 위한 설계", colX[1], 190f, Fonts.b(28f), Pal.amber)
        listOf(
            "초록불은 더 확실할 때만, 빨간불은 더 빨리 확정",
            "거리·제동 물리로 긴급 경고 판단",
            "황색 딜레마 구간: 급정거 유도 안 함",
            "인식 실패는 숨기지 않고 '직접 확인'",
            "알림 중재 · 야간 감광 · 필요할 때만 영상 처리",
        ).forEachIndexed { k, l -> Fonts.draw(g, "• $l", colX[1], 236f + k * 40, Fonts.r(20f), Pal.text) }
        g.round(110, 470, 1060, 110, 20f, Pal.panel)
        Fonts.center(g, "카메라 없이도 지금 동작: 차량 경고 · 내비 카드 · 사이렌 · 뒷좌석 확인", W / 2f, 515f, Fonts.r(24f), Pal.text)
        Fonts.center(g, "카메라 API가 열리면 신호 인식을 실제 영상으로 전환 (영상 입력 한 곳만 교체)", W / 2f, 555f, Fonts.r(22f), Pal.dim)
        Fonts.center(g, "위치 미기록 · 주행 요약은 기기 안에만 · 공유는 사용자가 직접", W / 2f, 640f, Fonts.r(20f), Pal.dim)
    }

    // ---- 시나리오 재생 ----

    private inner class ScenarioRun(private val seg: Segment.Run, private val baseMs: Long) {
        private val s = seg.scenario
        private val profile = DriverProfile(s.targetNeeds)
        private val player = ScenarioPlayer(s)
        private val engine = DrivingAssistEngine(profile)
        private val trip = TripRecorder()
        private var index = -1
        private lateinit var sample: ScenarioSample
        private lateinit var out: DrivingOutput
        private lateinit var cam: BufferedImage
        private var lamps: List<LampDetection> = emptyList()
        private var distanceM: Float? = null
        private var signal: SignalState = SignalState.UNKNOWN
        private var banner: Delivery? = null
        private var bannerAt = 0L
        private var flash: Delivery? = null
        private var flashAt = 0L
        private var voice: String? = null
        private var voiceUntil = 0L
        private var caption: List<String> = emptyList()
        private var captionUntil = 0L
        private var summary: String? = null

        private fun advanceTo(t: Long) {
            val target = min((t / 100).toInt(), player.frameCount - 1)
            while (index < target) {
                index++
                sample = player.sample(index)
                out = engine.process(sample.toInput())
                cam = toImage(sample)
                out.signal?.let { sig ->
                    signal = sig.state
                    lamps = sig.detection.lamps
                    distanceM = sig.distanceM
                }
                trip.update(out)?.let { summary = it.summaryKo() }
                val now = sample.timestampMs
                val captions = ArrayList<String>()
                for (d in out.deliveries) {
                    banner = d; bannerAt = now
                    if (d.plan.visualFlash) { flash = d; flashAt = now }
                    if (d.plan.tone) audio.add(baseMs + now, d.alert.tone)
                    if (d.plan.voice) { voice = d.alert.message; voiceUntil = now + 3_200 }
                    captions += if (d.plan.silent) "동시에 나온 덜 중요한 알림은 소리 없이 화면에만 (알림 중재)" else why(d.alert)
                }
                if (captions.isNotEmpty()) { caption = captions.take(2); captionUntil = now + 4_000 }
                if (out.deliveries.isNotEmpty()) banner = out.deliveries.first()
            }
        }

        fun drawAt(g: Graphics2D, t: Long) {
            val tt = min(t, seg.trimMs)
            advanceTo(tt)
            val now = sample.timestampMs
            if (t >= seg.trimMs && summary == null && seg.tripSummaryHoldMs > 0) summary = trip.finish(now)?.summaryKo()
            g.color = Pal.bg; g.fillRect(0, 0, W, H)
            topBar(g, now)
            camera(g)
            appPanel(g)
            bottom(g, now)
            drawFlash(g, now)
            if (t >= seg.trimMs && summary != null) tripCard(g, summary!!)
        }

        private fun topBar(g: Graphics2D, now: Long) {
            Fonts.draw(g, "시나리오 ${seg.index + 1}  ${s.titleKo}", 24f, 40f, Fonts.b(24f), Pal.text)
            val v = sample.vehicle
            val gear = when (v.gear) { Gear.PARK -> "P"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.DRIVE -> "D"; Gear.UNKNOWN -> "-" }
            val right = "%.0f km/h · 기어 %s · %.1f초".format(v.speedKmh, gear, now / 1000.0)
            Fonts.draw(g, right, W - 24f - Fonts.width(g, right, Fonts.r(22f)), 40f, Fonts.r(22f), Pal.dim)
            var x = 24f
            for (need in profile.needs) x += g.chip(x, 54f, need.labelKo, Pal.line, size = 15f) + 8
        }

        private fun camera(g: Graphics2D) {
            val x = 24; val y = 92; val w = 736; val h = 414
            g.drawImage(cam, x, y, w, h, null)
            val sx = w / sample.frame.width.toFloat()
            val sy = h / sample.frame.height.toFloat()
            // 인식한 등화 표시 + 추정 거리
            lamps.filter { it.score >= 0.12f }.take(2).forEachIndexed { k, l ->
                val r = (l.radius * 2.2f).coerceAtLeast(9f)
                val c = when (l.color) { SignalColor.RED -> Pal.vermilion; SignalColor.YELLOW -> Pal.amber; else -> Pal.green }
                g.color = c; g.stroke = BasicStroke(3f)
                g.drawRect((x + (l.centerX - r) * sx).toInt(), (y + (l.centerY - r) * sy).toInt(), (2 * r * sx).toInt(), (2 * r * sy).toInt())
                if (k == 0) {
                    val label = "${l.color.labelKo}" + (distanceM?.let { " · %.0fm".format(it) } ?: "")
                    g.chip(x + (l.centerX + r) * sx + 6, y + (l.centerY - r) * sy - 4, label, Color(0, 0, 0, 170), size = 16f)
                }
            }
            g.round(x + 10, y + h - 40, 250, 30, 10f, Color(0, 0, 0, 160))
            Fonts.draw(g, "전방 카메라 (합성 영상)", x + 22f, y + h - 18f, Fonts.r(16f), Pal.dim)
        }

        private fun appPanel(g: Graphics2D) {
            val x = 784; val y = 92; val w = 472; val h = 414
            g.round(x, y, w, h, 22f, Pal.panel)
            Fonts.draw(g, "앱 화면", x + 20f, y + 32f, Fonts.r(17f), Pal.dim)
            if (profile.signalAssist) {
                // 4구 신호 패널 (위치 + 모양 + 색각이상 친화 색)
                val cell = 92f; val left = x + (w - cell * 4) / 2f; val top = y + 48f
                g.round(left, top, cell * 4, cell, 18f, Color(12, 12, 14))
                val litArr = listOf(signal.color == SignalColor.RED, signal.color == SignalColor.YELLOW, signal.leftTurnArrow, signal.color == SignalColor.GREEN)
                val cols = listOf(Pal.vermilion, Pal.amber, Pal.green, Pal.green)
                for (i in 0 until 4) {
                    val cx = left + cell * (i + 0.5f); val cy = top + cell / 2; val r = cell * 0.34f
                    g.color = if (litArr[i]) cols[i] else Pal.off
                    g.fill(shape(i, cx, cy, r))
                    if (litArr[i]) { g.color = Color.WHITE; g.stroke = BasicStroke(3f); g.drawRoundRect((cx - cell / 2 + 5).toInt(), (top + 5).toInt(), (cell - 10).toInt(), (cell - 10).toInt(), 14, 14) }
                }
                val (sym, label) = when (signal.color) {
                    SignalColor.RED -> "■" to "정지"; SignalColor.YELLOW -> "▲" to "주의"
                    SignalColor.GREEN -> "●" to "진행"; SignalColor.UNKNOWN -> "?" to "신호 인식 중"
                }
                val arrow = if (signal.leftTurnArrow) "  ← 좌회전" else ""
                Fonts.center(g, "$sym $label$arrow", x + w / 2f, top + cell + 62, Fonts.b(44f), Pal.text)
            }
            // 내비 카드
            val navY = if (profile.signalAssist) y + 250 else y + 52
            val nav = sample.navigation
            g.round(x + 18, navY, w - 36, 70, 14f, Color(20, 30, 45))
            val navText = if (nav?.guiding == true && nav.nextManeuver != null && nav.distanceToManeuverM != null) {
                "${nav.nextManeuver!!.symbol}  ${(nav.distanceToManeuverM!! / 10).roundToInt() * 10}m  ${nav.nextManeuver!!.labelKo}" + (nav.roadName?.let { "  $it" } ?: "")
            } else "경로 안내 없음"
            Fonts.draw(g, navText, x + 36f, navY + 46f, Fonts.b(if (profile.navigationCards) 30f else 24f), Pal.text)
            // 차량 상태
            val lines = cabinLines(sample.cabin)
            lines.take(if (profile.signalAssist) 2 else 6).forEachIndexed { k, l ->
                Fonts.draw(g, l, x + 26f, navY + 104f + k * 34, Fonts.r(if (profile.signalAssist) 19f else 23f), if (l.startsWith("⚠")) Pal.amber else Pal.dim)
            }
        }

        private fun bottom(g: Graphics2D, now: Long) {
            val y = 522
            g.round(24, y, W - 48, 86, 18f, Pal.panel)
            val b = banner
            if (b != null) {
                val age = now - bannerAt
                val accent = levelColor(b.alert.level)
                g.round(24, y, 12, 86, 6f, if (age < 2_500) accent else Pal.line)
                Fonts.draw(g, "${b.alert.symbol}  ${b.alert.message}", 54f, y + 40f, Fonts.b(28f), Pal.text)
                var cx = 54f
                for (c in channels(b)) cx += g.chip(cx, y + 50f, c, Pal.line, size = 14f) + 8
            } else {
                Fonts.draw(g, "알림 대기 중", 54f, y + 52f, Fonts.r(24f), Pal.dim)
            }
            // 음성 자막 / 이유 설명
            if (now < voiceUntil && voice != null) {
                Fonts.draw(g, "음성 안내 “$voice”", 30f, 650f, Fonts.r(22f), Pal.sky)
            }
            caption.firstOrNull()?.takeIf { now < captionUntil }?.let { c ->
                g.round(24, 664, W - 48, 44, 12f, Color(40, 36, 10))
                Fonts.draw(g, "왜?  $c", 40f, 693f, Fonts.r(21f), Pal.amber)
            }
        }

        private fun drawFlash(g: Graphics2D, now: Long) {
            val f = flash ?: return
            val dur = if (f.alert.level == AlertLevel.URGENT) 2_400 else 1_800
            val t = now - flashAt
            if (t < 0 || t > dur) return
            val keys = floatArrayOf(1f, 0.2f, 1f, 0.2f, 1f, 0f)
            val p = t.toFloat() / dur * (keys.size - 1)
            val i = p.toInt().coerceAtMost(keys.size - 2)
            val peak = if (f.plan.dimmed) 0.45f else 0.9f
            val a = (keys[i] + (keys[i + 1] - keys[i]) * (p - i)) * peak
            val old = g.composite
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a.coerceIn(0f, 1f))
            g.color = when (f.alert.level) {
                AlertLevel.URGENT -> Pal.vermilion; AlertLevel.WARNING -> Color(170, 70, 0)
                AlertLevel.CAUTION -> Color(120, 110, 20); AlertLevel.INFO -> Color(0, 90, 140)
            }
            g.fillRect(0, 0, W, H)
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (a / peak).coerceIn(0f, 1f))
            Fonts.center(g, flashHeadline(f.alert), W / 2f, 300f, Fonts.b(96f), Color.WHITE)
            Fonts.wrap(g, f.alert.message, Fonts.b(46f), W - 160f).forEachIndexed { k, l ->
                Fonts.center(g, l, W / 2f, 400f + k * 60, Fonts.b(46f), Color.WHITE)
            }
            g.composite = old
        }

        private fun tripCard(g: Graphics2D, text: String) {
            val old = g.composite
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.92f)
            g.color = Pal.bg; g.fillRect(0, 0, W, H)
            g.composite = old
            g.round(240, 150, 800, 420, 26f, Pal.panel)
            Fonts.draw(g, "주차 · 주행 요약", 290f, 220f, Fonts.b(36f), Pal.text)
            text.lines().forEachIndexed { k, l -> Fonts.draw(g, l, 290f, 290f + k * 50, Fonts.r(28f), if (k == text.lines().lastIndex) Pal.green else Pal.text) }
            g.chip(290f, 500f, "가족에게 공유", Pal.line, size = 20f)
            Fonts.draw(g, "위치는 기록하지 않고 기기에만 저장", 520f, 528f, Fonts.r(18f), Pal.dim)
        }

        private fun channels(d: Delivery): List<String> {
            val p = d.plan
            if (p.silent) return listOf("화면 표시만 (알림 중재)")
            return listOfNotNull(
                ("소리: " + toneName(d.alert.tone)).takeIf { p.tone },
                "음성".takeIf { p.voice },
                (if (p.dimmed) "화면 깜빡임 (야간 감광)" else "화면 깜빡임").takeIf { p.visualFlash },
                "큰 글씨".takeIf { p.largeText },
                "헤드업 알림",
            ).let { if (!p.tone && !p.voice) listOf("소리 없음") + it else it }
        }
    }

    // ---- 보조 ----

    private fun toImage(s: ScenarioSample): BufferedImage {
        val f = s.frame
        val img = BufferedImage(f.width, f.height, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, f.width, f.height, f.argb, 0, f.width)
        return img
    }

    private fun shape(i: Int, cx: Float, cy: Float, r: Float): Path2D.Float = Path2D.Float().apply {
        when (i) {
            0 -> { for (k in 0 until 8) { val a = Math.toRadians(22.5 + 45.0 * k); val px = cx + r * cos(a).toFloat(); val py = cy + r * sin(a).toFloat(); if (k == 0) moveTo(px, py) else lineTo(px, py) }; closePath() }
            1 -> { moveTo(cx, cy - r); lineTo(cx + r * 0.95f, cy + r * 0.75f); lineTo(cx - r * 0.95f, cy + r * 0.75f); closePath() }
            2 -> { moveTo(cx - r, cy); lineTo(cx - r * 0.1f, cy - r * 0.7f); lineTo(cx - r * 0.1f, cy - r * 0.28f); lineTo(cx + r, cy - r * 0.28f)
                lineTo(cx + r, cy + r * 0.28f); lineTo(cx - r * 0.1f, cy + r * 0.28f); lineTo(cx - r * 0.1f, cy + r * 0.7f); closePath() }
            else -> append(java.awt.geom.Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r), false)
        }
    }

    private fun cabinLines(c: CabinState): List<String> = listOfNotNull(
        c.driverSeatbeltBuckled?.let { if (it) "○ 벨트 착용" else "⚠ 벨트 미착용" },
        c.turnSignal?.let { when (it) { TurnSignal.NONE -> "○ 방향지시등 꺼짐"; TurnSignal.LEFT -> "⚠ 방향지시등 왼쪽"; TurnSignal.RIGHT -> "⚠ 방향지시등 오른쪽"; TurnSignal.HAZARD -> "⚠ 비상등" } },
        c.lowTirePressure?.let { if (it) "⚠ 타이어 저압" else "○ 타이어 정상" },
        c.headlightsOn?.let { if (it) "○ 전조등 켜짐" else "⚠ 전조등 꺼짐" },
        c.doorOpen?.let { if (it) "⚠ 문 열림" else "○ 문 닫힘" },
        c.rearSeatOccupied?.let { if (it) "◉ 뒷좌석 탑승" else "○ 뒷좌석 비어 있음" },
    )

    private fun levelColor(l: AlertLevel) = when (l) {
        AlertLevel.URGENT -> Pal.vermilion; AlertLevel.WARNING -> Color(220, 120, 30)
        AlertLevel.CAUTION -> Pal.amber; AlertLevel.INFO -> Pal.sky
    }

    private fun toneName(t: TonePattern) = when (t) {
        TonePattern.RISING_DOUBLE -> "도→솔 (올라감)"
        TonePattern.SINGLE_LONG -> "한 음 길게"
        TonePattern.FALLING_TRIPLE -> "세 음 내려감"
        TonePattern.RAPID_BEEPS -> "빠른 연속음"
        TonePattern.SOFT_CHIME -> "짧은 차임"
        TonePattern.DOUBLE_MID -> "같은 음 두 번"
    }

    /** 화면 깜빡임의 큰 제목: 신호는 모양 + 낱말로 (기호만 크게 그리면 빈 상자처럼 보임) */
    private fun flashHeadline(a: Alert): String = when (a.type) {
        AlertType.RED_STOP, AlertType.RED_URGENT -> "■ 정지"
        AlertType.YELLOW_CAUTION, AlertType.YELLOW_DILEMMA -> "▲ 주의"
        AlertType.GREEN_GO, AlertType.GREEN_REMINDER -> "● 진행"
        AlertType.SIGNAL_LOST -> "? 직접 확인"
        AlertType.LEFT_ARROW_ON, AlertType.LEFT_ARROW_FOR_ROUTE, AlertType.LEFT_ARROW_NOT_FOR_ROUTE -> "← 좌회전"
        AlertType.SIREN_NEARBY -> "⚠ 사이렌"
        else -> a.symbol
    }

    /** 알림이 왜 나왔는지 한 줄 설명 (심사위원용) */
    private fun why(a: Alert): String = when (a.type) {
        AlertType.GREEN_GO -> "초록불은 5프레임 중 4프레임이 일치해야 확정 (빨간불보다 엄격) → 정차 중이라 바로 안내"
        AlertType.GREEN_REMINDER -> "초록불 3초 후에도 정차 중 → 한 번만 다시 알림"
        AlertType.YELLOW_CAUTION -> "반응 1초 + 편안한 제동으로 정지선에 설 수 있는 거리 → 정지 준비"
        AlertType.YELLOW_DILEMMA -> "정지선이 너무 가까워 급정거하면 추돌 위험 → '정지' 대신 주의 진행"
        AlertType.RED_STOP -> "보통 제동으로 설 수 있는 거리 → 놀라지 않게 차분한 안내"
        AlertType.RED_URGENT -> "감속 없이 접근, 정지선까지 필요한 제동이 2.5m/s² 초과 → 긴급 경고"
        AlertType.SIGNAL_LOST -> "접근할 때 보이던 신호등이 사라짐 → 침묵 대신 직접 확인 요청"
        AlertType.LEFT_ARROW_FOR_ROUTE, AlertType.LEFT_ARROW_NOT_FOR_ROUTE, AlertType.LEFT_ARROW_ON -> "좌회전 화살표 + 내비 경로를 함께 판단"
        AlertType.SEATBELT_UNBUCKLED -> "벨트 경고음을 못 듣는 운전자 → 화면 깜빡임 (Pleos Vehicle State)"
        AlertType.DOOR_OPEN_MOVING -> "주행 중 문 열림 → 긴급 (Pleos Vehicle State)"
        AlertType.LOW_TIRE_PRESSURE -> "계기판 경고등을 놓치기 쉬움 → 다른 감각으로 (Pleos Vehicle State)"
        AlertType.TURN_SIGNAL_LEFT_ON -> "방향지시등 20초 이상 + 30km/h 이상 + 안내 지점 아님 → 끄는 것을 잊음"
        AlertType.HEADLIGHTS_OFF_DARK -> "어두운데 전조등 꺼짐 → 음성으로 (저시력)"
        AlertType.REAR_SEAT_CHECK -> "이번 주행에 뒷좌석 탑승 + 주차(P) → 내리기 전 확인"
        AlertType.SIREN_NEARBY -> "실내 마이크에서 오르내리는 사이렌음 감지 → 화면 깜빡임 (기기 안에서만 분석)"
        AlertType.NAV_MANEUVER_SOON -> "내비 음성 대신 300m·100m 앞 큰 카드 (Pleos NaviHelper)"
    }
}
