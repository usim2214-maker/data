package com.signalassist.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

data class SirenConfig(
    val sampleRate: Int = 16_000,
    /** 분석 단위 (16kHz 에서 64ms) */
    val blockSize: Int = 1024,
    val minFreqHz: Float = 450f,
    val maxFreqHz: Float = 1_800f,
    val freqStepHz: Float = 12f,
    /** 판단에 쓰는 최근 구간 길이 */
    val windowMs: Int = 2_500,
    /** 한 블록이 '순음에 가깝다'고 볼 최소 비율 (대역 내 최대 성분 / 전체 에너지) */
    val minTonality: Float = 0.15f,
    /** 구간 중 순음 블록 비율 */
    val minTonalFraction: Float = 0.7f,
    /** 음높이가 오르내리는 폭 */
    val minSweepRangeHz: Float = 250f,
    /** 연속된 블록 사이 음높이가 '부드럽게 움직인' 비율 (멜로디는 음 사이에서만 뛰고 나머지는 평평함) */
    val minMovingFraction: Float = 0.45f,
    val minRms: Float = 60f,
    /** 연속 블록 사이 이 범위의 음높이 변화를 '부드러운 움직임'으로 본다 */
    val maxStepHz: Float = 400f,
) {
    companion object {
        /** 느린 오르내림음 (wail): 64ms 블록, 정밀한 음높이 */
        val WAIL = SirenConfig()
        /** 빠른 yelp: 16ms 블록 (음높이가 빨리 변해 긴 블록에서는 번짐) */
        val YELP = SirenConfig(blockSize = 256, freqStepHz = 30f, minTonality = 0.12f, maxStepHz = 450f)
    }
}

/**
 * 긴급차량 사이렌 감지 (청각장애 운전자용).
 *
 * 사이렌(국내 표준 오르내림음 wail, 빠른 yelp)은 '한 음높이의 순음이 연속으로 오르내리는' 소리다.
 * - 도로·엔진 소음: 순음 성분이 약함 -> 탈락
 * - 경적·일정한 경고음: 음높이가 거의 그대로 -> 탈락
 * - 음악 멜로디: 음과 음 사이에서만 뛰고 대부분 평평함 -> 탈락
 * - 말소리: 순음 비율이 낮고 끊김이 많음 -> 탈락
 *
 * 차량 실내 마이크 소리를 기기 안에서만 분석하고 저장·전송하지 않는다.
 */
class SirenDetector(configs: List<SirenConfig> = listOf(SirenConfig.WAIL, SirenConfig.YELP)) {
    private val analyzers = configs.map { SweepAnalyzer(it) }

    var detected = false
        private set

    fun reset() { analyzers.forEach { it.reset() }; detected = false }

    /** PCM 16bit mono 샘플을 넣는다. 현재 감지 여부를 돌려준다 */
    fun feed(samples: ShortArray, length: Int = samples.size): Boolean {
        detected = analyzers.map { it.feed(samples, length) }.any { it }
        return detected
    }
}

/** 한 시간 해상도에서 '순음이 부드럽게 오르내리는가'를 판단 */
private class SweepAnalyzer(private val config: SirenConfig) {

    private data class Block(val freq: Float, val tonal: Boolean)

    private val freqs: FloatArray
    private val coeffs: DoubleArray
    private val history = ArrayDeque<Block>()
    private val maxBlocks = config.windowMs * config.sampleRate / 1000 / config.blockSize
    private val pending = ShortArray(config.blockSize)
    private var pendingCount = 0
    private val filtered = DoubleArray(config.blockSize)

    // 400Hz 2차 고역통과 필터 (RBJ biquad): 엔진·도로 소음(저주파)을 걸러낸 뒤 분석
    private val hp: DoubleArray
    private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0

    var detected = false
        private set

    init {
        val n = ((config.maxFreqHz - config.minFreqHz) / config.freqStepHz).toInt() + 1
        freqs = FloatArray(n) { config.minFreqHz + it * config.freqStepHz }
        coeffs = DoubleArray(n) { 2 * cos(2 * PI * freqs[it] / config.sampleRate) }
        val w0 = 2 * PI * 400.0 / config.sampleRate
        val alpha = kotlin.math.sin(w0) / (2 * 0.7071)
        val cw = cos(w0)
        val a0 = 1 + alpha
        hp = doubleArrayOf((1 + cw) / 2 / a0, -(1 + cw) / a0, (1 + cw) / 2 / a0, -2 * cw / a0, (1 - alpha) / a0)
    }

    fun reset() { history.clear(); pendingCount = 0; detected = false; x1 = 0.0; x2 = 0.0; y1 = 0.0; y2 = 0.0 }

    /** PCM 16bit mono 샘플을 넣는다. 블록이 찰 때마다 판단을 갱신하고 현재 감지 여부를 돌려준다 */
    fun feed(samples: ShortArray, length: Int = samples.size): Boolean {
        var i = 0
        while (i < length) {
            val take = minOf(config.blockSize - pendingCount, length - i)
            System.arraycopy(samples, i, pending, pendingCount, take)
            pendingCount += take
            i += take
            if (pendingCount == config.blockSize) {
                analyzeBlock(pending)
                pendingCount = 0
            }
        }
        return detected
    }

    private fun analyzeBlock(raw: ShortArray) {
        val n = raw.size
        val x = filtered
        for (i in 0 until n) {
            val xi = raw[i].toDouble()
            val yi = hp[0] * xi + hp[1] * x1 + hp[2] * x2 - hp[3] * y1 - hp[4] * y2
            x2 = x1; x1 = xi; y2 = y1; y1 = yi
            x[i] = yi
        }
        var energy = 0.0
        for (s in x) energy += s * s
        val rms = sqrt(energy / n)
        var bestPower = 0.0
        var bestIdx = 0
        if (rms >= config.minRms) {
            for (k in freqs.indices) {
                val c = coeffs[k]
                var s1 = 0.0
                var s2 = 0.0
                for (s in x) {
                    val s0 = s + c * s1 - s2
                    s2 = s1; s1 = s0
                }
                val power = s1 * s1 + s2 * s2 - c * s1 * s2
                if (power > bestPower) { bestPower = power; bestIdx = k }
            }
        }
        // 순음 진폭 A 일 때 power ≈ (A·N/2)², energy ≈ A²·N/2  ->  비율 ≈ 1
        val tonality = if (energy > 0) (bestPower / (n / 2.0 * energy)).toFloat() else 0f
        history.addLast(Block(freqs[bestIdx], rms >= config.minRms && tonality >= config.minTonality))
        while (history.size > maxBlocks) history.removeFirst()
        detected = decide()
    }

    private fun decide(): Boolean {
        if (history.size < maxBlocks) return false
        val tonal = history.filter { it.tonal }
        if (tonal.size < history.size * config.minTonalFraction) return false
        val fs = tonal.map { it.freq }
        if (fs.max() - fs.min() < config.minSweepRangeHz) return false
        var moving = 0
        var pairs = 0
        val list = history.toList()
        for (k in 1 until list.size) {
            if (!list[k].tonal || !list[k - 1].tonal) continue
            pairs++
            val df = abs(list[k].freq - list[k - 1].freq)
            if (df >= 5f && df <= config.maxStepHz) moving++
        }
        return pairs > 0 && moving >= pairs * config.minMovingFraction
    }
}
