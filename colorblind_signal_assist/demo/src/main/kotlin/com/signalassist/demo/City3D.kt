package com.signalassist.demo

import com.signalassist.core.Frame
import com.signalassist.core.SignalColor
import com.signalassist.core.SignalState
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * OpenStreetMap 건물(바닥 모양 + 높이)과 도로로 센텀시티를 3D 로 그리는 간단한 소프트웨어 렌더러.
 * GPU 없이 Java2D 로 다각형을 먼 것부터 그린다(화가 알고리즘). 거리 안개, 면 방향에 따른 명암, 층 줄무늬를 넣는다.
 * 운전석 시점 화면은 그대로 신호 인식기(TrafficLightDetector) 입력으로도 쓴다.
 */
internal class City3D(mapFile: File) {
    class Building(val xs: FloatArray, val ys: FloatArray, val height: Float, val cx: Float, val cy: Float, val tint: Int)
    class Road(val xs: FloatArray, val ys: FloatArray, val width: Float)

    val buildings = ArrayList<Building>()
    val roads = ArrayList<Road>()
    val water = ArrayList<Pair<FloatArray, FloatArray>>()
    var route: Pair<FloatArray, FloatArray> = FloatArray(0) to FloatArray(0)

    init {
        var seed = 1
        for (line in mapFile.readLines()) {
            val p = line.split(" ")
            fun coords(tokens: List<String>): Pair<FloatArray, FloatArray> {
                val xs = FloatArray(tokens.size); val ys = FloatArray(tokens.size)
                tokens.forEachIndexed { i, t -> val (x, y) = t.split(","); xs[i] = x.toFloat(); ys[i] = y.toFloat() }
                return xs to ys
            }
            when (p[0]) {
                "B" -> {
                    val (xs, ys) = coords(p.drop(2))
                    buildings += Building(xs, ys, p[1].toFloat().coerceIn(4f, 220f), xs.average().toFloat(), ys.average().toFloat(), (seed++ * 37) % 5)
                }
                "R" -> { val (xs, ys) = coords(p.drop(2)); roads += Road(xs, ys, max(6f, p[1].toInt() * 3.3f)) }
                "W" -> water += coords(p.drop(1))
                "P" -> route = coords(p.drop(1))
            }
        }
        // 도로 위를 덮는 구조물(육교·캐노피 등)은 '건물'로 세우면 길을 막으므로 뺀다
        // 도로 중심선을 5m 간격으로 촘촘히 살펴, 그 위에 걸친 '건물'을 찾는다
        val roadPts = ArrayList<Pair<Float, Float>>()
        for (r in roads) for (i in 0 until r.xs.size - 1) {
            val len = hypot((r.xs[i + 1] - r.xs[i]).toDouble(), (r.ys[i + 1] - r.ys[i]).toDouble())
            val n = max(1, (len / 5).toInt())
            for (k in 0..n) roadPts += (r.xs[i] + (r.xs[i + 1] - r.xs[i]) * k / n) to (r.ys[i] + (r.ys[i + 1] - r.ys[i]) * k / n)
        }
        buildings.removeAll { b -> roadPts.any { (x, y) -> abs(x - b.cx) < 150 && abs(y - b.cy) < 150 && inside(b, x, y) } }
    }

    private fun inside(b: Building, x: Float, y: Float): Boolean {
        var c = false
        var j = b.xs.size - 1
        for (i in b.xs.indices) {
            if ((b.ys[i] > y) != (b.ys[j] > y) && x < (b.xs[j] - b.xs[i]) * (y - b.ys[i]) / (b.ys[j] - b.ys[i]) + b.xs[i]) c = !c
            j = i
        }
        return c
    }

    /** 카메라: 위치(m), 높이, 방향(북쪽 0, 시계방향 라디안), 기울기(아래가 음수) */
    data class Camera(
        val x: Double, val y: Double, val z: Double, val heading: Double, val pitch: Double,
        val w: Int, val h: Int, val focal: Double, val cx: Double, val cy: Double,
    )

    /** 3D 로 세울 물체 */
    sealed class Item(val dist: Double)
    class Car(val x: Float, val y: Float, val headingDeg: Float, val color: Color, val braking: Boolean, d: Double) : Item(d)
    /** 신호등: 카메라 기준 앞쪽 거리·오른쪽·높이(m)에 둔다 (주행 기록의 정지선 거리와 맞추기 위해) */
    class Light(val forward: Double, val right: Double, val up: Double, val state: SignalState) : Item(forward)

    private val skyTop = Color(112, 160, 218)
    private val skyHorizon = Color(196, 214, 232)
    private val ground = Color(150, 150, 146)
    private val asphalt = Color(78, 80, 86)
    private val waterColor = Color(52, 92, 140)

    private class P3(val r: Double, val u: Double, val f: Double)

    private fun toCam(c: Camera, x: Double, y: Double, z: Double): P3 {
        val dx = x - c.x; val dy = y - c.y; val dz = z - c.z
        val sh = sin(c.heading); val ch = cos(c.heading)
        val f0 = dx * sh + dy * ch
        val r = dx * ch - dy * sh
        val cp = cos(c.pitch); val sp = sin(c.pitch)
        return P3(r, dz * cp - f0 * sp, f0 * cp + dz * sp)
    }

    /** 가까운 평면(f >= near) 으로 다각형 자르기 */
    private fun clipNear(pts: List<P3>, near: Double = 0.6): List<P3> {
        if (pts.isEmpty()) return pts
        val out = ArrayList<P3>(pts.size + 2)
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            val ain = a.f >= near; val bin = b.f >= near
            if (ain) out += a
            if (ain != bin) {
                val t = (near - a.f) / (b.f - a.f)
                out += P3(a.r + (b.r - a.r) * t, a.u + (b.u - a.u) * t, near)
            }
        }
        return out
    }

    private fun project(c: Camera, pts: List<P3>): Path2D.Double? {
        val cl = clipNear(pts)
        if (cl.size < 3) return null
        val path = Path2D.Double()
        cl.forEachIndexed { i, p ->
            val sx = c.cx + c.focal * p.r / p.f
            val sy = c.cy - c.focal * p.u / p.f
            if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
        }
        path.closePath()
        return path
    }

    private fun fog(col: Color, d: Double, start: Double = fogStart, end: Double = fogEnd): Color {
        val t = ((d - start) / (end - start)).coerceIn(0.0, 0.85)
        return Color(
            (col.red + (skyHorizon.red - col.red) * t).toInt(),
            (col.green + (skyHorizon.green - col.green) * t).toInt(),
            (col.blue + (skyHorizon.blue - col.blue) * t).toInt(),
        )
    }

    private val sun = doubleArrayOf(-0.55, -0.35) // 남서쪽에서 비치는 해 (벽 명암)

    /** 안개 시작·끝 거리 (항공 시점은 멀리서 보므로 크게) */
    private var fogStart = 140.0
    private var fogEnd = 900.0

    fun render(img: BufferedImage, c: Camera, items: List<Item> = emptyList(), drawRoute: Boolean = false, viewRange: Double = 900.0) {
        val aerial = c.z > 50
        fogStart = if (aerial) 1400.0 else 140.0
        fogEnd = if (aerial) 4000.0 else 900.0
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        // 하늘 (지평선 높이는 기울기에 따라)
        val horizonY = (c.cy - c.focal * Math.tan(-c.pitch)).toInt().coerceIn(-c.h, 2 * c.h)
        g.paint = java.awt.GradientPaint(0f, 0f, skyTop, 0f, max(1, horizonY).toFloat(), skyHorizon)
        g.fillRect(0, 0, c.w, max(0, horizonY))
        g.color = if (aerial) Color(156, 162, 150) else ground; g.fillRect(0, max(0, horizonY), c.w, c.h)

        // 땅 위 평면: 물, 도로 (먼 것부터)
        water.forEach { (xs, ys) ->
            project(c, xs.indices.map { toCam(c, xs[it].toDouble(), ys[it].toDouble(), 0.0) })?.let { g.color = waterColor; g.fill(it) }
        }
        val roadQuads = ArrayList<Pair<Double, Path2D.Double>>()
        for (r in roads) {
            for (i in 0 until r.xs.size - 1) {
                val x1 = r.xs[i].toDouble(); val y1 = r.ys[i].toDouble(); val x2 = r.xs[i + 1].toDouble(); val y2 = r.ys[i + 1].toDouble()
                val mx = (x1 + x2) / 2; val my = (y1 + y2) / 2
                val d = hypot(mx - c.x, my - c.y)
                if (d > viewRange) continue
                val len = hypot(x2 - x1, y2 - y1); if (len < 0.1) continue
                val nx = -(y2 - y1) / len * r.width / 2; val ny = (x2 - x1) / len * r.width / 2
                // 이음새가 벌어지지 않게 양끝을 조금 늘림
                val ex = (x2 - x1) / len * 1.5; val ey = (y2 - y1) / len * 1.5
                val quad = listOf(
                    toCam(c, x1 - ex + nx, y1 - ey + ny, 0.02), toCam(c, x2 + ex + nx, y2 + ey + ny, 0.02),
                    toCam(c, x2 + ex - nx, y2 + ey - ny, 0.02), toCam(c, x1 - ex - nx, y1 - ey - ny, 0.02),
                )
                project(c, quad)?.let { roadQuads += d to it }
            }
        }
        roadQuads.sortByDescending { it.first }
        for ((d, p) in roadQuads) { g.color = fog(if (aerial) Color(70, 72, 80) else asphalt, d); g.fill(p) }
        // 차선 (도로 중앙선 점선) — 가까운 곳만
        g.stroke = BasicStroke(max(2f, (c.w / 400f)))
        for (r in roads) {
            if (r.width < 9f) continue
            for (i in 0 until r.xs.size - 1) {
                val x1 = r.xs[i].toDouble(); val y1 = r.ys[i].toDouble(); val x2 = r.xs[i + 1].toDouble(); val y2 = r.ys[i + 1].toDouble()
                val len = hypot(x2 - x1, y2 - y1)
                var s = 0.0
                while (s < len) {
                    val ax = x1 + (x2 - x1) * s / len; val ay = y1 + (y2 - y1) * s / len
                    val e = min(len, s + 3.0)
                    val bx = x1 + (x2 - x1) * e / len; val by = y1 + (y2 - y1) * e / len
                    val d = hypot(ax - c.x, ay - c.y)
                    if (d < 160) {
                        val a = toCam(c, ax, ay, 0.04); val b = toCam(c, bx, by, 0.04)
                        if (a.f > 1 && b.f > 1) {
                            g.color = fog(Color(225, 225, 215), d)
                            g.drawLine((c.cx + c.focal * a.r / a.f).toInt(), (c.cy - c.focal * a.u / a.f).toInt(),
                                (c.cx + c.focal * b.r / b.f).toInt(), (c.cy - c.focal * b.u / b.f).toInt())
                        }
                    }
                    s += 9.0
                }
            }
        }
        if (drawRoute) {
            val (xs, ys) = route
            g.stroke = BasicStroke(max(2f, c.w / 260f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = Color(0, 200, 140)
            for (i in 0 until xs.size - 1) {
                val a = toCam(c, xs[i].toDouble(), ys[i].toDouble(), 0.3); val b = toCam(c, xs[i + 1].toDouble(), ys[i + 1].toDouble(), 0.3)
                if (a.f > 1 && b.f > 1) g.drawLine((c.cx + c.focal * a.r / a.f).toInt(), (c.cy - c.focal * a.u / a.f).toInt(),
                    (c.cx + c.focal * b.r / b.f).toInt(), (c.cy - c.focal * b.u / b.f).toInt())
            }
        }

        // 세워진 물체들: 건물·차·신호등을 거리순으로
        data class Draw(val d: Double, val fn: () -> Unit)
        val draws = ArrayList<Draw>()
        for (b in buildings) {
            val d = hypot(b.cx - c.x, b.cy - c.y)
            if (d > viewRange) continue
            val cam = toCam(c, b.cx.toDouble(), b.cy.toDouble(), 0.0)
            if (cam.f < -60 && c.pitch > -0.3) continue
            draws += Draw(d) { drawBuilding(g, c, b, d) }
        }
        for (it in items) draws += Draw(it.dist) {
            when (it) {
                is Car -> drawCar(g, c, it)
                is Light -> drawLight(g, c, it)
            }
        }
        draws.sortByDescending { it.d }
        draws.forEach { it.fn() }
        g.dispose()
    }

    private fun drawBuilding(g: Graphics2D, c: Camera, b: Building, d: Double) {
        val n = b.xs.size
        val base = intArrayOf(188, 182, 176, 170, 196)[b.tint]
        val walls = ArrayList<Pair<Double, () -> Unit>>()
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x1 = b.xs[i].toDouble(); val y1 = b.ys[i].toDouble(); val x2 = b.xs[j].toDouble(); val y2 = b.ys[j].toDouble()
            // 바깥 방향 법선 (꼭짓점 순서와 무관하게 중심 반대쪽으로)
            var nx = y2 - y1; var ny = -(x2 - x1)
            val mx = (x1 + x2) / 2; val my = (y1 + y2) / 2
            if ((mx - b.cx) * nx + (my - b.cy) * ny < 0) { nx = -nx; ny = -ny }
            val len = hypot(nx, ny); if (len < 0.01) continue
            nx /= len; ny /= len
            // 카메라를 보는 벽만
            if ((c.x - mx) * nx + (c.y - my) * ny <= 0) continue
            val light = 0.62 + 0.38 * max(0.0, -(nx * sun[0] + ny * sun[1]))
            val col = fog(Color((base * light).toInt(), (base * light).toInt(), (base * light * 1.06).toInt().coerceAtMost(255)), d)
            val h = b.height.toDouble()
            val pts = listOf(toCam(c, x1, y1, 0.0), toCam(c, x2, y2, 0.0), toCam(c, x2, y2, h), toCam(c, x1, y1, h))
            val wd = hypot(mx - c.x, my - c.y)
            walls += wd to {
                project(c, pts)?.let { path ->
                    g.color = col; g.fill(path)
                    // 층 줄무늬 (창문 띠)
                    if (d < 380) {
                        val floors = (h / 3.4).toInt()
                        val stripe = fog(Color((base * light * 0.72).toInt(), (base * light * 0.75).toInt(), (base * light * 0.85).toInt()), d)
                        g.color = stripe
                        g.stroke = BasicStroke(1f)
                        for (k in 1 until floors) {
                            val z = k * 3.4 - 1.2
                            val a = toCam(c, x1, y1, z); val bb = toCam(c, x2, y2, z)
                            if (a.f > 1 && bb.f > 1) g.drawLine((c.cx + c.focal * a.r / a.f).toInt(), (c.cy - c.focal * a.u / a.f).toInt(),
                                (c.cx + c.focal * bb.r / bb.f).toInt(), (c.cy - c.focal * bb.u / bb.f).toInt())
                        }
                    }
                }
            }
        }
        walls.sortByDescending { it.first }
        walls.forEach { it.second() }
        // 지붕
        val roof = (0 until n).map { toCam(c, b.xs[it].toDouble(), b.ys[it].toDouble(), b.height.toDouble()) }
        project(c, roof)?.let { g.color = fog(Color(base - 40, base - 38, base - 30), d); g.fill(it) }
    }

    private fun box(c: Camera, cx: Double, cy: Double, hd: Double, len: Double, wid: Double, z0: Double, z1: Double): List<List<P3>> {
        val s = sin(hd); val co = cos(hd)
        fun pt(a: Double, b: Double, z: Double) = toCam(c, cx + a * s + b * co, cy + a * co - b * s, z)
        val l = len / 2; val w = wid / 2
        val corners = listOf(l to w, l to -w, -l to -w, -l to w)
        val faces = ArrayList<List<P3>>()
        for (i in 0 until 4) {
            val (a1, b1) = corners[i]; val (a2, b2) = corners[(i + 1) % 4]
            faces += listOf(pt(a1, b1, z0), pt(a2, b2, z0), pt(a2, b2, z1), pt(a1, b1, z1))
        }
        faces += corners.map { (a, b) -> pt(a, b, z1) }
        return faces
    }

    private fun drawCar(g: Graphics2D, c: Camera, car: Car) {
        val hd = Math.toRadians(car.headingDeg.toDouble())
        val faces = box(c, car.x.toDouble(), car.y.toDouble(), hd, 4.4, 1.8, 0.3, 1.45)
        faces.sortedByDescending { f -> f.sumOf { it.f } / f.size }.forEachIndexed { i, f ->
            project(c, f)?.let { p ->
                val shade = if (i == faces.size - 1) 0.85 else 1.0
                g.color = fog(Color((car.color.red * shade).toInt(), (car.color.green * shade).toInt(), (car.color.blue * shade).toInt()), car.dist)
                g.fill(p)
            }
        }
        // 브레이크등 (앞차, 지평선 아래라 신호로 오인되지 않음)
        if (car.braking) {
            val s = sin(hd); val co = cos(hd)
            for (side in listOf(-0.7, 0.7)) {
                val px = car.x - 2.2 * s + side * co; val py = car.y - 2.2 * co - side * s
                val p = toCam(c, px, py, 0.95)
                if (p.f > 1) {
                    val r = max(1.5, c.focal * 0.12 / p.f)
                    g.color = Color(255, 30, 25)
                    g.fill(java.awt.geom.Ellipse2D.Double(c.cx + c.focal * p.r / p.f - r, c.cy - c.focal * p.u / p.f - r, 2 * r, 2 * r))
                }
            }
        }
    }

    /** 국내 가로형 4구 신호등 (적·황·좌회전·녹), 등화 지름 300mm */
    private fun drawLight(g: Graphics2D, c: Camera, l: Light) {
        if (l.forward < 2) return
        val f = l.forward
        val sx = c.cx + c.focal * l.right / f
        val sy = c.cy - c.focal * (l.up) / f
        val r = c.focal * 0.15 / f
        val pitch = 2.5 * r
        // 지주와 팔
        val poleX = c.cx + c.focal * (l.right + 6 * 0.15 * 2.5 / 1.0 * 0.9) / f
        val groundY = c.cy - c.focal * (-c.z) / f
        g.color = Color(70, 70, 72)
        g.fill(java.awt.geom.Rectangle2D.Double(poleX - max(1.0, r * 0.35), sy, max(2.0, r * 0.7), max(0.0, groundY - sy)))
        g.fill(java.awt.geom.Rectangle2D.Double(sx, sy - r * 0.3, poleX - sx, r * 0.6))
        g.color = Color(22, 22, 24)
        g.fill(java.awt.geom.Rectangle2D.Double(sx - 2 * pitch, sy - 1.3 * r, 4 * pitch, 2.6 * r))
        val colors = listOf(Color(255, 40, 30), Color(255, 185, 0), Color(0, 230, 170), Color(0, 230, 170))
        val lit = listOf(l.state.color == SignalColor.RED, l.state.color == SignalColor.YELLOW, l.state.leftTurnArrow, l.state.color == SignalColor.GREEN)
        for (i in 0 until 4) {
            val lx = sx + (i - 1.5) * pitch
            val col = colors[i]
            if (lit[i]) {
                g.color = Color(col.red, col.green, col.blue, 90)
                g.fill(java.awt.geom.Ellipse2D.Double(lx - r * 1.35, sy - r * 1.35, 2.7 * r, 2.7 * r))
                g.color = col
                g.fill(java.awt.geom.Ellipse2D.Double(lx - r, sy - r, 2 * r, 2 * r))
                g.color = Color((col.red + 255) / 2, (col.green + 255) / 2, (col.blue + 255) / 2)
                g.fill(java.awt.geom.Ellipse2D.Double(lx - r * 0.42, sy - r * 0.42, 0.84 * r, 0.84 * r))
            } else {
                g.color = Color(col.red / 7 + 18, col.green / 7 + 18, col.blue / 7 + 18)
                g.fill(java.awt.geom.Ellipse2D.Double(lx - r, sy - r, 2 * r, 2 * r))
            }
        }
    }

    /** 지도 좌표를 화면 좌표로 (화면 뒤쪽이면 null). 영상의 이름표용 */
    /** 가장 큰 물(수영강) 영역의 중심 */
    val waterCenter: Pair<Double, Double>?
        get() = water.maxByOrNull { (xs, _) -> xs.size }?.let { (xs, ys) -> xs.average() to ys.average() }

    fun projectPoint(c: Camera, x: Double, y: Double, z: Double): java.awt.geom.Point2D.Double? {
        val p = toCam(c, x, y, z)
        if (p.f < 1) return null
        return java.awt.geom.Point2D.Double(c.cx + c.focal * p.r / p.f, c.cy - c.focal * p.u / p.f)
    }

    companion object {
        /** 렌더링 결과를 신호 인식기 입력 형식(ARGB)으로 */
        fun toFrame(img: BufferedImage): Frame {
            val data = (img.raster.dataBuffer as DataBufferInt).data
            return Frame(img.width, img.height, IntArray(data.size) { data[it] or (0xFF shl 24) })
        }

    }
}
