package com.signalassist.app.alert

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.signalassist.core.OutputPlan
import com.signalassist.core.ToneLibrary
import com.signalassist.core.TonePattern

/**
 * 알림음 재생. 소리 설계(배음, 고령자 저음역, 위험도별 크기)는 core 의 [ToneLibrary] 에 있다.
 * 색을 구분하기 어려운 운전자도 소리만 듣고 진행/주의/정지를 구분할 수 있게 하는 것이 목적.
 */
class TonePlayer {
    private val sampleRate = 44_100
    private val cache = HashMap<Pair<TonePattern, Boolean>, ShortArray>()

    fun play(pattern: TonePattern, plan: OutputPlan) {
        val pcm = cache.getOrPut(pattern to plan.lowPitchTones) {
            ToneLibrary.synthesize(pattern, sampleRate, plan.lowPitchTones, gain = 1f)
        }
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
        track.setVolume(plan.toneGain) // 안내 < 주의 < 경고 < 긴급
        track.setNotificationMarkerPosition(pcm.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) = t.release()
            override fun onPeriodicNotification(t: AudioTrack) = Unit
        })
        track.play()
    }
}
