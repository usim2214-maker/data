package com.pleos.cardex.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Pleos Vehicle SDK Odometer 로 '이번 주행' 거리·시간을 읽는다. 권한: pleos.car.permission.CAR_DRIVE
 * getTripCurrentDrivingDistance(Float) / getTripCurrentDrivingTime(Integer) — Vehicle 2.3.2 aar 시그니처 기준.
 * TODO(Pleos): 단위는 실차/에뮬레이터에서 확인 (거리 km, 시간 초로 가정).
 * SDK 를 쓸 수 없으면 null → 앱이 위치 기반 추정값을 보낸다.
 */
class TripMeter(context: Context) {
    private val vehicle = runCatching { Vehicle(context.applicationContext) }.getOrNull()
    @Volatile private var ready = false

    fun start(): Boolean {
        val v = vehicle ?: return false
        ready = runCatching { v.initialize(); true }.getOrElse { Log.w(TAG, "Vehicle SDK unavailable", it); false }
        return ready
    }

    /** (거리 km, 주행 분). 실패하면 null */
    suspend fun read(): Pair<Float, Int>? {
        val v = vehicle?.takeIf { ready } ?: return null
        val odo = runCatching { v.getOdometer() }.getOrNull() ?: return null
        val km = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<Float?> { c ->
                runCatching { odo.getTripCurrentDrivingDistance({ d -> if (c.isActive) c.resume(d) }, { e -> Log.w(TAG, "trip distance", e); if (c.isActive) c.resume(null) }) }
                    .onFailure { if (c.isActive) c.resume(null) }
            }
        } ?: return null
        val seconds = withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<Int?> { c ->
                runCatching { odo.getTripCurrentDrivingTime({ t -> if (c.isActive) c.resume(t) }, { e -> Log.w(TAG, "trip time", e); if (c.isActive) c.resume(null) }) }
                    .onFailure { if (c.isActive) c.resume(null) }
            }
        } ?: 0
        return km to (seconds / 60)
    }

    fun release() {
        runCatching { if (ready) vehicle?.release() }
        ready = false
    }

    private companion object {
        const val TAG = "TripMeter"
        const val TIMEOUT_MS = 2_000L
    }
}
