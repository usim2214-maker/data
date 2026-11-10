package com.signalassist.core

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 합성 오디오로 사이렌 감지기를 검증한다 (16kHz, 16bit) */
class SirenDetectorTest {
    private val sr = 16_000

    private fun synth(seconds: Float, noiseAmp: Double = 0.0, seed: Int = 1, f: (t: Double) -> Pair<Double, Double>): ShortArray {
        // f(t) -> (주파수 Hz, 진폭). 위상을 누적해 주파수가 연속으로 변하게 한다
        val rnd = Random(seed)
        val n = (seconds * sr).toInt()
        var phase = 0.0
        var brown = 0.0
        return ShortArray(n) { i ->
            val t = i.toDouble() / sr
            val (freq, amp) = f(t)
            phase += 2 * PI * freq / sr
            brown = (brown + rnd.nextDouble(-1.0, 1.0) * 400) * 0.995 // 저주파 위주 도로 소음
            val v = amp * sin(phase) + noiseAmp * brown / 400.0 * 3000
            v.coerceIn(-32000.0, 32000.0).toInt().toShort()
        }
    }

    /** 처음 감지된 시각(초). 끝까지 없으면 null */
    private fun firstDetection(audio: ShortArray): Double? {
        val d = SirenDetector()
        val chunk = 320 // 20ms 씩 넣기
        var i = 0
        while (i < audio.size) {
            val len = minOf(chunk, audio.size - i)
            if (d.feed(audio.copyOfRange(i, i + len))) return i.toDouble() / sr
            i += len
        }
        return null
    }

    @Test
    fun `wail siren with road noise is detected within 3 seconds`() {
        // 4초 주기로 650~1450Hz 를 오르내림
        val audio = synth(6f, noiseAmp = 1.0) { t -> (1050 + 400 * sin(2 * PI * t / 4)) to 6000.0 }
        val t = firstDetection(audio)
        assertTrue(t != null && t <= 3.0, "detected at $t")
    }

    @Test
    fun `fast yelp siren is detected`() {
        val audio = synth(5f, noiseAmp = 0.7) { t -> (1000 + 350 * sin(2 * PI * t * 3)) to 6000.0 }
        assertTrue(firstDetection(audio) != null)
    }

    @Test
    fun `quiet distant siren is still detected`() {
        val audio = synth(6f, noiseAmp = 0.3) { t -> (1050 + 400 * sin(2 * PI * t / 4)) to 1500.0 }
        assertTrue(firstDetection(audio) != null)
    }

    @Test
    fun `road noise alone is not a siren`() {
        assertFalse(firstDetection(synth(8f, noiseAmp = 2.0) { _ -> 0.0 to 0.0 }) != null)
    }

    @Test
    fun `steady horn or chime is not a siren`() {
        assertFalse(firstDetection(synth(6f, noiseAmp = 0.5) { _ -> 880.0 to 8000.0 }) != null)
    }

    @Test
    fun `music melody of discrete notes is not a siren`() {
        val notes = doubleArrayOf(523.0, 587.0, 659.0, 698.0, 784.0, 880.0, 988.0, 1047.0, 988.0, 880.0, 784.0, 698.0)
        val audio = synth(8f, noiseAmp = 0.3) { t -> notes[((t / 0.3).toInt()) % notes.size] to 6000.0 }
        assertFalse(firstDetection(audio) != null)
    }

    @Test
    fun `speech-like voiced syllables are not a siren`() {
        // 기본 주파수 120~220Hz 가 흔들리는 유성음 + 4Hz 음절 단위로 끊김 + 배음
        val rnd = Random(7)
        val n = 8 * sr
        var phase = 0.0
        val audio = ShortArray(n) { i ->
            val t = i.toDouble() / sr
            val f0 = 170 + 50 * sin(2 * PI * t * 0.7) + 15 * sin(2 * PI * t * 5.3)
            phase += 2 * PI * f0 / sr
            val syllable = if (((t * 4).toInt() % 3) == 2) 0.0 else sin(PI * ((t * 4) % 1.0))
            var v = 0.0
            for (h in 1..12) v += sin(h * phase) / h * (if (h in 3..6) 2.0 else 1.0)
            ((v * 2500 * syllable) + rnd.nextDouble(-200.0, 200.0)).toInt().toShort()
        }
        assertFalse(firstDetection(audio) != null)
    }
}
