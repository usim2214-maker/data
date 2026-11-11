package com.signalassist.app

import android.content.Context
import com.signalassist.core.AlertPreferences
import com.signalassist.core.DriverNeed
import com.signalassist.core.DriverProfile

/** 프로필·모드 저장 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var profile: DriverProfile
        get() {
            val names = prefs.getStringSet(KEY_NEEDS, null) ?: return DriverProfile.COLOR_VISION_ONLY
            val needs = names.mapNotNull { n -> DriverNeed.entries.firstOrNull { it.name == n } }.toSet()
            val alertPrefs = AlertPreferences(
                lowPitch = when (prefs.getString(KEY_LOW_PITCH, "auto")) { "low" -> true; "normal" -> false; else -> null },
                volumeBoost = prefs.getFloat(KEY_BOOST, 1f),
                soundForHearing = prefs.getBoolean(KEY_SOUND_FOR_HEARING, false),
            )
            return DriverProfile(needs, alertPrefs)
        }
        set(value) = prefs.edit()
            .putStringSet(KEY_NEEDS, value.needs.map { it.name }.toSet())
            .putString(KEY_LOW_PITCH, when (value.prefs.lowPitch) { true -> "low"; false -> "normal"; null -> "auto" })
            .putFloat(KEY_BOOST, value.prefs.volumeBoost)
            .putBoolean(KEY_SOUND_FOR_HEARING, value.prefs.soundForHearing)
            .apply()

    /** 첫 설정(질문 + 소리 테스트)을 마쳤는지 */
    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

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
        const val KEY_LOW_PITCH = "low_pitch"
        const val KEY_BOOST = "volume_boost"
        const val KEY_SOUND_FOR_HEARING = "sound_for_hearing"
        const val KEY_ONBOARDED = "onboarded"
        const val TRIP_SEP = "\u001E"
    }
}
