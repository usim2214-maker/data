package com.signalassist.app.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.signalassist.app.R
import com.signalassist.app.ui.SignalSymbols
import com.signalassist.core.Alert
import com.signalassist.core.AlertLevel
import com.signalassist.core.AlertType
import java.util.Locale

/**
 * 알림 출력: ① 소리 패턴 ② 음성 안내(TTS) ③ 헤드업 알림(AAOS HUN).
 * 시각 정보는 색에만 의존하지 않도록 모양 기호(■ ▲ ●)와 문구를 함께 쓴다.
 *
 * TODO(Pleos): Pleos SDK 에 클러스터/HUD 표시나 시트·스티어링 햅틱 API 가 있으면 여기서 추가 출력.
 */
class AlertNotifier(private val context: Context) : TextToSpeech.OnInitListener {

    var voiceEnabled = true
    var soundEnabled = true

    private val tone = TonePlayer()
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

    fun notify(alert: Alert) {
        if (soundEnabled) tone.play(alert.tone)
        if (voiceEnabled && ttsReady) {
            // 긴급 경고는 진행 중인 안내를 끊고 즉시
            val mode = if (alert.level == AlertLevel.URGENT) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts.speak(alert.message, mode, Bundle(), "alert-${alert.timestampMs}")
        }
        postNotification(alert)
    }

    private fun postNotification(alert: Alert) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val symbol = when (alert.type) {
            AlertType.GREEN_GO, AlertType.GREEN_REMINDER -> SignalSymbols.GO
            AlertType.YELLOW_CAUTION -> SignalSymbols.CAUTION
            AlertType.RED_STOP, AlertType.RED_URGENT -> SignalSymbols.STOP
            AlertType.LEFT_ARROW_ON -> SignalSymbols.LEFT_ARROW
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("$symbol ${alert.message}")
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(if (alert.level >= AlertLevel.WARNING) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(if (alert.level == AlertLevel.URGENT) 6_000 else 4_000)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId++, n)
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }

    private companion object {
        const val CHANNEL_ID = "signal_alerts"
    }
}
