package com.signalassist.app.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.signalassist.app.R
import com.signalassist.core.Alert
import com.signalassist.core.AlertLevel
import com.signalassist.core.OutputPlan
import com.signalassist.core.ToneLibrary
import java.util.Locale

/**
 * 알림 출력: ① 소리 패턴 ② 음성 안내 ③ 헤드업 알림(HUN).
 * 운전자 프로필의 [OutputPlan] 에 따라 감각을 고른다. 예) 청각장애만 선택 -> 소리·음성 없이 화면 깜빡임 + HUN
 * 화면 깜빡임/큰 글씨는 MainActivity 가 [com.signalassist.app.AssistBus.alerts] 를 받아 처리한다.
 *
 * 음성: Pleos TextToSpeech SDK 가 '준비 중'이라 표준 Android TextToSpeech 를 쓴다. SDK 공개 시 교체.
 * TODO(Pleos): 클러스터/HUD 표시, 시트·스티어링 햅틱 API 가 공개되면 청각장애 프로필의 출력 채널로 추가.
 */
class AlertNotifier(private val context: Context) : TextToSpeech.OnInitListener {

    private val tone = TonePlayer()
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private val tts = TextToSpeech(context, this)
    private var ttsReady = false
    private var notificationId = 1000

    init {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH)
            .apply { description = context.getString(R.string.channel_desc); setSound(null, null) } // 소리는 TonePlayer 가 담당
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        val result = tts.setLanguage(Locale.KOREAN)
        ttsReady = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    fun notify(alert: Alert, plan: OutputPlan, postHeadsUp: Boolean = true) {
        if (plan.tone || plan.voice) requestFocus(alert.level)
        if (plan.tone) runCatching { tone.play(alert.tone, plan) }
        if (plan.voice && ttsReady) {
            // 알림음과 음성이 겹치면 서로 가려 둘 다 알아듣기 어렵다 -> 알림음이 끝난 뒤 말한다
            val delay = if (plan.tone) ToneLibrary.durationMs(alert.tone) + 80L else 0L
            handler.postDelayed({ speak(alert, plan) }, delay)
        }
        if (postHeadsUp) postNotification(alert)
    }

    private fun speak(alert: Alert, plan: OutputPlan) {
        tts.setSpeechRate(plan.speechRate)
        // 긴급 경고는 진행 중인 안내를 끊고 즉시. 음성은 짧고 핵심어가 먼저 나오는 문장(alert.speech)
        val mode = if (alert.level == AlertLevel.URGENT) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts.speak(alert.speech, mode, Bundle(), "alert-${alert.timestampMs}")
    }

    /**
     * 오디오 포커스: 긴급 경고는 음악을 잠시 멈추고(GAIN_TRANSIENT), 나머지는 음악 볼륨만 낮춘다(MAY_DUCK).
     * 몇 초 뒤 포커스를 돌려준다.
     */
    private fun requestFocus(level: AlertLevel) {
        val gain = if (level == AlertLevel.URGENT) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        val req = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        if (audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focus = req
            handler.postDelayed({ if (focus === req) { audioManager.abandonAudioFocusRequest(req); focus = null } }, 4_000)
        }
    }

    private fun postNotification(alert: Alert) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("${alert.symbol} ${alert.headline}")
            .setContentText(alert.message)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(if (alert.level >= AlertLevel.WARNING) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(if (alert.level == AlertLevel.URGENT) 6_000 else 4_000)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId++, n)
    }

    fun clearPending() {
        handler.removeCallbacksAndMessages(null)
        tone.stop()
        val manager = NotificationManagerCompat.from(context)
        for (id in maxOf(1000, notificationId - 64) until notificationId) manager.cancel(id)
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
        tts.stop()
    }

    fun shutdown() {
        clearPending()
        tts.shutdown()
    }

    private companion object {
        const val CHANNEL_ID = "signal_alerts"
    }
}
