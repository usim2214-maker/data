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

    /** 최근 주행 요약 (기기 안에만 저장, 위치 없음). 최신순 최대 20개 */
    val trips: List<String>
        get() = prefs.getString(KEY_TRIPS, null)?.split(TRIP_SEP)?.filter { it.isNotBlank() } ?: emptyList()

    fun addTrip(summary: String) {
        val list = (listOf(summary) + trips).take(20)
        prefs.edit().putString(KEY_TRIPS, list.joinToString(TRIP_SEP)).apply()
    }

    private companion object {
        const val KEY_NEEDS = "needs"
        const val KEY_MODE = "mode"
        const val KEY_TRIPS = "trips"
        const val TRIP_SEP = "\u001E"
    }
}
