package com.pleos.cardex.pleos

import com.pleos.cardex.data.GeoPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** 차량/위치 컨텍스트. 도감 등록 시 '처음 발견한 장소'로 사용. */
data class DrivingSnapshot(val location: GeoPoint?, val speedKmh: Float, val isParked: Boolean)

interface VehicleContextProvider {
    fun snapshots(): Flow<DrivingSnapshot>
}

/**
 * Pleos SDK 연동 지점.
 *
 * TODO(Pleos): Pleos Playground 문서의 FusedLocation SDK / Vehicle SDK 로 구현.
 *  - FusedLocation 콜백 -> GeoPoint(lat, lon)
 *  - 차속/기어(P) 신호 -> speedKmh, isParked (주행 중 카드 상세 표시 제한 등 UX 정책에 사용)
 *  SDK 의 정확한 클래스/메서드명은 Playground 에서 받은 SDK 버전 문서를 따를 것.
 */
class PleosVehicleContextProvider : VehicleContextProvider {
    override fun snapshots(): Flow<DrivingSnapshot> =
        throw NotImplementedError("Pleos SDK 연동 전입니다. BuildConfig.USE_MOCK_PLEOS=true 로 실행하세요.")
}

/** 에뮬레이터/시연용: 서울 시내를 천천히 이동하는 가짜 주행 데이터 */
class MockVehicleContextProvider : VehicleContextProvider {
    override fun snapshots(): Flow<DrivingSnapshot> = flow {
        var lat = 37.5665
        var lon = 126.9780
        while (true) {
            lat += 0.0001; lon += 0.0001
            emit(DrivingSnapshot(GeoPoint(lat, lon), speedKmh = 32f, isParked = false))
            delay(1_000)
        }
    }
}
