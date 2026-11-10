package ai.parkingguide.app

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 음성 안내(Pleos TextToSpeech)와 거리 비프음.
 * ON_DEVICE 모드는 별도 앱 등록 없이 쓸 수 있다 (HYBRID/SERVER는 My Project 자격증명 필요).
 */
class VoiceOutput(context: Context) {
    private val tts = TextToSpeech(context, Mode.ON_DEVICE)
    @Volatile private var ready = false
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_SYSTEM, 80) }.getOrNull()
    private val handler = Handler(Looper.getMainLooper())
    private var beepInterval: Double? = null

    init {
        tts.initialize(object : OnInitializeListener {
            override fun onSuccess() { ready = true }
            override fun onFailure(errorType: ErrorType) { Log.w("VoiceOutput", "TTS init failed: $errorType") }
        })
    }

    fun say(text: String?) {
        if (text == null || !ready) return
        tts.stop() // 늦은 안내가 쌓이지 않게 새 안내가 이전 안내를 끊는다
        tts.speak(text, true)
    }

    /** null = 끄기, 0 = 연속음, 그 외 = 초 간격. */
    fun setBeepInterval(seconds: Double?) {
        val changed = (seconds == null) != (beepInterval == null)
        beepInterval = seconds
        if (changed) {
            handler.removeCallbacks(beepLoop)
            if (seconds != null) handler.post(beepLoop)
        }
    }

    private val beepLoop = object : Runnable {
        override fun run() {
            val s = beepInterval ?: return
            if (s <= 0.0) {
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 300)
                handler.postDelayed(this, 280)
            } else {
                tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
                handler.postDelayed(this, (s * 1000).toLong().coerceAtLeast(100))
            }
        }
    }

    fun release() {
        handler.removeCallbacks(beepLoop)
        tone?.release()
        runCatching { tts.stop(); tts.release() }
    }
}
