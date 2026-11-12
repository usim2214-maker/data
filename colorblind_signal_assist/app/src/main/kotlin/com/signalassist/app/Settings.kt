package com.signalassist.app

import android.content.Context
import com.signalassist.core.AlertPreferences
import com.signalassist.core.AlertType
import com.signalassist.core.Verbosity
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
                verbosity = Verbosity.entries.firstOrNull { it.name == prefs.getString(KEY_VERBOSITY, null) } ?: Verbosity.NORMAL,
                muted = (prefs.getStringSet(KEY_MUTED, emptySet()) ?: emptySet())
                    .mapNotNull { n -> AlertType.entries.firstOrNull { it.name == n } }.toSet(),
            )
            return DriverProfile(needs, alertPrefs)
        }
        set(value) = prefs.edit()
            .putStringSet(KEY_NEEDS, value.needs.map { it.name }.toSet())
            .putString(KEY_LOW_PITCH, when (value.prefs.lowPitch) { true -> "low"; false -> "normal"; null -> "auto" })
            .putFloat(KEY_BOOST, value.prefs.volumeBoost)
            .putBoolean(KEY_SOUND_FOR_HEARING, value.prefs.soundForHearing)
            .putString(KEY_VERBOSITY, value.prefs.verbosity.name)
            .putStringSet(KEY_MUTED, value.prefs.muted.map { it.name }.toSet())
            .apply()

    /** 운전하며 익히기: 알림 종류별로 뜻을 들려준 횟수 */
    var hintCounts: Map<AlertType, Int>
        get() = (prefs.getString(KEY_HINTS, "") ?: "").split(",").mapNotNull { e ->
            val (k, v) = e.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
            AlertType.entries.firstOrNull { it.name == k }?.let { it to (v.toIntOrNull() ?: 0) }
        }.toMap()
        set(value) = prefs.edit().putString(KEY_HINTS, value.entries.joinToString(",") { "${it.key.name}:${it.value}" }).apply()

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

    var largeText: Boolean
        get() = prefs.getBoolean("large_text", false)
        set(value) { prefs.edit().putBoolean("large_text", value).apply() }

    var fullScreenFlash: Boolean
        get() = prefs.getBoolean("full_screen_flash", false)
        set(value) { prefs.edit().putBoolean("full_screen_flash", value).apply() }

    /** 내비와 함께 쓰기: 시동을 걸면 알림 엔진을 자동으로 켜고, 알림은 내비 위 카드로 띄운다 */
    var navCompanion: Boolean
        get() = prefs.getBoolean("nav_companion", true)
        set(value) { prefs.edit().putBoolean("nav_companion", value).apply() }

    private companion object {
        const val KEY_NEEDS = "needs"
        const val KEY_MODE = "mode"
        const val KEY_TRIPS = "trips"
        const val KEY_LOW_PITCH = "low_pitch"
        const val KEY_BOOST = "volume_boost"
        const val KEY_SOUND_FOR_HEARING = "sound_for_hearing"
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_VERBOSITY = "verbosity"
        const val KEY_MUTED = "muted"
        const val KEY_HINTS = "hint_counts"
        const val TRIP_SEP = "\u001E"
    }
}
