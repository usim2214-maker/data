package ai.parkingguide.core

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 주차 리포트 카드(코칭)와 주차선 상태 리포트(지자체 공유용).
 * 개인정보: 영상, VIN, 정확한 위치는 보내지 않는다. 위치는 약 100m 단위로 반올림한다.
 */
data class LineCondition(val coverageLeft: Double, val coverageRight: Double, val confidence: Double, val samples: Int) {
    val worstCoverage get() = minOf(coverageLeft, coverageRight)
    val needsRepaint get() = worstCoverage < 0.6
    val label get() = when {
        worstCoverage >= 0.8 -> "양호"
        worstCoverage >= 0.6 -> "약간 닳음"
        else -> "재도색 권장"
    }
}

data class ParkingReport(
    val score: Int,
    val grade: String,
    val offsetCm: Double,
    val yawDeg: Double,
    val clearDriverCm: Double,
    val clearPassengerCm: Double,
    val durationS: Double,
    val corrections: Int,
    val retries: Int,
    val lowConfRatio: Double,
    val tips: List<String>,
    val line: LineCondition?,
    val targetBiasCm: Double = 0.0,
    val trend: String = "",
)

fun scoreParking(offsetCm: Double, yawDeg: Double, minClearCm: Double, corrections: Int, retries: Int): Int {
    var s = 100.0
    s -= minOf(40.0, 2.0 * abs(offsetCm))
    s -= minOf(30.0, 6.0 * abs(yawDeg))
    s -= when {
        minClearCm < 0 -> 25.0
        minClearCm < 5 -> 10.0
        else -> 0.0
    }
    s -= minOf(15.0, 2.0 * maxOf(0, corrections - 4))
    s -= 5.0 * retries
    return s.coerceIn(0.0, 100.0).roundToInt()
}

fun grade(score: Int) = when {
    score >= 90 -> "완벽해요"
    score >= 75 -> "잘했어요"
    score >= 55 -> "괜찮아요"
    else -> "연습해 봐요"
}

/** 가장 도움이 될 조언부터. 화면에는 첫 번째만 크게 보여 준다. */
fun makeTips(offsetCm: Double, yawDeg: Double, corrections: Int, retries: Int, lowConfRatio: Double, targetBiasCm: Double): List<String> {
    val tips = mutableListOf<String>()
    if (abs(yawDeg) >= 1.5) tips += "끝선 1m 전부터는 핸들을 똑바로 풀어 두면 차가 반듯하게 들어가요"
    if (abs(offsetCm) >= 8) {
        val side = if (offsetCm > 0) "오른쪽" else "왼쪽"
        tips += "${side}으로 치우쳤어요. 후진을 시작할 때 차 뒤를 주차칸 가운데에 더 가깝게 두세요"
    }
    if (corrections > 6) tips += "핸들을 자주 바꾸기보다 다이얼의 초록 구간에 맞춘 뒤 잠깐 유지해 보세요"
    if (retries > 0) tips += "무리하지 않고 다시 넣은 건 좋은 판단이에요"
    if (lowConfRatio > 0.3) tips += "이 주차장은 선이 희미해서 인식이 어려웠어요. 주변을 더 꼼꼼히 확인하세요"
    if (targetBiasCm != 0.0 && abs(offsetCm) < 8) tips += "문 열 공간까지 생각한 주차예요"
    if (tips.isEmpty()) tips += "반듯하고 가운데예요. 이 감각을 기억해 두세요"
    return tips
}

/** 한 번의 후진 주차를 기록한다. 매 프레임 add()를 부르고 끝나면 finish(). */
class ParkingSession {
    private var t0: Double? = null
    private var tLast = 0.0
    private val steerHist = mutableListOf<Double>()
    private var retries = 0
    private var frames = 0
    private var lowConfFrames = 0
    private val covL = mutableListOf<Double>()
    private val covR = mutableListOf<Double>()
    private val confs = mutableListOf<Double>()
    private var lastAlign: Alignment? = null
    private var lastKey: String? = null

    /** [measured]: 이번 프레임에 실제로 검출된 주차칸 (추적 예측이 아닌 것). 선 상태 측정에 쓴다. */
    fun add(align: Alignment?, slot: SlotEstimate?, guidance: Guidance?, steerDeg: Double?, t: Double, measured: SlotEstimate? = null) {
        if (slot == null) return
        if (t0 == null) t0 = t
        tLast = t
        frames++
        steerDeg?.let { steerHist += it }
        if (slot.confidence < LOW_CONFIDENCE) lowConfFrames++
        if (guidance?.key == "retry" && lastKey != "retry") retries++
        lastKey = guidance?.key
        measured?.let { covL += it.left.coverage; covR += it.right.coverage; confs += it.confidence }
        align?.let { lastAlign = it }
    }

    /** 핸들을 돌리는 방향이 바뀐 횟수 (작은 떨림은 무시). */
    fun corrections(deadbandDeg: Double = 20.0): Int {
        if (steerHist.size < 3) return 0
        var n = 0
        var direction = 0
        var anchor = steerHist[0]
        for (w in steerHist.drop(1)) {
            val d = w - anchor
            if (abs(d) < deadbandDeg) continue
            val newDir = if (d > 0) 1 else -1
            if (direction != 0 && newDir != direction) n++
            direction = newDir
            anchor = w
        }
        return n
    }

    fun lineCondition(): LineCondition? =
        if (covL.isEmpty()) null else LineCondition(median(covL), median(covR), median(confs), covL.size)

    fun finish(): ParkingReport? {
        val a = lastAlign ?: return null
        val corr = corrections()
        val low = lowConfFrames.toDouble() / maxOf(1, frames)
        val score = scoreParking(a.offsetCm, a.yawDeg, minOf(a.clearLeftCm, a.clearRightCm), corr, retries)
        return ParkingReport(
            score, grade(score), round1(a.offsetCm), Math.round(a.yawDeg * 100) / 100.0,
            round1(a.clearLeftCm), round1(a.clearRightCm), round1(tLast - (t0 ?: tLast)), corr, retries,
            Math.round(low * 100) / 100.0, makeTips(a.offsetCm, a.yawDeg, corr, retries, low, a.targetBiasCm),
            lineCondition(), a.targetBiasCm,
        )
    }
}

fun trendText(scores: List<Int>): String {
    if (scores.size < 2) return "첫 기록이에요"
    if (scores.size < 6) {
        val best = scores.dropLast(1).max()
        return if (scores.last() > best) "최고 기록이에요 (+${scores.last() - best}점)" else "지금까지 ${scores.size}번 연습했어요"
    }
    val diff = (scores.takeLast(5).average() - scores.dropLast(5).takeLast(5).average()).roundToInt()
    return when {
        diff > 0 -> "최근 5번 평균이 그 전보다 ${diff}점 올랐어요"
        diff < 0 -> "최근 5번 평균이 ${-diff}점 내려갔어요. 천천히 해 봐요"
        else -> "실력이 꾸준해요"
    }
}

/**
 * 지자체 공유용 익명 데이터(JSON). 영상·차량 식별정보 없이, 위치는 소수 셋째 자리(약 100m)로 반올림한다.
 * 위치는 Pleos FusedLocation에서 받는다. 운전자 동의를 받은 경우에만 보낸다.
 */
fun municipalPayload(r: ParkingReport, lat: Double?, lon: Double?, slotHeadingDeg: Double? = null): String {
    val loc = if (lat == null || lon == null) "null" else
        "{\"lat\":${round3(lat)},\"lon\":${round3(lon)}}"
    val heading = slotHeadingDeg?.let { ((it / 10).roundToInt() * 10).toString() } ?: "null"
    val line = r.line?.let {
        "{\"coverage_min\":${round2(it.worstCoverage)},\"confidence\":${round2(it.confidence)}," +
            "\"status\":\"${it.label}\",\"needs_repaint\":${it.needsRepaint}}"
    } ?: "null"
    return "{\"schema\":\"parking-line-condition/v1\",\"location\":$loc,\"slot_heading_deg\":$heading," +
        "\"line\":$line,\"difficulty\":{\"retries\":${r.retries},\"duration_s\":${r.durationS},\"corrections\":${r.corrections}}}"
}

private fun round1(v: Double) = Math.round(v * 10) / 10.0
private fun round2(v: Double) = Math.round(v * 100) / 100.0
private fun round3(v: Double) = Math.round(v * 1000) / 1000.0
