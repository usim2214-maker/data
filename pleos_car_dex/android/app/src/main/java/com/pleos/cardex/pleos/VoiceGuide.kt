package com.pleos.cardex.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import java.util.Locale

/**
 * 주행 중 화면 대신 음성으로 알려주기 (스탬프 획득, 코스 진행, 완주 등).
 * 1순위 Pleos TextToSpeech SDK(ON_DEVICE), 실패하면 안드로이드 기본 TTS 로 대체.
 */
class VoiceGuide(context: Context) {
    private val appContext = context.applicationContext
    private var pleos: TextToSpeech? = null
    private var pleosReady = false
    private var fallback: android.speech.tts.TextToSpeech? = null
    private var fallbackReady = false

    init {
        runCatching {
            TextToSpeech(appContext, Mode.ON_DEVICE).also { tts ->
                pleos = tts
                tts.initialize(object : OnInitializeListener {
                    override fun onSuccess() { pleosReady = true; runCatching { tts.setLocale(Locale.KOREAN) } }
                    override fun onFailure(errorType: ErrorType) {
                        Log.w(TAG, "Pleos TTS init failed: $errorType"); useFallback()
                    }
                })
            }
        }.onFailure { Log.w(TAG, "Pleos TTS unavailable", it); useFallback() }
    }

    private fun useFallback() {
        if (fallback != null) return
        fallback = android.speech.tts.TextToSpeech(appContext) { status ->
            fallbackReady = status == android.speech.tts.TextToSpeech.SUCCESS
            if (fallbackReady) fallback?.language = Locale.KOREAN
        }
    }

    fun speak(text: String) {
        val clean = text.replace(Regex("[\\p{So}\\p{Cn}]"), "").trim() // 이모지 제거
        when {
            pleosReady -> runCatching { pleos?.speak(clean, true) }.onFailure { useFallback() }
            fallbackReady -> fallback?.speak(clean, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "cardex")
            else -> useFallback()
        }
    }

    fun release() {
        runCatching { pleos?.release() }
        runCatching { fallback?.shutdown() }
    }

    private companion object { const val TAG = "VoiceGuide" }
}
