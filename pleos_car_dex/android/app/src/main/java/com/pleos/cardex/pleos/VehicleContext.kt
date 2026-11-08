package com.pleos.cardex.pleos

import android.content.Context
import android.util.Log
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import com.pleos.cardex.data.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow

/** 차량/위치 컨텍스트. 도감 등록 시 '처음 발견한 장소'로 사용. */
data class DrivingSnapshot(val location: GeoPoint?, val headingDeg: Float? = null, val source: String)

/** 히든 스탬프 후보 명소 (근접 알림 대상) */
data class LandmarkTarget(val id: String, val name: String, val lat: Double, val lon: Double, val radiusMeters: Int)

interface VehicleContextProvider {
    fun snapshots(): Flow<DrivingSnapshot>

    /** 명소 반경 진입 알림 등록 (이전 등록은 교체). 지원하지 않으면 no-op — 앱이 거리로 직접 판정한다. */
    fun watchLandmarks(targets: List<LandmarkTarget>, onEnter: (LandmarkTarget) -> Unit) {}
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
    @Volatile private var initialized = false
    private val alertIds = mutableListOf<String>()

    fun isSupported(): Boolean = runCatching { fused.isSupported() }.getOrDefault(false)

    override fun snapshots(): Flow<DrivingSnapshot> = callbackFlow {
        fused.initialize()
        initialized = true
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
            clearAlerts()
            fused.unregisterFusedLocationCallback(listener)
            initialized = false
            fused.release()
        }
    }

    /**
     * Pleos 근접 알림(addProximityAlert)으로 명소 반경 진입(ENTER)을 감지.
     * 요청 파라미터명은 공식 샘플(pleos-sample-fusedlocation-app)과 동일.
     * TODO(Pleos): radiusMeters 의 타입(Int/Float/Double)은 첫 빌드 때 SDK 시그니처로 확인.
     */
    override fun watchLandmarks(targets: List<LandmarkTarget>, onEnter: (LandmarkTarget) -> Unit) {
        if (!initialized) return
        clearAlerts()
        targets.forEach { t ->
            val request = ProximityAlertRequest(
                latitude = t.lat,
                longitude = t.lon,
                radiusMeters = t.radiusMeters.toFloat(),
                transitionTypes = setOf(ProximityTransition.ENTER),
            )
            runCatching {
                fused.addProximityAlert(request, object : ProximityAlertListener {
                    override fun onRegistered(alertId: String) { synchronized(alertIds) { alertIds += alertId } }
                    override fun onTransition(event: ProximityAlertEvent) = onEnter(t) // ENTER 만 등록했으므로
                    override fun onError(exception: Exception) { Log.w(TAG, "proximity ${t.name}", exception) }
                })
            }.onFailure { Log.w(TAG, "addProximityAlert failed", it) }
        }
    }

    private fun clearAlerts() = synchronized(alertIds) {
        alertIds.forEach { id -> runCatching { fused.removeProximityAlert(id) } }
        alertIds.clear()
    }

    private companion object { const val TAG = "PleosLocation" }
}

/** Pleos 서비스가 없는 환경/시연용: 서울 시청에 정차해 있는 가짜 위치 (주행 모드는 시연 전환으로 확인) */
class MockVehicleContextProvider : VehicleContextProvider {
    override fun snapshots(): Flow<DrivingSnapshot> = flow {
        while (true) {
            emit(DrivingSnapshot(GeoPoint(37.5665, 126.9780), source = "Mock"))
            delay(1_000)
        }
    }
}
