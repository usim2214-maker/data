package com.signalassist.demo

import com.signalassist.core.AlertType
import com.signalassist.core.Delivery
import com.signalassist.core.DriverNeed
import com.signalassist.core.DriverProfile
import com.signalassist.core.DrivingAssistEngine
import com.signalassist.core.DrivingOutput
import com.signalassist.core.EngineOutput
import com.signalassist.core.LampDetection
import com.signalassist.core.SignalColor
import com.signalassist.core.SignalQuery
import com.signalassist.core.SignalState
import com.signalassist.core.TraceParser
import com.signalassist.core.TracePlayer
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 부산 해운대구 센텀시티 실제 도로(OpenStreetMap + SUMO) 주행 시연.
 *   ./gradlew :demo:renderCentum     # build/video/모두의운전석_센텀시티.mp4
 */
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/video/모두의운전석_센텀시티.mp4").absoluteFile
    val audio = AudioTimeline()
    encodeVideo(out, 1280, 720, audio) { emit ->
        val img = BufferedImage(1280, 720, BufferedImage.TYPE_3BYTE_BGR)
        val g = img.createGraphics().apply { setupHints() }
        var t = 0L
        t += centumCard(g, img, emit, 5_000) { gg, tt -> centumIntro(gg, tt) }
        t += CentumRun(audio, standaloneIndex = null).play(g, img, emit, t)
        t += centumCard(g, img, emit, 6_000) { gg, _ -> centumOutro(gg) }
        t
    }
}

internal fun Graphics2D.setupHints() {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
}

private fun centumCard(g: Graphics2D, img: BufferedImage, emit: (BufferedImage) -> Unit, ms: Long, draw: (Graphics2D, Long) -> Unit): Long {
    val frames = (ms * FPS / 1000.0).roundToInt()
    repeat(frames) { f ->
        val t = f * 1000L / FPS
        g.color = Pal.bg; g.fillRect(0, 0, 1280, 720)
        draw(g, t)
        fadeBlack(g, t, ms, 1280, 720)
        emit(img)
    }
    return ms
}

internal fun fadeBlack(g: Graphics2D, t: Long, len: Long, w: Int, h: Int) {
    val f = 300L
    val a = when { t < f -> 1f - t.toFloat() / f; t > len - f -> (t - (len - f)).toFloat() / f; else -> 0f }
    if (a <= 0f) return
    val old = g.composite
    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a.coerceIn(0f, 1f))
    g.color = Color.BLACK; g.fillRect(0, 0, w, h)
    g.composite = old
}

internal fun centumIntro(g: Graphics2D, t: Long) {
    Fonts.center(g, "실제 도로에서 달려 봤습니다", 640f, 220f, Fonts.r(30f), Pal.dim)
    Fonts.center(g, "부산 해운대구 센텀시티 · 해운대로", 640f, 300f, Fonts.b(54f), Pal.text)
    Fonts.center(g, "OpenStreetMap 실제 도로·차로·신호등 위치 + SUMO 교통 시뮬레이션 (주변 차량 포함)", 640f, 380f, Fonts.r(26f), Pal.sky)
    Fonts.center(g, "해운대로 → 센텀5로 · 센텀4로 · 센텀남대로 교차로 → APEC로  |  1.76km · 경로상 신호 12개", 640f, 430f, Fonts.r(24f), Pal.text)
    Fonts.center(g, "※ 건물·도로는 OpenStreetMap 실제 데이터로 3D 재현 · 신호 주기는 시뮬레이션 생성값으로 실제와 다릅니다", 640f, 520f, Fonts.r(20f), Pal.dim)
    Fonts.center(g, "지도 데이터 © OpenStreetMap contributors", 640f, 560f, Fonts.r(18f), Pal.dim)
}

internal fun centumOutro(g: Graphics2D) {
    Fonts.center(g, "실제 도로 주행 결과", 640f, 200f, Fonts.b(44f), Pal.text)
    listOf(
        "보통 정차 2번: 노란불·빨간불을 차분한 안내로 (거짓 긴급 경고 0회)",
        "빨간불을 못 본 장면: 운전자가 반응하기 전 긴급 경고 → 정지선 앞 정지",
        "초록불 늦은 출발: 3초 뒤 한 번만 리마인드",
        "헷갈릴 땐 '지금 신호?': 확실할 때만 답함",
    ).forEachIndexed { k, l -> Fonts.center(g, l, 640f, 290f + k * 52, Fonts.r(26f), if (k == 1) Pal.amber else Pal.text) }
    Fonts.center(g, "실제 도로에서 시험하며 거짓 경보 3가지를 찾아 고쳤습니다 (먼 신호·정상 정차·출발 순간)", 640f, 540f, Fonts.r(22f), Pal.green)
    Fonts.center(g, "지도 데이터 © OpenStreetMap contributors · 신호 주기는 시뮬레이션 생성값", 640f, 620f, Fonts.r(18f), Pal.dim)
}

// ---------------------------------------------------------------- 지도

private class CentumMap(file: File) {
    class Road(val lanes: Int, val path: Path2D.Float)
    class Building(val height: Float, val path: Path2D.Float)
    class Label(val x: Float, val y: Float, val text: String)
    class Signal(val x: Float, val y: Float)

    val roads = ArrayList<Road>()
    val buildings = ArrayList<Building>()
    val water = ArrayList<Path2D.Float>()
    val labels = ArrayList<Label>()
    val stations = ArrayList<Label>()
    val signals = ArrayList<Signal>()
    var route: Path2D.Float = Path2D.Float()

    private fun pts(tokens: List<String>, close: Boolean): Path2D.Float = Path2D.Float().apply {
        tokens.forEachIndexed { i, t ->
            val (x, y) = t.split(",").map { it.toFloat() }
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        if (close) closePath()
    }

    init {
        for (line in file.readLines()) {
            val p = line.split(" ")
            when (p[0]) {
                "R" -> roads += Road(p[1].toInt(), pts(p.drop(2), false))
                "B" -> buildings += Building(p[1].toFloat(), pts(p.drop(2), true))
                "W" -> water += pts(p.drop(1), true)
                "N" -> labels += Label(p[1].toFloat(), p[2].toFloat(), p.drop(4).joinToString(" "))
                "S" -> stations += Label(p[1].toFloat(), p[2].toFloat(), p.drop(3).joinToString(" "))
                "T" -> signals += Signal(p[3].toFloat(), p[4].toFloat())
                "P" -> route = pts(p.drop(1), false)
            }
        }
        buildings.sortBy { it.height }
    }
}

// ---------------------------------------------------------------- 실제 도로 주행 장면

/**
 * @param standaloneIndex 본 시연 영상 안에 들어갈 때의 시나리오 번호 (단독 영상이면 null)
 */
internal class CentumRun(private val audio: AudioTimeline, private val standaloneIndex: Int?) {
    private val dir = listOf(File("../sim/data"), File("sim/data")).first { File(it, "centum_trace.csv").exists() }
    private val city = City3D(File(dir, "centum_map.txt"))
    private val camBuf = java.awt.image.BufferedImage(960, 540, java.awt.image.BufferedImage.TYPE_INT_RGB)
    private val carColors = listOf(Color(150, 155, 165), Color(60, 64, 72), Color(210, 212, 214), Color(120, 30, 34), Color(40, 60, 100))

    /** 전방 카메라: 실제 센텀 건물로 만든 3D 시점 (신호 인식기도 이 화면을 본다) */
    private fun render3D(index: Int, s: com.signalassist.core.TraceStep, light: SignalState?, lightDist: Float): com.signalassist.core.Frame {
        val cam = City3D.Camera(s.x.toDouble(), s.y.toDouble(), 1.3, Math.toRadians(s.angleDeg.toDouble()), 0.0,
            960, 540, 960 * 0.866, 480.0, 540 * 0.55)
        val items = ArrayList<City3D.Item>()
        if (light != null) items += City3D.Light(lightDist.toDouble(), 1.0, 4.0, light)
        s.others.forEachIndexed { k, (ox, oy, a) ->
            val d = Math.hypot((ox - s.x).toDouble(), (oy - s.y).toDouble())
            if (d < 220) items += City3D.Car(ox, oy, a, carColors[(ox.toInt() * 31 + oy.toInt() * 17 + k).mod(carColors.size)], false, d)
        }
        city.render(camBuf, cam, items, viewRange = 700.0)
        return City3D.toFrame(camBuf)
    }

    private val player = TracePlayer(TraceParser.parse(File(dir, "centum_trace.csv").readLines()), frameRenderer = ::render3D)
    private val map = CentumMap(File(dir, "centum_map.txt"))
    private val profile = DriverProfile(setOf(DriverNeed.COLOR_VISION))
    private val queries = listOf(350) // 첫 빨간불 대기 중 '지금 신호?' (35.0초)

    private val outputs = ArrayList<DrivingOutput>()
    private val queryAnswers = HashMap<Int, Delivery>()
    private val eventIdx = ArrayList<Int>()

    init {
        val engine = DrivingAssistEngine(profile)
        var lastSignal: EngineOutput? = null
        for (i in 0 until player.frameCount) {
            val s = player.sample(i)
            val out = engine.process(s.toInput())
            outputs += out.copy(input = out.input.copy(frame = null)) // 영상 프레임은 버리고 결과만 보관 (메모리)
            out.signal?.let { lastSignal = it.copy(timestampMs = s.timestampMs) }
            if (out.deliveries.isNotEmpty()) {
                eventIdx += i
                out.deliveries.forEach { println("[3D] %.1fs %s".format(s.timestampMs / 1000.0, it.alert.message)) }
            }
            if (i in queries) {
                val a = SignalQuery.answer(lastSignal, s.timestampMs)
                queryAnswers[i] = Delivery(a, SignalQuery.plan(profile, a))
                eventIdx += i
            }
        }
    }

    /** 영상 프레임마다 보여줄 주행 기록 위치와 재생 배속 */
    private fun timeline(): List<Pair<Int, Float>> {
        val out = ArrayList<Pair<Int, Float>>()
        var t = 0.0
        val end = (player.frameCount - 1) / 10.0
        while (t <= end) {
            val idx = (t * 10).roundToInt().coerceAtMost(player.frameCount - 1)
            val interesting = eventIdx.any { e -> t in (e / 10.0 - 2.5)..(e / 10.0 + 4.0) } || t < 2.0
            val stopped = player.step(idx).speedKmh < 0.5
            val rate = if (interesting) 1f else if (stopped) 15f else 6f
            out += idx to rate
            t += rate / FPS
        }
        return out
    }

    // 화면 상태
    private var banner: Delivery? = null
    private var bannerAt = 0L
    private var flash: Delivery? = null
    private var flashAt = 0L
    private var voice: String? = null
    private var voiceUntil = 0L
    private var caption: String? = null
    private var captionUntil = 0L
    private var signal = SignalState.UNKNOWN
    private var lamps: List<LampDetection> = emptyList()
    private var distanceM: Float? = null
    private var heading = Double.NaN
    private var pressedAt = -10_000L
    private var camIdx = -1
    private lateinit var cam: BufferedImage

    /** 센텀시티 상공을 도는 항공 시점 (실제 건물 높이) */
    private fun flyover(g: Graphics2D, img: BufferedImage, emit: (BufferedImage) -> Unit, ms: Long): Long {
        val (rx, ry) = city.route
        val cxw = rx.average(); val cyw = ry.average()
        val frames = (ms * FPS / 1000.0).roundToInt()
        val buf = BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB)
        val labels = listOf(
            Triple(map.stations, "Ⓜ", Color(60, 90, 160)),
        )
        repeat(frames) { f ->
            val t = f.toDouble() / frames
            val ang = Math.toRadians(205.0 + 70.0 * t)          // 남서쪽에서 시작해 천천히 돈다
            val radius = 900.0 - 250.0 * t
            val camX = cxw + radius * sin(ang); val camY = cyw + radius * cos(ang)
            val height = 420.0 - 170.0 * t
            val heading = Math.atan2(cxw - camX, cyw - camY)
            val cam = City3D.Camera(camX, camY, height, heading, -Math.atan2(height, radius) + 0.05, 1280, 720, 1280 * 0.8, 640.0, 360.0)
            city.render(buf, cam, emptyList(), drawRoute = true, viewRange = 2200.0)
            g.drawImage(buf, 0, 0, null)
            for ((list, mark, col) in labels) for (st in list) {
                val p = city.projectPoint(cam, st.x.toDouble(), st.y.toDouble(), 30.0) ?: continue
                if (p.x in 40.0..1200.0 && p.y in 60.0..680.0) g.chip(p.x.toFloat() - 40, p.y.toFloat() - 14, "$mark ${st.text}", col, size = 16f)
            }
            city.waterCenter?.let { (wx, wy) ->
                city.projectPoint(cam, wx, wy, 2.0)?.let { p -> if (p.x in 40.0..1200.0 && p.y in 60.0..680.0) g.chip(p.x.toFloat() - 30, p.y.toFloat() - 12, "수영강", Color(30, 70, 120), size = 16f) }
            }
            // 출발점 표시
            val sp = city.projectPoint(cam, rx.first().toDouble(), ry.first().toDouble(), 6.0)
            if (sp != null) { g.color = Pal.green; g.fill(Ellipse2D.Double(sp.x - 9, sp.y - 9, 18.0, 18.0)); g.chip(sp.x.toFloat() + 12, sp.y.toFloat() - 14, "출발", Color(0, 0, 0, 170), size = 15f) }
            val ep = city.projectPoint(cam, rx.last().toDouble(), ry.last().toDouble(), 6.0)
            if (ep != null) { g.color = Pal.amber; g.fill(Ellipse2D.Double(ep.x - 9, ep.y - 9, 18.0, 18.0)); g.chip(ep.x.toFloat() + 12, ep.y.toFloat() - 14, "도착", Color(0, 0, 0, 170), size = 15f) }
            g.round(24, 24, 760, 92, 18f, Color(0, 0, 0, 170))
            Fonts.draw(g, "부산 해운대구 센텀시티 — 실제 건물 ${city.buildings.size}개를 높이대로 세운 3D", 44f, 62f, Fonts.b(24f), Color.WHITE)
            Fonts.draw(g, "초록 선: 주행 경로 (해운대로 → APEC로, 1.76km, 신호 12곳)", 44f, 96f, Fonts.r(19f), Pal.sky)
            Fonts.draw(g, "© OpenStreetMap contributors", 1080f, 706f, Fonts.r(14f), Color(40, 40, 40))
            fadeBlack(g, f * 1000L / FPS, ms, 1280, 720)
            emit(img)
        }
        return ms
    }

    fun play(g: Graphics2D, img: BufferedImage, emit: (BufferedImage) -> Unit, baseMs: Long): Long {
        val fly = flyover(g, img, emit, 8_000)
        return fly + drive(g, img, emit, baseMs + fly)
    }

    private fun drive(g: Graphics2D, img: BufferedImage, emit: (BufferedImage) -> Unit, baseMs: Long): Long {
        val tl = timeline()
        var lastIdx = -1
        tl.forEachIndexed { f, (idx, rate) ->
            val videoMs = f * 1000L / FPS
            // 건너뛴 기록도 순서대로 반영 (알림은 실시간 구간에서만 나오도록 배속을 정했다)
            for (i in (lastIdx + 1)..idx) apply(i, baseMs + videoMs)
            lastIdx = idx
            draw(g, idx, rate)
            fadeBlack(g, videoMs, tl.size * 1000L / FPS, 1280, 720)
            emit(img)
        }
        return tl.size * 1000L / FPS
    }

    private fun apply(i: Int, videoMs: Long) {
        val out = outputs[i]
        val now = out.input.timestampMs
        out.signal?.let { signal = it.state; lamps = it.detection.lamps; distanceM = it.distanceM }
        val ds = out.deliveries + listOfNotNull(queryAnswers[i])
        for (d in ds) {
            if (banner == null || !d.plan.silent || d.alert.type == AlertType.SIGNAL_STATUS) { banner = d; bannerAt = now }
            if (d.plan.visualFlash) { flash = d; flashAt = now }
            if (d.plan.tone) audio.add(videoMs, d.alert.tone, d.plan)
            if (d.plan.voice) { voice = d.alert.speech; voiceUntil = now + 4_000 }
            caption = if (d.alert.type == AlertType.SIGNAL_STATUS) {
                pressedAt = now
                "운전자가 '지금 신호?'를 누름 → 최근 프레임이 모두 빨간불이라 확실하게 답함 (신호등까지 거리 포함)"
            } else if (d.alert.type == AlertType.RED_STOP && i < 200) {
                "사람처럼 일찍 감속 중 → 긴급 경고 없이 차분한 정지 안내"
            } else why(d.alert)
            captionUntil = now + 4_500
        }
    }

    private fun draw(g: Graphics2D, idx: Int, rate: Float) {
        val step = player.step(idx)
        val out = outputs[idx]
        val now = out.input.timestampMs
        if (camIdx != idx) { cam = toImage(player.sample(idx)); camIdx = idx }
        g.color = Pal.bg; g.fillRect(0, 0, 1280, 720)

        // 상단
        val title = (standaloneIndex?.let { "시나리오 $it  " } ?: "") + "실제 도로 · 부산 해운대구 센텀시티"
        Fonts.draw(g, title, 24f, 40f, Fonts.b(24f), Pal.text)
        g.chip(24f, 52f, "색각이상", Pal.line, size = 15f)
        g.chip(120f, 52f, "OpenStreetMap + SUMO", Color(20, 50, 70), size = 15f)
        val right = "%.0f km/h · %.1f초".format(step.speedKmh, step.tSec)
        Fonts.draw(g, right, 1256f - Fonts.width(g, right, Fonts.r(22f)), 40f, Fonts.r(22f), Pal.dim)
        if (rate > 1f) g.chip(1256f - 120f, 50f, "▶▶ %.0f배속".format(rate), Pal.amber, Color.BLACK, size = 16f)

        // 전방 영상
        val cx = 24; val cy = 84; val cw = 640; val ch = 360
        g.drawImage(cam, cx, cy, cw, ch, null)
        val sx = cw / 960f; val sy = ch / 540f
        lamps.firstOrNull { it.score >= 0.12f && it.shape.name == "CIRCLE" }?.let { l ->
            val r = (l.radius * 2.2f).coerceAtLeast(9f)
            g.color = when (l.color) { SignalColor.RED -> Pal.vermilion; SignalColor.YELLOW -> Pal.amber; else -> Pal.green }
            g.stroke = BasicStroke(3f)
            g.drawRect((cx + (l.centerX - r) * sx).toInt(), (cy + (l.centerY - r) * sy).toInt(), (2 * r * sx).toInt(), (2 * r * sy).toInt())
            g.chip(cx + (l.centerX + r) * sx + 6, cy + (l.centerY - r) * sy - 4, l.color.labelKo + (distanceM?.let { " · %.0fm".format(it) } ?: ""), Color(0, 0, 0, 170), size = 15f)
        }
        if (out.activeHazards.isNotEmpty()) drawHazardBar(g, cx.toFloat(), cy.toFloat(), cw.toFloat(), out.activeHazards, now, 20f)
        g.round(cx + 8, cy + ch - 34, 236, 26, 9f, Color(0, 0, 0, 160))
        Fonts.draw(g, "전방 시점 · 실제 건물 3D 재현", cx + 18f, cy + ch - 15f, Fonts.r(14f), Pal.dim)

        // 위치 정보 줄
        g.round(24, 452, 640, 58, 14f, Pal.panel)
        val road = step.road.ifBlank { "교차로 통과 중" }
        val next = step.junction.takeIf { it.isNotBlank() && step.tlsDistM != null }?.let { "다음 신호  $it  %.0fm".format(step.tlsDistM) } ?: "다음 신호 없음"
        Fonts.draw(g, "▶ $road", 40f, 478f, Fonts.b(20f), Pal.text)
        Fonts.draw(g, next, 40f, 502f, Fonts.r(17f), Pal.sky)
        val geo = "%.5f, %.5f".format(step.lat, step.lon)
        Fonts.draw(g, geo, 648f - Fonts.width(g, geo, Fonts.r(14f)), 478f, Fonts.r(14f), Pal.dim)

        drawMinimap(g, step, 680, 84, 576, 300)
        drawAppPanel(g, 680, 396, 576, 114, now)
        drawBottom(g, now)
        flash?.let { drawFlashOverlay(g, 1280, 720, it, now - flashAt, 96f, 46f, 300f, 400f) }
    }

    private fun drawMinimap(g: Graphics2D, step: com.signalassist.core.TraceStep, x: Int, y: Int, w: Int, h: Int) {
        g.round(x, y, w, h, 18f, Color(18, 20, 24))
        val oldClip = g.clip
        val oldTx = g.transform
        g.clip(java.awt.geom.RoundRectangle2D.Float(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat(), 18f, 18f))
        // 진행 방향이 위로 오도록 회전 (내비처럼). 방향은 부드럽게
        val target = Math.toRadians(step.angleDeg.toDouble())
        heading = if (heading.isNaN()) target else {
            val d = atan2(sin(target - heading), cos(target - heading))
            heading + d * if (step.speedKmh > 2f) 0.08 else 0.0
        }
        val scale = 0.75 // 1m = 0.75px  (지도 폭 약 770m)
        val ex = step.x.toDouble(); val ey = step.y.toDouble()
        val ccx = x + w / 2.0; val ccy = y + h * 0.66
        val world = AffineTransform().apply {
            translate(ccx, ccy); rotate(-heading); scale(scale, -scale); translate(-ex, -ey)
        }
        g.transform(world)
        g.color = Color(30, 60, 92); map.water.forEach { g.fill(it) }
        for (b in map.buildings) {
            val shade = (52 + (b.height.coerceAtMost(120f) / 120f) * 60).toInt()
            g.color = Color(shade, shade + 2, shade + 8); g.fill(b.path)
        }
        for (r in map.roads) {
            g.color = Color(92, 96, 104)
            g.stroke = BasicStroke((r.lanes * 3.2f).coerceAtLeast(4f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(r.path)
        }
        g.color = Color(0, 158, 115, 140)
        g.stroke = BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(map.route)
        // 주변 차량
        g.color = Color(200, 204, 210)
        for ((ox, oy, _) in step.others) g.fill(Ellipse2D.Float(ox - 2.5f, oy - 2.5f, 5f, 5f))
        g.transform = oldTx

        fun toScreen(wx: Float, wy: Float): Point2D = world.transform(Point2D.Float(wx, wy), null)
        // 경로상 신호 (현재 상태 색 + 모양)
        map.signals.forEachIndexed { i, s ->
            val st = step.routeTls.getOrNull(i) ?: return@forEachIndexed
            val p = toScreen(s.x, s.y)
            val (c, sym) = when (st) { 'r', 'R' -> Pal.vermilion to "■"; 'y', 'Y' -> Pal.amber to "▲"; else -> Pal.green to "●" }
            g.color = Color(0, 0, 0, 180); g.fill(Ellipse2D.Double(p.x - 9, p.y - 9, 18.0, 18.0))
            g.color = c; g.fill(Ellipse2D.Double(p.x - 7, p.y - 7, 14.0, 14.0))
            Fonts.center(g, sym, p.x.toFloat(), p.y.toFloat() + 4, Fonts.b(9f), Color.BLACK)
        }
        // 도로 이름 · 역
        for (l in map.labels) {
            val p = toScreen(l.x, l.y)
            if (p.x < x + 20 || p.x > x + w - 20 || p.y < y + 16 || p.y > y + h - 10) continue
            Fonts.center(g, l.text, p.x.toFloat() + 1, p.y.toFloat() + 1, Fonts.r(13f), Color(0, 0, 0, 200))
            Fonts.center(g, l.text, p.x.toFloat(), p.y.toFloat(), Fonts.r(13f), Color(220, 224, 230))
        }
        for (st in map.stations) {
            val p = toScreen(st.x, st.y)
            if (p.x < x + 30 || p.x > x + w - 60 || p.y < y + 16 || p.y > y + h - 10) continue
            g.chip(p.x.toFloat() - 30, p.y.toFloat() - 12, "Ⓜ " + st.text, Color(60, 90, 160), size = 12f)
        }
        // 내 차
        val tri = Path2D.Float().apply { moveTo(ccx, ccy - 13); lineTo(ccx + 9, ccy + 9); lineTo(ccx, ccy + 4); lineTo(ccx - 9, ccy + 9); closePath() }
        g.color = Color.WHITE; g.stroke = BasicStroke(3f); g.draw(tri)
        g.color = Pal.green; g.fill(tri)
        // 북쪽 표시
        val nx = x + w - 30.0; val ny = y + 30.0
        g.color = Color(0, 0, 0, 150); g.fill(Ellipse2D.Double(nx - 16, ny - 16, 32.0, 32.0))
        val na = -heading
        val arrow = Path2D.Double().apply {
            moveTo(nx + 11 * sin(na), ny - 11 * cos(na)); lineTo(nx + 5 * cos(na), ny + 5 * sin(na)); lineTo(nx - 5 * cos(na), ny - 5 * sin(na)); closePath()
        }
        g.color = Pal.vermilion; g.fill(arrow)
        Fonts.center(g, "N", nx.toFloat(), ny.toFloat() + 13, Fonts.b(10f), Color.WHITE)
        g.clip = oldClip
        Fonts.draw(g, "© OpenStreetMap contributors", x + w - 182f, y + h - 8f, Fonts.r(11f), Color(200, 200, 200))
        Fonts.draw(g, "실제 지도 · 경로 신호", x + 12f, y + 22f, Fonts.r(13f), Pal.dim)
    }

    private fun drawAppPanel(g: Graphics2D, x: Int, y: Int, w: Int, h: Int, now: Long) {
        g.round(x, y, w, h, 18f, Pal.panel)
        Fonts.draw(g, "앱 화면", x + 14f, y + 22f, Fonts.r(13f), Pal.dim)
        val cell = 62f; val left = x + 16f; val top = y + 34f
        g.round(left, top, cell * 4, cell, 14f, Color(12, 12, 14))
        val lit = listOf(signal.color == SignalColor.RED, signal.color == SignalColor.YELLOW, false, signal.color == SignalColor.GREEN)
        val cols = listOf(Pal.vermilion, Pal.amber, Pal.green, Pal.green)
        for (i in 0 until 4) {
            val c = left + cell * (i + 0.5f)
            g.color = if (lit[i]) cols[i] else Pal.off
            g.fill(shape(i, c, top + cell / 2, cell * 0.33f))
            if (lit[i]) { g.color = Color.WHITE; g.stroke = BasicStroke(2.5f); g.drawRoundRect((c - cell / 2 + 4).toInt(), (top + 4).toInt(), (cell - 8).toInt(), (cell - 8).toInt(), 10, 10) }
        }
        val (sym, label) = when (signal.color) {
            SignalColor.RED -> "■" to "정지"; SignalColor.YELLOW -> "▲" to "주의"
            SignalColor.GREEN -> "●" to "진행"; SignalColor.UNKNOWN -> "?" to "인식 중"
        }
        Fonts.draw(g, "$sym $label", left + cell * 4 + 24, top + 44, Fonts.b(38f), Pal.text)
        val pressed = now - pressedAt in 0..700
        val bw = Fonts.width(g, "? 지금 신호", Fonts.b(16f)) + 22
        val bx = x + w - bw - 14f
        g.round(bx, y + 10f, bw, 30f, 11f, if (pressed) Pal.amber else Pal.line)
        Fonts.draw(g, "? 지금 신호", bx + 11f, y + 31f, Fonts.b(16f), if (pressed) Color.BLACK else Pal.text)
        if (pressed) { g.color = Pal.amber; g.stroke = BasicStroke(3f); g.drawRoundRect((bx - 5).toInt(), y + 5, (bw + 10).toInt(), 40, 14, 14) }
    }

    private fun drawBottom(g: Graphics2D, now: Long) {
        val y = 522
        g.round(24, y, 1232, 86, 18f, Pal.panel)
        val b = banner
        if (b != null) {
            g.round(24, y, 12, 86, 6f, if (now - bannerAt < 2_500) levelColor(b.alert.level) else Pal.line)
            Fonts.draw(g, "${b.alert.symbol}  ${b.alert.message}", 54f, y + 40f, Fonts.b(28f), Pal.text)
            val p = b.plan
            val chips = listOfNotNull(("소리: " + toneName(b.alert.tone)).takeIf { p.tone }, "음성".takeIf { p.voice },
                "화면 깜빡임".takeIf { p.visualFlash }, "헤드업 알림")
            var x = 54f
            for (c in chips) x += g.chip(x, y + 50f, c, Pal.line, size = 14f) + 8
        } else {
            Fonts.draw(g, "알림 대기 중", 54f, y + 52f, Fonts.r(24f), Pal.dim)
        }
        if (now < voiceUntil && voice != null) Fonts.draw(g, "음성 안내 “$voice”", 30f, 650f, Fonts.r(22f), Pal.sky)
        caption?.takeIf { now < captionUntil }?.let { c ->
            g.round(24, 664, 1232, 44, 12f, Color(40, 36, 10))
            Fonts.draw(g, "왜?  $c", 40f, 693f, Fonts.r(21f), Pal.amber)
        }
    }
}
