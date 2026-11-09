package com.pleos.cardex.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D 차량 모델 (코드로 생성) + 소프트웨어 렌더러 → 여러 각도로 돌려 보기.
 *
 * - 형상: 실제 제원(전장·전폭·전고·휠베이스, [REAL_DIMS])과 측면 실루엣([profileOf])으로 단면을 이어 붙여(lofting) 차체를 만든다.
 *   휠 아치, 그린하우스(유리), A/B 필러, 사이드미러, 램프(차종별 [LampStyle]), 알로이 휠까지 포함.
 * - 렌더링: 정점 법선 + 정점별 색(고로 셰이딩)으로 Canvas.drawVertices. 메탈릭 도장은 하늘/지평선/지면 환경 반사 + 프레넬 + 하이라이트.
 * - 로고·엠블럼·번호판은 만들지 않는다 (상표·개인정보). 사진은 쓰지 않는다 (저작권).
 */

/** 실제 제원 (mm, 공개 제원 기준 대략값): 전장, 전폭, 전고, 휠베이스 */
internal data class Dims(val l: Int, val w: Int, val h: Int, val wb: Int)

internal val REAL_DIMS = mapOf(
    "hyundai-ioniq-5" to Dims(4635, 1890, 1605, 3000),
    "hyundai-ioniq-5-n" to Dims(4715, 1940, 1585, 3000),
    "kia-ev9" to Dims(5010, 1980, 1755, 3100),
    "genesis-g80" to Dims(4995, 1925, 1465, 3010),
    "hyundai-grandeur" to Dims(5035, 1880, 1460, 2895),
    "kia-sorento" to Dims(4815, 1900, 1695, 2815),
    "hyundai-avante" to Dims(4710, 1825, 1420, 2720),
    "hyundai-avante-n" to Dims(4675, 1825, 1415, 2720),
    "hyundai-sonata" to Dims(4910, 1860, 1445, 2840),
    "hyundai-tucson" to Dims(4640, 1865, 1665, 2755),
    "hyundai-palisade" to Dims(5060, 1980, 1805, 2970),
    "hyundai-casper" to Dims(3595, 1595, 1575, 2400),
    "hyundai-ioniq-6" to Dims(4855, 1880, 1495, 2950),
    "hyundai-staria" to Dims(5255, 1995, 1990, 3275),
    "hyundai-kona" to Dims(4350, 1825, 1580, 2660),
    "kia-morning" to Dims(3595, 1595, 1485, 2400),
    "kia-ray" to Dims(3595, 1595, 1700, 2520),
    "kia-k5" to Dims(4905, 1860, 1445, 2850),
    "kia-k8" to Dims(5015, 1875, 1455, 2895),
    "kia-sportage" to Dims(4660, 1865, 1660, 2755),
    "kia-seltos" to Dims(4390, 1800, 1600, 2630),
    "kia-carnival" to Dims(5155, 1995, 1775, 3090),
    "kia-ev3" to Dims(4300, 1850, 1560, 2680),
    "kia-ev6" to Dims(4680, 1880, 1550, 2900),
    "genesis-g90" to Dims(5275, 1930, 1490, 3180),
    "genesis-gv70" to Dims(4715, 1910, 1630, 2875),
    "genesis-gv80" to Dims(4945, 1975, 1715, 2955),
    "genesis-gv60" to Dims(4515, 1890, 1580, 2900),
)

internal enum class Mat { PAINT, LOWER, GLASS, TRIM, UNDER, TIRE, RIM, LAMP_F, LAMP_R, GRILLE, CALIPER }

internal class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = V3(x * s, y * s, z * s)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun norm(): V3 { val l = sqrt(dot(this)); return if (l < 1e-9f) V3(0f, 1f, 0f) else V3(x / l, y / l, z / l) }
}

/** 삼각형: 정점 3개, 정점 법선 3개, 재질 */
internal class Tri(val a: V3, val b: V3, val c: V3, val na: V3, val nb: V3, val nc: V3, val mat: Mat)

internal class CarMesh(internal val tris: List<Tri>, val paint: Color, val halfWidth: Float, val height: Float, val caliper: Color?)

private val meshCache = HashMap<String, CarMesh>()

internal fun carMeshFor(id: String, style: CarStyle): CarMesh = synchronized(meshCache) {
    meshCache.getOrPut("$id|${style.hashCode()}") { buildMesh(id, style) }
}

private fun smooth01(t: Float): Float { val c = t.coerceIn(0f, 1f); return c * c * (3 - 2 * c) }

private fun buildMesh(id: String, style: CarStyle): CarMesh {
    val prof = profileOf(style)
    val dims = REAL_DIMS[id]
    val H = dims?.let { it.h.toFloat() / it.l } ?: prof.height
    val halfW = (dims?.let { it.w.toFloat() / it.l } ?: 0.39f) / 2f * 0.97f
    val wheelR = (when {
        dims == null -> 340f
        dims.l < 4000 -> 300f
        style.spoiler || style.caliper != null -> 355f
        dims.h > 1700 -> 375f
        else -> 345f
    }) / (dims?.l ?: 4800)
    val (axF, axR) = if (dims != null) {
        val over = (dims.l - dims.wb).toFloat() / dims.l
        val f = over * 0.44f
        f to f + dims.wb.toFloat() / dims.l
    } else prof.axles

    // 윗선 (지붕/후드/트렁크) — 측면 실루엣의 윗부분을 보간
    val upper = prof.body.drop(1).dropLast(1).sortedBy { it.x }
    fun topAt(p: Float): Float {
        if (p <= upper.first().x) return upper.first().y * H
        for (i in 0 until upper.size - 1) {
            val a = upper[i]; val b = upper[i + 1]
            if (p <= b.x) { val t = (p - a.x) / max(1e-4f, b.x - a.x); return (a.y + (b.y - a.y) * smooth01(t)) * H }
        }
        return upper.last().y * H
    }
    val g = prof.glass
    val beltBase = (g[0].y + g[3].y) / 2f * H
    fun beltAt(p: Float): Float = (g[0].y + (g[3].y - g[0].y) * ((p - g[0].x) / (g[3].x - g[0].x)).coerceIn(0f, 1f)) * H
    val bottomBase = prof.body.first().y * H * 0.85f
    val archR = wheelR * 1.13f
    fun bottomAt(p: Float): Float {
        var y = bottomBase
        for (ax in listOf(axF, axR)) {
            val d = abs(p - ax)
            if (d < archR) y = max(y, wheelR + sqrt(archR * archR - d * d) * 0.97f)
        }
        return y
    }
    val pillarB = g[1].x + (g[2].x - g[1].x) * (if (style.roof == Roof.SEDAN) 0.52f else 0.42f)
    val pillarC = if (style.roof == Roof.SUV) g[1].x + (g[2].x - g[1].x) * 0.8f else -1f

    // ---- 단면 (한쪽: k=0 아래 → k=7 지붕 중앙) ----
    val N = 64
    val K = 8
    val stations = (0..N).map { it / N.toFloat() }
    fun ring(p: Float): List<Pair<Float, Float>> {
        val taper = (0.84f + 0.16f * smooth01(p / 0.12f)) * (0.9f + 0.1f * smooth01((1f - p) / 0.1f))
        val w = halfW * taper
        val top = topAt(p)
        val yb = min(bottomAt(p), top - 0.05f * H)
        val inCabin = p > g[0].x - 0.01f && p < g[3].x + 0.01f
        val shoulder = min(if (inCabin) beltAt(p) else beltBase, top - 0.012f * H)
        val gh = if (inCabin) ((top - shoulder) / (0.12f * H)).coerceIn(0f, 1f) else 0f
        fun mix(a: Float, b: Float) = a + (b - a) * gh
        return listOf(
            0.84f * w to yb,
            0.97f * w to yb + 0.3f * (shoulder - yb),
            1.0f * w to yb + 0.66f * (shoulder - yb),
            0.975f * w to shoulder,
            mix(0.86f, 0.9f) * w to mix(top, shoulder + 0.015f * H),
            mix(0.6f, 0.74f) * w to mix(top + 0.006f * H, top - 0.06f * H),
            mix(0.32f, 0.6f) * w to mix(top + 0.011f * H, top - 0.008f * H),
            0f to top + 0.013f * H,
        )
    }
    // 전체 링: 왼쪽 k0..k6, 중앙 k7, 오른쪽 k6..k0 (z: 왼쪽 음수)
    val grid = stations.map { p ->
        val r = ring(p)
        val x = p - 0.5f
        (0 until K).map { k -> V3(x, r[k].second, -r[k].first) } + (K - 2 downTo 0).map { k -> V3(x, r[k].second, r[k].first) }
    }
    val J = grid[0].size // 15
    fun at(i: Int, j: Int) = grid[i.coerceIn(0, N)][((j % J) + J) % J]
    // 정점 법선 (중앙 차분, 바깥쪽으로)
    val normals = (0..N).map { i ->
        (0 until J).map { j ->
            val du = at(i + 1, j) - at(i - 1, j)
            val dv = at(i, j + 1) - at(i, j - 1)
            var n = du.cross(dv).norm()
            val p = at(i, j)
            val center = V3(p.x, (grid[i][K - 1].y + grid[i][0].y) / 2f, 0f)
            if (n.dot(p - center) < 0) n = n * -1f
            n
        }
    }

    fun segMat(i: Int, j: Int): Mat {
        if (j == J - 1) return Mat.UNDER
        val k = if (j < K - 1) j else 2 * (K - 1) - j - 1
        val p = (stations[i] + stations[min(i + 1, N)]) / 2f
        val inCabin = p > g[0].x && p < g[3].x
        val windshield = p < g[1].x + 0.01f
        val rearGlass = p > g[2].x - 0.01f
        val top = topAt(p)
        val ghOk = inCabin && top - beltAt(p) > 0.09f * H
        return when (k) {
            0 -> Mat.LOWER
            4 -> when {
                !ghOk -> Mat.PAINT
                abs(p - pillarB) < 0.014f || abs(p - pillarC) < 0.011f -> Mat.TRIM
                else -> Mat.GLASS
            }
            5 -> if (ghOk && (windshield || rearGlass)) Mat.GLASS else if (style.twoToneRoof && ghOk) Mat.TRIM else Mat.PAINT
            6 -> if (ghOk && (windshield || rearGlass)) Mat.GLASS else if (style.twoToneRoof && ghOk) Mat.TRIM else Mat.PAINT
            else -> Mat.PAINT
        }
    }

    val tris = ArrayList<Tri>(N * J * 2 + 800)
    for (i in 0 until N) for (j in 0 until J) {
        val a = at(i, j); val b = at(i + 1, j); val c = at(i + 1, j + 1); val d = at(i, j + 1)
        val na = normals[i][j]; val nb = normals[i + 1][j]; val nc = normals[i + 1][(j + 1) % J]; val nd = normals[i][(j + 1) % J]
        val m = segMat(i, j)
        tris += Tri(a, b, c, na, nb, nc, m)
        tris += Tri(a, c, d, na, nc, nd, m)
    }
    // 앞·뒤 마감면 (부채꼴)
    for ((i, dir) in listOf(0 to -1f, N to 1f)) {
        val ringPts = grid[i]
        val cx = ringPts.map { it.y }.average().toFloat()
        val center = V3(ringPts[0].x + dir * 0.004f, cx, 0f)
        val n = V3(dir, 0f, 0f)
        for (j in 0 until J) {
            val a = ringPts[j]; val b = ringPts[(j + 1) % J]
            val na = (n * 0.7f + normals[i][j] * 0.3f).norm(); val nb = (n * 0.7f + normals[i][(j + 1) % J] * 0.3f).norm()
            tris += Tri(center, a, b, n, na, nb, Mat.PAINT)
        }
    }
    // 램프·그릴 (앞뒤 면에 얇게 붙임)
    fun quad(x: Float, y0: Float, y1: Float, z0: Float, z1: Float, m: Mat, dir: Float) {
        val n = V3(dir, 0f, 0f)
        val a = V3(x, y0, z0); val b = V3(x, y0, z1); val c = V3(x, y1, z1); val d = V3(x, y1, z0)
        tris += Tri(a, b, c, n, n, n, m); tris += Tri(a, c, d, n, n, n, m)
    }
    val fx = -0.5f - 0.006f
    val rx = 0.5f + 0.006f
    val headY = topAt(0.02f) - 0.1f * H
    val tailY = topAt(0.98f) - 0.12f * H
    val lw = halfW * 0.84f
    when (style.lamp) {
        LampStyle.HORIZON -> { quad(fx, headY, headY + 0.018f * H, -lw, lw, Mat.LAMP_F, -1f); quad(rx, tailY, tailY + 0.03f * H, -lw, lw, Mat.LAMP_R, 1f) }
        LampStyle.PIXEL -> for (s in listOf(-1f, 1f)) for (r in 0 until 2) for (c in 0 until 4) {
            val z0 = s * (lw - c * 0.03f); val y0 = headY - r * 0.035f * H
            quad(fx, y0, y0 + 0.025f * H, z0 - s * 0.022f, z0, Mat.LAMP_F, -1f)
            quad(rx, tailY - r * 0.035f * H, tailY - r * 0.035f * H + 0.025f * H, z0 - s * 0.022f, z0, Mat.LAMP_R, 1f)
        }
        LampStyle.VERTICAL -> for (s in listOf(-1f, 1f)) {
            quad(fx, headY - 0.1f * H, headY + 0.03f * H, s * lw, s * (lw - 0.02f), Mat.LAMP_F, -1f)
            quad(rx, tailY - 0.12f * H, tailY + 0.03f * H, s * lw, s * (lw - 0.02f), Mat.LAMP_R, 1f)
        }
        LampStyle.TWO_LINE -> for (s in listOf(-1f, 1f)) for (r in 0 until 2) {
            val y0 = headY - r * 0.045f * H
            quad(fx, y0, y0 + 0.014f * H, s * lw, s * (lw - 0.16f), Mat.LAMP_F, -1f)
            quad(rx, tailY - r * 0.045f * H, tailY - r * 0.045f * H + 0.014f * H, s * lw, s * (lw - 0.16f), Mat.LAMP_R, 1f)
        }
        LampStyle.ROUND -> for (s in listOf(-1f, 1f)) {
            quad(fx, headY - 0.06f * H, headY + 0.03f * H, s * (lw - 0.02f), s * (lw - 0.075f), Mat.LAMP_F, -1f)
            quad(rx, tailY - 0.06f * H, tailY + 0.03f * H, s * lw, s * (lw - 0.035f), Mat.LAMP_R, 1f)
        }
    }
    val grilleTop = bottomBase + 0.2f * H
    quad(fx + 0.002f, bottomBase + 0.05f * H, grilleTop, -halfW * 0.6f, halfW * 0.6f, Mat.GRILLE, -1f)

    // 사이드미러
    for (s in listOf(-1f, 1f)) {
        val mx = g[0].x - 0.5f + 0.025f; val my = beltBase + 0.04f * H; val mz = s * (halfW * 0.96f + 0.016f)
        box(tris, V3(mx, my, mz), V3(0.016f, 0.022f * H, 0.018f), Mat.PAINT)
    }
    // 휠
    val tw = 0.052f
    for (ax in listOf(axF, axR)) for (s in listOf(-1f, 1f)) {
        wheel(tris, V3(ax - 0.5f, wheelR, s * (halfW * 0.98f - tw / 2)), wheelR, tw, s, sporty = style.spoiler || style.caliper != null, caliper = style.caliper != null)
    }
    return CarMesh(tris, style.paint, halfW, H, style.caliper)
}

private fun box(out: MutableList<Tri>, c: V3, h: V3, m: Mat) {
    val faces = listOf(
        V3(1f, 0f, 0f), V3(-1f, 0f, 0f), V3(0f, 1f, 0f), V3(0f, -1f, 0f), V3(0f, 0f, 1f), V3(0f, 0f, -1f),
    )
    for (n in faces) {
        val u = if (abs(n.y) > 0.5f) V3(1f, 0f, 0f) else V3(0f, 1f, 0f)
        val v = n.cross(u)
        fun p(a: Float, b: Float) = V3(c.x + (n.x + u.x * a + v.x * b) * h.x, c.y + (n.y + u.y * a + v.y * b) * h.y, c.z + (n.z + u.z * a + v.z * b) * h.z)
        val a = p(-1f, -1f); val b = p(1f, -1f); val cc = p(1f, 1f); val d = p(-1f, 1f)
        out += Tri(a, b, cc, n, n, n, m); out += Tri(a, cc, d, n, n, n, m)
    }
}

private fun wheel(out: MutableList<Tri>, c: V3, r: Float, tw: Float, side: Float, sporty: Boolean, caliper: Boolean) {
    val seg = 32
    val zo = c.z + side * tw / 2; val zi = c.z - side * tw / 2
    fun pt(rad: Float, a: Double, z: Float) = V3(c.x + rad * cos(a).toFloat(), c.y + rad * sin(a).toFloat(), z)
    val sideN = V3(0f, 0f, side)
    for (k in 0 until seg) {
        val a0 = 2 * Math.PI * k / seg; val a1 = 2 * Math.PI * (k + 1) / seg
        val n0 = V3(cos(a0).toFloat(), sin(a0).toFloat(), 0f); val n1 = V3(cos(a1).toFloat(), sin(a1).toFloat(), 0f)
        // 트레드
        val p0 = pt(r * 0.97f, a0, zo); val p1 = pt(r * 0.97f, a1, zo); val p2 = pt(r * 0.97f, a1, zi); val p3 = pt(r * 0.97f, a0, zi)
        out += Tri(p0, p1, p2, n0, n1, n1, Mat.TIRE); out += Tri(p0, p2, p3, n0, n1, n0, Mat.TIRE)
        // 바깥 사이드월 (볼록하게 법선 기울임)
        val rimR = r * 0.7f
        val s0 = pt(r * 0.97f, a0, zo); val s1 = pt(r * 0.97f, a1, zo); val s2 = pt(rimR, a1, zo + side * 0.004f); val s3 = pt(rimR, a0, zo + side * 0.004f)
        val ns0 = (sideN + n0 * 0.6f).norm(); val ns1 = (sideN + n1 * 0.6f).norm()
        out += Tri(s0, s1, s2, ns0, ns1, sideN, Mat.TIRE); out += Tri(s0, s2, s3, ns0, sideN, sideN, Mat.TIRE)
        // 림 안쪽(배럴) 어둡게
        val b0 = pt(rimR, a0, zo - side * 0.012f); val b1 = pt(rimR, a1, zo - side * 0.012f); val cc = V3(c.x, c.y, zo - side * 0.012f)
        out += Tri(cc, b0, b1, sideN, sideN, sideN, Mat.UNDER)
        // 림 테두리 (은색)
        val l0 = pt(rimR, a0, zo + side * 0.005f); val l1 = pt(rimR, a1, zo + side * 0.005f); val l2 = pt(rimR * 0.9f, a1, zo + side * 0.003f); val l3 = pt(rimR * 0.9f, a0, zo + side * 0.003f)
        val nl0 = (sideN - n0 * 0.5f).norm(); val nl1 = (sideN - n1 * 0.5f).norm()
        out += Tri(l0, l1, l2, nl0, nl1, nl1, Mat.RIM); out += Tri(l0, l2, l3, nl0, nl1, nl0, Mat.RIM)
    }
    val rimR = r * 0.7f
    // 브레이크 캘리퍼
    if (caliper) {
        val a0 = Math.toRadians(150.0); val a1 = Math.toRadians(200.0)
        val z = zo - side * 0.009f
        val p0 = pt(rimR * 0.82f, a0, z); val p1 = pt(rimR * 0.82f, a1, z); val p2 = pt(rimR * 0.55f, a1, z); val p3 = pt(rimR * 0.55f, a0, z)
        out += Tri(p0, p1, p2, sideN, sideN, sideN, Mat.CALIPER); out += Tri(p0, p2, p3, sideN, sideN, sideN, Mat.CALIPER)
    }
    // 스포크
    val spokes = if (sporty) 10 else 5
    val half = if (sporty) 0.05 else 0.11
    for (k in 0 until spokes) {
        val a = 2 * Math.PI * k / spokes + Math.PI / 2
        val z = zo + side * 0.002f
        val p0 = pt(rimR * 0.2f, a - half * 1.6, z); val p1 = pt(rimR * 0.2f, a + half * 1.6, z)
        val p2 = pt(rimR * 0.9f, a + half, z - side * 0.002f); val p3 = pt(rimR * 0.9f, a - half, z - side * 0.002f)
        val nn = (sideN + V3(cos(a).toFloat(), sin(a).toFloat(), 0f) * -0.25f).norm()
        out += Tri(p0, p1, p2, sideN, sideN, nn, Mat.RIM); out += Tri(p0, p2, p3, sideN, nn, nn, Mat.RIM)
    }
    // 허브
    for (k in 0 until 12) {
        val a0 = 2 * Math.PI * k / 12; val a1 = 2 * Math.PI * (k + 1) / 12
        val z = zo + side * 0.004f
        out += Tri(V3(c.x, c.y, z + side * 0.002f), pt(rimR * 0.2f, a0, z), pt(rimR * 0.2f, a1, z), sideN, sideN, sideN, Mat.RIM)
    }
}

// ---------------- 렌더링 ----------------

private fun lerpC(a: FloatArray, b: FloatArray, t: Float) = FloatArray(3) { a[it] + (b[it] - a[it]) * t.coerceIn(0f, 1f) }
private fun rgb(c: Color) = floatArrayOf(c.red, c.green, c.blue)
private val SKY_TOP = rgb(Color(0xFF7F93AD))
private val SKY_HOR = rgb(Color(0xFFE6ECF3))
private val GROUND_HOR = rgb(Color(0xFF5A5650))
private val GROUND = rgb(Color(0xFF151413))

/** 반사 방향의 '위쪽 성분'으로 환경색 (하늘 → 밝은 지평선 → 어두운 지면) */
private fun env(ry: Float): FloatArray = when {
    ry > 0.03f -> lerpC(SKY_HOR, SKY_TOP, (ry - 0.03f) / 0.7f)
    ry > -0.01f -> floatArrayOf(0.9f, 0.92f, 0.95f)
    else -> lerpC(GROUND_HOR, GROUND, (-ry - 0.01f) / 0.35f)
}

/**
 * 3D 차를 그린다. yawDeg: 0=측면(앞이 왼쪽), +35 ≈ 앞 3/4, 90=정면, 180=반대 측면.
 * centerX/groundY: 화면에서 차 중심·지면 위치, width: 측면에서 본 차 길이(px).
 */
fun DrawScope.drawCar3D(
    id: String, style: CarStyle, centerX: Float, groundY: Float, width: Float,
    yawDeg: Float = 35f, pitchDeg: Float = 12f, light: Color = Color.White, silhouette: Boolean = false,
) {
    val mesh = carMeshFor(id, style)
    val tris = mesh.tris
    val yaw = Math.toRadians(yawDeg.toDouble()); val pt = Math.toRadians(pitchDeg.toDouble())
    val cy = cos(yaw).toFloat(); val sy = sin(yaw).toFloat(); val cp = cos(pt).toFloat(); val sp = sin(pt).toFloat()
    val D = 3.4f
    val scale = width * 0.95f
    val midY = mesh.height * 0.45f
    fun view(v: V3): V3 { // 모델 → 카메라 공간 (지면 중앙 기준 회전, 차 높이 절반을 중심으로 기울임)
        val x = v.x * cy + v.z * sy
        val z = -v.x * sy + v.z * cy
        val y = v.y - midY
        return V3(x, y * cp - z * sp, z * cp + y * sp)
    }
    fun viewN(n: V3): V3 {
        val x = n.x * cy + n.z * sy
        val z = -n.x * sy + n.z * cy
        return V3(x, n.y * cp - z * sp, z * cp + n.y * sp)
    }
    val cam = V3(0f, 0f, D)
    val baseY = groundY - midY * cp * scale
    fun proj(v: V3): Offset { val s = D / (D - v.z); return Offset(centerX + v.x * s * scale, baseY - v.y * s * scale) }

    // 접지 그림자
    val shadow = Path().apply {
        val corners = listOf(V3(-0.56f, 0f, -mesh.halfWidth * 1.25f), V3(0.56f, 0f, -mesh.halfWidth * 1.25f), V3(0.56f, 0f, mesh.halfWidth * 1.25f), V3(-0.56f, 0f, mesh.halfWidth * 1.25f))
            .map { proj(view(it)) }
        moveTo(corners[0].x, corners[0].y); corners.drop(1).forEach { lineTo(it.x, it.y) }; close()
    }
    val sc = proj(view(V3(0f, 0f, 0f)))
    drawPath(shadow, Brush.radialGradient(listOf(Color.Black.copy(alpha = if (silhouette) 0.25f else 0.6f), Color.Transparent), center = sc, radius = scale * 0.62f))

    val lightDir = V3(-0.45f, 0.75f, 0.55f).norm()
    val up = V3(0f, cp, sp)
    val paint = rgb(mesh.paint)
    val lightTint = rgb(light)
    val silC = rgb(Color(0xFF2E3644))

    class Out(val depth: Float, val p: Array<Offset>, val col: IntArray)
    val visible = ArrayList<Out>(tris.size)
    for (t in tris) {
        val a = view(t.a); val b = view(t.b); val c = view(t.c)
        val na = viewN(t.na); val nb = viewN(t.nb); val nc = viewN(t.nc)
        var fn = (b - a).cross(c - a)
        if (fn.dot(na + nb + nc) < 0) fn = fn * -1f
        if (fn.dot(cam - a) <= 0f) continue
        val cols = IntArray(3)
        val vs = arrayOf(a, b, c); val ns = arrayOf(na, nb, nc)
        for (k in 0..2) {
            val col: FloatArray = if (silhouette) silC else {
                val n = ns[k]; val v = (cam - vs[k]).norm()
                val diff = max(0f, n.dot(lightDir))
                val ndv = max(0f, n.dot(v))
                val fres = (1f - ndv).pow(3)
                val r = (v * -1f) - n * (2f * n.dot(v * -1f))
                val e = env(r.dot(up))
                val h = (lightDir + v).norm()
                val spec = max(0f, n.dot(h)).pow(70) * 0.9f
                fun mixAdd(base: FloatArray, kd: Float, ka: Float, ke: Float, ks: Float) = FloatArray(3) { i ->
                    (base[i] * (ka + kd * diff) * (0.85f + 0.15f * lightTint[i]) + e[i] * ke + spec * ks).coerceIn(0f, 1f)
                }
                when (t.mat) {
                    Mat.PAINT -> mixAdd(paint, 0.6f, 0.16f, 0.16f + 0.5f * fres, 1f)
                    Mat.LOWER -> mixAdd(rgb(Color(0xFF1B1D20)), 0.5f, 0.2f, 0.06f + 0.2f * fres, 0.2f)
                    Mat.GLASS -> mixAdd(rgb(Color(0xFF0D1218)), 0.4f, 0.4f, 0.28f + 0.55f * fres, 1f)
                    Mat.TRIM -> mixAdd(rgb(Color(0xFF0F1012)), 0.4f, 0.4f, 0.08f + 0.3f * fres, 0.6f)
                    Mat.UNDER -> rgb(Color(0xFF08090A))
                    Mat.TIRE -> mixAdd(rgb(Color(0xFF1E1F22)), 0.6f, 0.25f, 0.03f, 0.08f)
                    Mat.RIM -> mixAdd(rgb(Color(0xFFB9C0C8)), 0.55f, 0.25f, 0.45f, 1f)
                    Mat.GRILLE -> mixAdd(rgb(Color(0xFF141619)), 0.4f, 0.3f, 0.12f, 0.3f)
                    Mat.CALIPER -> mixAdd(rgb(Color(0xFFE53935)), 0.6f, 0.3f, 0.1f, 0.5f)
                    Mat.LAMP_F -> rgb(Color(0xFFFFF8E1))
                    Mat.LAMP_R -> rgb(Color(0xFFE5262E))
                }
            }
            cols[k] = Color(col[0], col[1], col[2]).toArgb()
        }
        visible += Out((a.z + b.z + c.z) / 3f, arrayOf(proj(a), proj(b), proj(c)), cols)
    }
    visible.sortBy { it.depth }
    val verts = FloatArray(visible.size * 6)
    val colors = IntArray(visible.size * 3)
    visible.forEachIndexed { i, o ->
        for (k in 0..2) { verts[i * 6 + k * 2] = o.p[k].x; verts[i * 6 + k * 2 + 1] = o.p[k].y; colors[i * 3 + k] = o.col[k] }
    }
    drawContext.canvas.nativeCanvas.drawVertices(
        android.graphics.Canvas.VertexMode.TRIANGLES, verts.size, verts, 0, null, 0, colors, 0, null, 0, 0,
        android.graphics.Paint(),
    )
}
