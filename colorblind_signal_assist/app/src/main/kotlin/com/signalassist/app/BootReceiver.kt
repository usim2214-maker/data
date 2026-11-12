package com.signalassist.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * '내비와 함께 쓰기': 시동을 걸면(부팅 완료) 앱 화면을 열지 않아도 알림 엔진을 켠다.
 * 운전자는 바로 내비를 켜고 출발하면 되고, 알림은 소리·음성·내비 위 카드로 받는다.
 * 첫 설정(온보딩)을 마치지 않았거나 설정에서 끈 경우에는 켜지 않는다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = runCatching { Settings(context) }.getOrNull() ?: return
        if (shouldStart(settings.onboarded, settings.navCompanion)) AssistService.start(context)
    }

    companion object {
        fun shouldStart(onboarded: Boolean, navCompanion: Boolean) = onboarded && navCompanion
    }
}
