package com.signalassist.app.alert

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.signalassist.core.TonePattern
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * 신호 종류별로 '음높이 방향 + 리듬'이 다른 알림음을 합성해 재생한다.
 * 색을 구분하기 어려운 운전자도 소리만 듣고 진행/주의/정지를 구분할 수 있게 하는 것이 목적.
 */
class TonePlayer {
    private data class Note(val freqHz: Double, val durMs: Int, val gapMs: Int = 60)

    private val sampleRate = 44_100

    private val patterns: Map<TonePattern, List<Note>> = mapOf(
        TonePattern.RISING_DOUBLE to listOf(Note(523.0, 150), Note(784.0, 220)), // 도 -> 솔 (올라감 = 진행)
        TonePattern.SINGLE_LONG to listOf(Note(660.0, 500)),                      // 한 번 길게 = 주의
        TonePattern.FALLING_TRIPLE to listOf(Note(880.0, 150), Note(660.0, 150), Note(440.0, 260)), // 내려감 = 정지
        TonePattern.RAPID_BEEPS to List(6) { Note(1000.0, 80, 50) },              // 빠른 반복 = 긴급
        TonePattern.SOFT_CHIME to listOf(Note(1047.0, 120)),
    )

    private val cache = HashMap<TonePattern, ShortArray>()

    fun play(pattern: TonePattern) {
        val pcm = cache.getOrPut(pattern) { synth(patterns.getValue(pattern)) }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // AAOS 에서 내비 안내음처럼 미디어를 덕킹(볼륨 낮춤)하고 재생됨
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        track.write(pcm, 0, pcm.size)
        track.setNotificationMarkerPosition(pcm.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) = t.release()
            override fun onPeriodicNotification(t: AudioTrack) = Unit
        })
        track.play()
    }

    private fun synth(notes: List<Note>): ShortArray {
        val total = notes.sumOf { (it.durMs + it.gapMs) * sampleRate / 1000 }
        val out = ShortArray(total)
        var pos = 0
        for (n in notes) {
            val len = n.durMs * sampleRate / 1000
            val fade = min(len / 4, sampleRate / 100) // 10ms 페이드로 '틱' 소리 방지
            for (i in 0 until len) {
                val env = when {
                    i < fade -> i.toDouble() / fade
                    i > len - fade -> (len - i).toDouble() / fade
                    else -> 1.0
                }
                out[pos + i] = (sin(2 * PI * n.freqHz * i / sampleRate) * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
            }
            pos += len + n.gapMs * sampleRate / 1000
        }
        return out
    }
}
