package com.pleos.cardex.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.OnServerConnectionListener
import ai.pleos.playground.stt.listener.ResultListener
import com.pleos.cardex.BuildConfig
import java.util.Locale

/**
 * Pleos SpeechToText SDK 로 주행 중 음성 명령 받기 (화면을 만지지 않도록).
 * listen() -> 말이 끝나면(final) onText(인식 문장). Playground 앱 ID/키가 있으면 서버 등록.
 */
class VoiceCommand(context: Context) {
    private val hasKey = BuildConfig.PLEOS_APP_ID.isNotBlank() && BuildConfig.PLEOS_APP_KEY.isNotBlank()
    private val stt: SpeechToText? = runCatching { SpeechToText(context.applicationContext, if (hasKey) Mode.HYBRID else Mode.ON_DEVICE) }
        .onFailure { Log.w(TAG, "Pleos STT unavailable", it) }.getOrNull()

    @Volatile var available = false
        private set
    var onText: (String) -> Unit = {}
    var onListening: (Boolean) -> Unit = {}

    private val listener = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) { if (isFinal && text.isNotBlank()) onText(text) }
        override fun onUpdatedEpdData(start: Long, end: Long) {}
        override fun onStartedRecognition() = onListening(true)
        override fun onEndedRecognition() = onListening(false)
        override fun onError(errorType: ErrorType) { Log.w(TAG, "STT error: $errorType"); onListening(false) }
        override fun onReady() {}
        override fun onUpdatedRms(rms: Double) {}
    }

    init {
        stt?.let { s ->
            runCatching {
                s.initialize(object : OnInitializeListener {
                    override fun onSuccess() {
                        runCatching { s.setLocale(Locale.KOREAN); s.addListener(listener) }
                        if (hasKey) {
                            s.registerApp(BuildConfig.PLEOS_APP_ID, BuildConfig.PLEOS_APP_KEY, object : OnServerConnectionListener {
                                override fun onConnected() { available = true }
                                override fun onFailed(errorType: ErrorType) { Log.w(TAG, "registerApp: $errorType") }
                            })
                        } else {
                            available = true
                        }
                    }
                    override fun onFailure(errorType: ErrorType) { Log.w(TAG, "STT init failed: $errorType") }
                })
            }.onFailure { Log.w(TAG, "STT init", it) }
        }
    }

    fun listen(): Boolean {
        if (!available) return false
        return runCatching { stt?.request(); true }.getOrDefault(false)
    }

    fun release() {
        runCatching { stt?.removeListener(listener) }
        runCatching { stt?.release() }
    }

    private companion object { const val TAG = "VoiceCommand" }
}
