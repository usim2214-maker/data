package ai.parkingguide.app

import ai.parkingguide.core.ParkingReport
import ai.parkingguide.core.trendText
import android.content.Context

/** 주차 점수 기록 (기기 안에만 저장). 성장 추이 문구를 만든다. */
class HistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("parking_history", Context.MODE_PRIVATE)

    fun add(report: ParkingReport): ParkingReport {
        val scores = (prefs.getString("scores", "") ?: "").split(",").filter { it.isNotBlank() }.map { it.toInt() } + report.score
        prefs.edit().putString("scores", scores.takeLast(50).joinToString(",")).apply()
        return report.copy(trend = trendText(scores))
    }
}
