package com.signalassist.app

import android.content.Context
import com.signalassist.core.DriverNeed
import com.signalassist.core.DriverProfile

/** 프로필·모드 저장 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var profile: DriverProfile
        get() {
            val names = prefs.getStringSet(KEY_NEEDS, null) ?: return DriverProfile.COLOR_VISION_ONLY
            return DriverProfile(names.mapNotNull { n -> DriverNeed.entries.firstOrNull { it.name == n } }.toSet())
        }
        set(value) = prefs.edit().putStringSet(KEY_NEEDS, value.needs.map { it.name }.toSet()).apply()

    var mode: AssistMode
        get() = AssistMode.fromKey(prefs.getString(KEY_MODE, null))
        set(value) = prefs.edit().putString(KEY_MODE, value.key).apply()

    private companion object {
        const val KEY_NEEDS = "needs"
        const val KEY_MODE = "mode"
    }
}
