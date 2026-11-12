package com.signalassist.app.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.signalassist.app.camera.ScenarioSession
import com.signalassist.core.SirenDetector

/** 긴급차량 사이렌 감지 결과 공급원 */
interface SirenSource {
    val name: String
    fun start()
    /** 감지 여부. 마이크를 쓸 수 없으면 null */
    fun current(nowMs: Long): Boolean?
    fun stop()
}

/**
 * 차량 실내 마이크로 사이렌을 감지한다 (청각장애 프로필일 때만 켬).
 * 소리는 기기 안에서 [SirenDetector] 로 바로 분석하고, 녹음 파일을 만들거나 전송하지 않는다.
 */
class MicSirenSource(private val context: Context) : SirenSource {
    override val name = "실내 마이크 (사이렌 감지)"
    private val sampleRate = 16_000
    @Volatile private var detected: Boolean? = null
    @Volatile private var activeRun: java.util.concurrent.atomic.AtomicBoolean? = null
    @Volatile private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    override fun start() {
        stop()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return
        val token = java.util.concurrent.atomic.AtomicBoolean(true)
        activeRun = token
        thread = Thread({
            var record: AudioRecord? = null
            try {
                val audio = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 4096))
                record = audio
                if (audio.state != AudioRecord.STATE_INITIALIZED || !token.get()) return@Thread
                recorder = audio
                audio.startRecording()
                val detector = SirenDetector()
                val buf = ShortArray(1024)
                while (token.get()) {
                    val n = audio.read(buf, 0, buf.size)
                    if (n <= 0) break
                    val result = detector.feed(buf, n)
                    if (activeRun === token && token.get()) detected = result
                }
            } catch (e: Exception) {
                Log.w(TAG, "microphone unavailable", e)
            } finally {
                runCatching { record?.stop() }
                record?.release()
                if (activeRun === token) { detected = null; recorder = null }
            }
        }, "siren-detector").apply { isDaemon = true; start() }
    }

    override fun current(nowMs: Long): Boolean? = detected

    override fun stop() {
        activeRun?.set(false)
        activeRun = null
        runCatching { recorder?.stop() }
        recorder = null
        thread?.interrupt()
        thread = null
        detected = null
    }

    private companion object {
        const val TAG = "MicSiren"
    }
}

/** 시나리오의 사이렌 구간 */
class ScenarioSirenSource(private val session: ScenarioSession) : SirenSource {
    override val name = "시나리오 소리"
    override fun start() = Unit
    override fun current(nowMs: Long): Boolean = session.sampleAt(nowMs).sirenNearby
    override fun stop() = Unit
}

/** 사이렌 감지를 쓰지 않음 */
object NoSirenSource : SirenSource {
    override val name = "사이렌 감지 꺼짐"
    override fun start() = Unit
    override fun current(nowMs: Long): Boolean? = null
    override fun stop() = Unit
}
