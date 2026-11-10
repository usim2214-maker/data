package ai.parkingguide.app

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.util.Log

/** 지자체 공유용 위치 (Pleos FusedLocation). 보낼 때는 report.municipalPayload가 약 100m 단위로 반올림한다. */
class LocationSource(context: Context) {
    private val fused = FusedLocation(context)
    @Volatile var last: LocationData? = null
        private set
    private var started = false

    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            if (location != null) last = location
        }

        override fun onError(exception: Exception) {
            Log.w("LocationSource", "location error", exception)
        }
    }

    fun refresh() {
        runCatching {
            if (!started) {
                if (!fused.isSupported()) return
                fused.initialize()
                started = true
            }
            fused.getLastKnownLocation(listener)
        }.onFailure { Log.w("LocationSource", "FusedLocation unavailable", it) }
    }

    fun release() {
        if (started) runCatching { fused.release() }
    }
}
