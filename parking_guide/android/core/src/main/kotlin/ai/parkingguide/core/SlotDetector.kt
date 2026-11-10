package ai.parkingguide.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin

data class DetectorConfig(
    val lineWidthMinCm: Double = 6.0,
    val lineWidthMaxCm: Double = 40.0,
    val slotWidthMinCm: Double = 200.0,
    val slotWidthMaxCm: Double = 340.0,
    val maxLineAngleDeg: Double = 35.0,
    val minLineLengthCm: Double = 50.0,
    val minEndLineLengthCm: Double = 60.0,
    val tophatKernelCm: Double = 60.0,
    val minContrast: Double = 25.0,
    val openKernelCm: Double = 22.0, // 끝선 굵기(15cm)보다 크고, 닳아 짧아진 선 조각보다는 작게
    val ignoreBottomRatio: Double = 0.06, // 범퍼가 보이는 영상 하단 비율
    val mergeDxCm: Double = 15.0,
    val yRefCm: Double = 150.0,
)

class DetectionResult(val lines: List<LineEstimate>, val slot: SlotEstimate?)

/**
 * 주차선 검출 (Python 프로토타입 parking_guide/detector.py 와 같은 알고리즘).
 * 1. 렌즈 왜곡 + 원근 보정을 remap 한 번으로 → 조감도
 * 2. 방향별 Top-hat으로 좌우 주차선 / 끝선 마스크
 * 3. 허프 변환으로 한 직선 위의 페인트 조각을 묶고, 묶인 조각 화소 전체로 직선 피팅
 * 4. 평행한 모든 쌍 중 주차칸을 고르고, 끝선이 보이면 각도 보정 + 끝선 거리
 *
 * OpenCV 네이티브 라이브러리는 호출하는 쪽에서 미리 불러 둔다 (Android: OpenCVLoader.initLocal()).
 */
class SlotDetector(
    val camera: CameraModel,
    val vehicle: VehicleSpec = VehicleSpec.DEFAULT,
    val bev: BevConfig = BevConfig(),
    val cfg: DetectorConfig = DetectorConfig(),
) {
    var doorMode = DoorMode.CENTER
    private val s = bev.pxPerCm
    private val mapU = Mat(bev.heightPx, bev.widthPx, CvType.CV_32FC1)
    private val mapV = Mat(bev.heightPx, bev.widthPx, CvType.CV_32FC1)
    val valid = Mat(bev.heightPx, bev.widthPx, CvType.CV_8UC1)

    init {
        val w = bev.widthPx
        val h = bev.heightPx
        val us = FloatArray(w * h)
        val vs = FloatArray(w * h)
        val ok = ByteArray(w * h)
        val vMax = camera.height * (1 - cfg.ignoreBottomRatio)
        for (r in 0 until h) for (cIdx in 0 until w) {
            val i = r * w + cIdx
            val uv = camera.projectGround(bev.colToX(cIdx.toDouble()), bev.rowToY(r.toDouble()))
            if (uv == null) { us[i] = -1f; vs[i] = -1f; continue }
            us[i] = uv.first.toFloat(); vs[i] = uv.second.toFloat()
            if (uv.first >= 0 && uv.first <= camera.width - 1 && uv.second >= 0 && uv.second <= vMax) ok[i] = 255.toByte()
        }
        mapU.put(0, 0, us); mapV.put(0, 0, vs)
        val inside = Mat(h, w, CvType.CV_8UC1).apply { put(0, 0, ok) }
        Imgproc.erode(inside, valid, Mat.ones(7, 7, CvType.CV_8UC1))
        inside.release()
    }

    // ------------------------------------------------------------------ 마스크
    fun toBevGray(img: Mat): Mat {
        val gray = Mat()
        when (img.channels()) {
            1 -> img.copyTo(gray)
            3 -> Imgproc.cvtColor(img, gray, Imgproc.COLOR_BGR2GRAY)
            else -> Imgproc.cvtColor(img, gray, Imgproc.COLOR_RGBA2GRAY)
        }
        val out = Mat()
        Imgproc.remap(gray, out, mapU, mapV, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0))
        gray.release()
        return out
    }

    fun directionalMask(gray: Mat, vertical: Boolean): Mat {
        val k = maxOf(3, (cfg.tophatKernelCm * s).toInt() or 1)
        // 세로 선을 찾을 땐 가로 커널로, 가로 선을 찾을 땐 세로 커널로 배경을 뺀다
        val kernel = if (vertical) Mat.ones(1, k, CvType.CV_8UC1) else Mat.ones(k, 1, CvType.CV_8UC1)
        val tophat = Mat()
        Imgproc.morphologyEx(gray, tophat, Imgproc.MORPH_TOPHAT, kernel)
        val inv = Mat()
        Core.bitwise_not(valid, inv)
        tophat.setTo(Scalar(0.0), inv)
        // 유효 영역 값만으로 Otsu 문턱값
        val tb = ByteArray(tophat.total().toInt()).also { tophat.get(0, 0, it) }
        val vb = ByteArray(valid.total().toInt()).also { valid.get(0, 0, it) }
        val vals = ByteArray(vb.count { it != 0.toByte() })
        var j = 0
        for (i in tb.indices) if (vb[i] != 0.toByte()) vals[j++] = tb[i]
        val otsu = if (vals.isEmpty()) 255.0 else {
            val m = Mat(1, vals.size, CvType.CV_8UC1).apply { put(0, 0, vals) }
            Imgproc.threshold(m, Mat(), 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU).also { m.release() }
        }
        val mask = Mat()
        Imgproc.threshold(tophat, mask, maxOf(otsu, cfg.minContrast), 255.0, Imgproc.THRESH_BINARY)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, Mat.ones(5, 5, CvType.CV_8UC1))
        val ok = maxOf(3, (cfg.openKernelCm * s).toInt())
        val openK = if (vertical) Mat.ones(ok, 1, CvType.CV_8UC1) else Mat.ones(1, ok, CvType.CV_8UC1)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, openK)
        tophat.release(); inv.release()
        return mask
    }

    // ------------------------------------------------------------------ 피팅 도구
    private class Pix(val cols: IntArray, val rows: IntArray)

    private class Centers(val keys: IntArray, val centers: DoubleArray, val counts: IntArray, val full: BooleanArray)

    /** primary 값(행 또는 열)마다 secondary의 평균과 화소 수. 선 폭이 온전한 것만 full. */
    private fun rowCenters(primary: IntArray, secondary: IntArray): Centers {
        val sum = HashMap<Int, Long>()
        val cnt = HashMap<Int, Int>()
        for (i in primary.indices) {
            sum[primary[i]] = (sum[primary[i]] ?: 0L) + secondary[i]
            cnt[primary[i]] = (cnt[primary[i]] ?: 0) + 1
        }
        val keys = cnt.keys.sorted().toIntArray()
        val counts = IntArray(keys.size) { cnt.getValue(keys[it]) }
        val centers = DoubleArray(keys.size) { sum.getValue(keys[it]).toDouble() / counts[it] }
        val med = median(counts.map { it.toDouble() })
        var full = BooleanArray(keys.size) { counts[it] >= 0.7 * med }
        if (full.count { it } < 8) full = BooleanArray(keys.size) { true }
        return Centers(keys, centers, counts, full)
    }

    /** y = slope·x + intercept 최소제곱. */
    private fun polyfit(xs: DoubleArray, ys: DoubleArray): Pair<Double, Double> {
        val n = xs.size
        val mx = xs.average()
        val my = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) { sxy += (xs[i] - mx) * (ys[i] - my); sxx += (xs[i] - mx) * (xs[i] - mx) }
        val slope = if (sxx > 0) sxy / sxx else 0.0
        return slope to (my - slope * mx)
    }

    private fun fit(p: Pix): LineEstimate? {
        if (p.cols.size < 20) return null
        val c = rowCenters(p.rows, p.cols)
        if (c.keys.size < 10) return null
        val idx = c.keys.indices.filter { c.full[it] }
        val gx = DoubleArray(idx.size) { bev.colToX(c.centers[idx[it]]) }
        val gy = DoubleArray(idx.size) { bev.rowToY(c.keys[idx[it]].toDouble()) }
        if (gy.max() - gy.min() < 1.0) return null
        val (b, a) = polyfit(gy, gx)
        // 먼 곳은 원근 때문에 선이 번지므로 가까운 구간(아래쪽 행)으로 폭을 추정
        val fullCounts = idx.map { c.counts[it].toDouble() }
        val near = fullCounts.takeLast(maxOf(8, idx.size / 3))
        val width = median(near) / s * cos(atan(b))
        val yMin = bev.rowToY(p.rows.max().toDouble())
        val yMax = bev.rowToY(p.rows.min().toDouble())
        val spanRows = (yMax - yMin) * s + 1
        val coverage = (c.keys.size / spanRows).coerceIn(0.0, 1.0)
        return LineEstimate(a, b, width, yMin, yMax, idx.size, coverage)
    }

    private fun fitEnd(p: Pix): EndLine? {
        if (p.cols.size < 20) return null
        val c = rowCenters(p.cols, p.rows)
        val idx = c.keys.indices.filter { c.full[it] }
        if (idx.size < 10) return null
        val gx = DoubleArray(idx.size) { bev.colToX(c.keys[idx[it]].toDouble()) }
        val gy = DoubleArray(idx.size) { bev.rowToY(c.centers[idx[it]]) }
        if (gx.max() - gx.min() < 1.0) return null
        val (d, cc) = polyfit(gx, gy)
        val width = median(idx.map { c.counts[it].toDouble() }) / s * cos(atan(d))
        return EndLine(cc, d, width, gx.min(), gx.max())
    }

    private fun sameLine(long: LineEstimate, short: LineEstimate, tolCm: Double) =
        listOf(short.yMin, short.yMax).all { abs(long.xAt(it) - short.xAt(it)) < tolCm }

    /** 허프 변환을 절반 크기 마스크에서 돌린다 (조각을 묶는 용도라 정밀도는 충분). rho는 원래 크기 기준. */
    private fun hough(mask: Mat, votes: Int, minTheta: Double = 0.0, maxTheta: Double = PI): List<Pair<Double, Double>> {
        val small = Mat()
        Imgproc.resize(mask, small, Size(mask.cols() / 2.0, mask.rows() / 2.0), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.threshold(small, small, 60.0, 255.0, Imgproc.THRESH_BINARY)
        val lines = Mat()
        Imgproc.HoughLines(small, lines, 1.0, rad(0.5), maxOf(10, votes / 2), 0.0, 0.0, minTheta, maxTheta)
        val out = (0 until lines.rows()).map { r ->
            val v = lines.get(r, 0)
            (v[0] * 2 + 0.5) to v[1]
        }
        small.release(); lines.release()
        return out
    }

    // ------------------------------------------------------------------ 좌우 주차선
    fun detectLines(mask: Mat): List<LineEstimate> {
        val labels = Mat()
        val stats = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, Mat(), 8, CvType.CV_32S)
        val w = mask.cols()
        val lab = IntArray(labels.total().toInt()).also { labels.get(0, 0, it) }
        val area = IntArray(n) { stats.get(it, Imgproc.CC_STAT_AREA)[0].toInt() }
        val minArea = 10 * cfg.lineWidthMinCm * s * s
        val validIds = (1 until n).filter { area[it] >= minArea }.toSet()
        // 조각별 화소 목록
        val pxCols = HashMap<Int, MutableList<Int>>()
        val pxRows = HashMap<Int, MutableList<Int>>()
        val allCols = ArrayList<Int>()
        val allRows = ArrayList<Int>()
        val allLab = ArrayList<Int>()
        for (i in lab.indices) {
            val id = lab[i]
            if (id == 0) continue
            val r = i / w
            val cIdx = i % w
            allCols += cIdx; allRows += r; allLab += id
            if (id in validIds) {
                pxCols.getOrPut(id) { mutableListOf() } += cIdx
                pxRows.getOrPut(id) { mutableListOf() } += r
            }
        }
        labels.release(); stats.release()

        fun fitGroup(group: Set<Int>): LineEstimate? {
            val cols = group.flatMap { pxCols.getValue(it) }.toIntArray()
            val rows = group.flatMap { pxRows.getValue(it) }.toIntArray()
            val line = fit(Pix(cols, rows)) ?: return null
            return if (abs(line.angleDeg) > cfg.maxLineAngleDeg) null else line
        }

        val groups = ArrayList<Pair<Set<Int>, LineEstimate>>()
        val claimed = HashSet<Int>()
        val maxT = rad(cfg.maxLineAngleDeg)
        for ((rho, theta) in hough(mask, (60 * s).toInt()).take(40)) {
            if (!(theta < maxT || theta > PI - maxT)) continue // 세로에 가까운 선만
            val ct = cos(theta)
            val st = sin(theta)
            val inBand = HashMap<Int, Int>()
            for (i in allCols.indices) {
                if (abs(allCols[i] * ct + allRows[i] * st - rho) < 12 * s) inBand[allLab[i]] = (inBand[allLab[i]] ?: 0) + 1
            }
            // 조각의 절반 이상이 띠 안에 있으면 이 직선 위의 조각으로 본다
            val group = inBand.filter { (id, cnt) -> id in validIds && id !in claimed && cnt >= 0.5 * area[id] }.keys
            if (group.isEmpty()) continue
            val line = fitGroup(group) ?: continue
            if (line.lengthCm < cfg.minLineLengthCm) continue
            groups += group to line
            claimed += group
        }
        for (id in (validIds - claimed).sortedByDescending { area[it] }) {
            fitGroup(setOf(id))?.let { groups += setOf(id) to it }
        }
        // 같은 선 위의 후보 합치기 (긴 것부터)
        groups.sortByDescending { it.second.lengthCm }
        val merged = ArrayList<Pair<Set<Int>, LineEstimate>>()
        for ((group, line) in groups) {
            var done = false
            for (k in merged.indices) {
                val (bg, base) = merged[k]
                if (sameLine(base, line, cfg.mergeDxCm)) {
                    val refit = fitGroup(bg + group)
                    if (refit != null) { merged[k] = (bg + group) to refit; done = true; break }
                }
            }
            if (!done) merged += group to line
        }
        return merged.map { it.second }
            .filter { it.lengthCm >= cfg.minLineLengthCm && it.widthCm in cfg.lineWidthMinCm..(cfg.lineWidthMaxCm * 1.5) }
            .map { it.copy(widthCm = it.widthCm.coerceIn(cfg.lineWidthMinCm, cfg.lineWidthMaxCm)) }
    }

    // ------------------------------------------------------------------ 끝선
    private fun maskPixels(mask: Mat): Pix {
        val b = ByteArray(mask.total().toInt()).also { mask.get(0, 0, it) }
        val w = mask.cols()
        val cols = ArrayList<Int>()
        val rows = ArrayList<Int>()
        for (i in b.indices) if (b[i] != 0.toByte()) { cols += i % w; rows += i / w }
        return Pix(cols.toIntArray(), rows.toIntArray())
    }

    private fun componentPixels(mask: Mat, minArea: Double): List<Pix> {
        val labels = Mat()
        val stats = Mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, Mat(), 8, CvType.CV_32S)
        val lab = IntArray(labels.total().toInt()).also { labels.get(0, 0, it) }
        val w = mask.cols()
        val keep = (1 until n).filter { stats.get(it, Imgproc.CC_STAT_AREA)[0] >= minArea }.toSet()
        val cols = HashMap<Int, MutableList<Int>>()
        val rows = HashMap<Int, MutableList<Int>>()
        for (i in lab.indices) {
            val id = lab[i]
            if (id in keep) { cols.getOrPut(id) { mutableListOf() } += i % w; rows.getOrPut(id) { mutableListOf() } += i / w }
        }
        labels.release(); stats.release()
        return keep.map { Pix(cols.getValue(it).toIntArray(), rows.getValue(it).toIntArray()) }
    }

    private fun houghEndLines(mask: Mat): List<EndLine> {
        val maxT = rad(cfg.maxLineAngleDeg)
        val hl = hough(mask, (50 * s).toInt(), PI / 2 - maxT, PI / 2 + maxT)
        if (hl.isEmpty()) return emptyList()
        val all = maskPixels(mask)
        val used = ArrayList<Double>()
        val out = ArrayList<EndLine>()
        for ((rho, theta) in hl.take(20)) {
            if (abs(theta - PI / 2) > maxT) continue
            if (used.any { abs(rho - it) < 30 * s }) continue
            used += rho
            val ct = cos(theta)
            val st = sin(theta)
            val sel = all.cols.indices.filter { abs(all.cols[it] * ct + all.rows[it] * st - rho) < 22 * s }
            fitEnd(Pix(IntArray(sel.size) { all.cols[sel[it]] }, IntArray(sel.size) { all.rows[sel[it]] }))?.let { out += it }
        }
        return out
    }

    fun detectEndLine(mask: Mat, slot: SlotEstimate): EndLine? {
        val minArea = cfg.minEndLineLengthCm * 0.5 * cfg.lineWidthMinCm * s * s
        val cands = componentPixels(mask, minArea).mapNotNull { fitEnd(it) } + houghEndLines(mask)
        var best: EndLine? = null
        var bestErr = Double.MAX_VALUE
        for (end in cands) {
            if (end.lengthCm < cfg.minEndLineLengthCm) continue
            if (end.widthCm !in cfg.lineWidthMinCm..(cfg.lineWidthMaxCm * 1.5)) continue
            if (abs(deg(atan(end.d)) + slot.yawDeg) > 8.0) continue // 좌우 선과 거의 수직
            val xc = slot.centerXAt(end.yAt(0.0))
            if (xc < end.xMin - 40 || xc > end.xMax + 40) continue
            val err = abs(end.yAt(xc) - slot.farEndY) // 좌우 선이 끝나는 곳 근처
            if (err < 80 && err < bestErr) { best = end; bestErr = err }
        }
        return best?.copy(widthCm = best.widthCm.coerceIn(cfg.lineWidthMinCm, cfg.lineWidthMaxCm))
    }

    // ------------------------------------------------------------------ 주차칸 선택
    fun selectSlot(lines: List<LineEstimate>): SlotEstimate? {
        val sorted = lines.sortedBy { it.xAt(cfg.yRefCm) }
        var best: SlotEstimate? = null
        var bestScore = Double.MAX_VALUE
        for (i in sorted.indices) for (j in i + 1 until sorted.size) {
            val left = sorted[i]
            val right = sorted[j]
            if (abs(left.angleDeg - right.angleDeg) > 4.0) continue
            val slot = SlotEstimate(left, right)
            if (slot.widthCm !in cfg.slotWidthMinCm..cfg.slotWidthMaxCm) continue
            // 사이에 평행한 긴 선이 있으면 두 칸에 걸친 쌍이다
            if (sorted.subList(i + 1, j).any { abs(it.angleDeg - left.angleDeg) < 3.0 && it.lengthCm > 100 }) continue
            val score = abs(slot.centerXAt(0.0)) - 0.05 * minOf(left.lengthCm, right.lengthCm) -
                20.0 * minOf(left.coverage, right.coverage)
            if (score < bestScore) { best = slot; bestScore = score }
        }
        return best?.let { makeParallel(it.copy(confidence = slotConfidence(it))) }
    }

    /** 좌우 선은 평행하고 끝선과 수직이므로 기울기를 보이는 길이로 가중 평균해 공유시킨다. */
    fun makeParallel(slot: SlotEstimate, end: EndLine? = null): SlotEstimate {
        val l = slot.left
        val r = slot.right
        var num = l.b * l.lengthCm + r.b * r.lengthCm
        var den = l.lengthCm + r.lengthCm
        if (end != null) { num += -end.d * end.lengthCm; den += end.lengthCm }
        val b = num / den
        fun rebased(line: LineEstimate): LineEstimate {
            val yc = 0.5 * (line.yMin + line.yMax)
            return line.copy(a = line.xAt(yc) - b * yc, b = b)
        }
        val newEnd = end?.let {
            val xm = 0.5 * (it.xMin + it.xMax)
            EndLine(it.yAt(xm) + b * xm, -b, it.widthCm, it.xMin, it.xMax)
        }
        return slot.copy(left = rebased(l), right = rebased(r), end = newEnd)
    }

    fun analyze(img: Mat): DetectionResult {
        val gray = toBevGray(img)
        val mask = directionalMask(gray, vertical = true)
        val endMask = directionalMask(gray, vertical = false)
        // 멀리 있는 끝선은 원근 때문에 세로로 두꺼워져 세로선 마스크에 남을 수 있으므로 직접 뺀다
        val dil = Mat()
        Imgproc.dilate(endMask, dil, Mat.ones(5, 5, CvType.CV_8UC1))
        mask.setTo(Scalar(0.0), dil)
        val lines = detectLines(mask)
        var slot = selectSlot(lines)
        if (slot != null) {
            val end = detectEndLine(endMask, slot)
            if (end != null) {
                slot = makeParallel(slot.copy(end = null), end)
                slot = slot.copy(confidence = minOf(1.0, slot.confidence + 0.1)) // 끝선까지 맞으면 더 믿을 만함
            }
            slot = slot.copy(targetBiasCm = doorBiasCm(doorMode, slot, vehicle))
        }
        gray.release(); mask.release(); endMask.release(); dil.release()
        return DetectionResult(lines, slot)
    }
}

internal fun median(xs: List<Double>): Double {
    if (xs.isEmpty()) return 0.0
    val s = xs.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else 0.5 * (s[s.size / 2 - 1] + s[s.size / 2])
}
