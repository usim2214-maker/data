package com.pleos.cardex.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import com.pleos.cardex.data.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

/** 차량/위치 컨텍스트. 도감 등록 시 '처음 발견한 장소'로 사용. */
data class DrivingSnapshot(val location: GeoPoint?, val headingDeg: Float? = null, val source: String)

interface VehicleContextProvider {
    fun snapshots(): Flow<DrivingSnapshot>
}

/**
 * Pleos FusedLocation SDK 기반 위치 제공자.
 * 사용 순서는 Pleos 공식 샘플(pleos-sample-fusedlocation-app)과 동일:
 * isSupported() -> (권한 pleos.car.permission.FUSED_LOCATION 허용 후) initialize()
 * -> registerFusedLocationCallback() ... unregisterFusedLocationCallback() -> release()
 *
 * TODO(Pleos): 차속/기어 등은 Vehicle SDK(ai.pleos.playground:Vehicle) 로 확장.
 */
class PleosVehicleContextProvider(context: Context) : VehicleContextProvider {
    private val fused = FusedLocation(context.applicationContext)

    fun isSupported(): Boolean = runCatching { fused.isSupported() }.getOrDefault(false)

    override fun snapshots(): Flow<DrivingSnapshot> = callbackFlow {
        fused.initialize()
        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                location ?: return
                trySend(
                    DrivingSnapshot(GeoPoint(location.latitude, location.longitude), location.heading, source = "Pleos FusedLocation")
                )
            }

            override fun onError(exception: Exception) {
                Log.w(TAG, "FusedLocation error", exception)
            }
        }
        fused.registerFusedLocationCallback(listener)
        awaitClose {
            fused.unregisterFusedLocationCallback(listener)
            fused.release()
        }
    }

    private companion object { const val TAG = "PleosLocation" }
}

/** Pleos 서비스가 없는 환경/시연용: 서울 시내를 천천히 이동하는 가짜 위치 */
class MockVehicleContextProvider : VehicleContextProvider {
    override fun snapshots(): Flow<DrivingSnapshot> = flow {
        var lat = 37.5665
        var lon = 126.9780
        while (true) {
            lat += 0.0001; lon += 0.0001
            emit(DrivingSnapshot(GeoPoint(lat, lon), source = "Mock"))
            delay(1_000)
        }
    }
}
