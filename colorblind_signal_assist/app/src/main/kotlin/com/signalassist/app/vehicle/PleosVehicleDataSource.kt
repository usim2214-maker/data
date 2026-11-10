package com.signalassist.app.vehicle

import android.content.Context
import com.signalassist.core.VehicleState

/**
 * Pleos Vehicle SDK 어댑터 자리.
 *
 * Pleos Playground 의 Vehicle SDK 는 Android API 호환을 유지하면서 차량 데이터 활용·제어를 확장한다고
 * 소개되어 있다. SDK 문서를 받은 뒤 아래 TODO 부분을 실제 호출로 바꾸면 되고, 그 전까지는
 * 표준 Car API 구현([CarPropertyVehicleDataSource])에 위임한다.
 *
 * 앱의 나머지 부분은 [VehicleDataSource] 인터페이스에만 의존하므로 이 파일만 고치면 된다.
 * Pleos SDK 에서 얻으면 좋은 추가 신호:
 *  - 브레이크 페달 상태 -> VehicleState.brakePedal (운전자 반응 판단 정확도 향상)
 *  - 클러스터/HUD 표시, 시트·스티어링 햅틱 -> alert/AlertNotifier 에서 활용
 */
class PleosVehicleDataSource(context: Context) : VehicleDataSource {
    override val name = "Pleos Vehicle (Car API fallback)"

    private val fallback = CarPropertyVehicleDataSource(context)

    override fun start() {
        // TODO(Pleos): Pleos Vehicle SDK 초기화 / 구독
        fallback.start()
    }

    override fun current(nowMs: Long): VehicleState {
        // TODO(Pleos): SDK 값으로 VehicleState 구성 (speedKmh, gear, brakePedal)
        return fallback.current(nowMs)
    }

    override fun stop() {
        fallback.stop()
    }
}
