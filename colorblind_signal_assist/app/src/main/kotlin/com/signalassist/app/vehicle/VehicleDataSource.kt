package com.signalassist.app.vehicle

import com.signalassist.core.VehicleState

/**
 * 차량 신호 공급원. 구현체를 바꿔 끼워 실차 / Pleos SDK / 시뮬레이션을 전환한다.
 */
interface VehicleDataSource {
    val name: String
    fun start()
    fun current(nowMs: Long): VehicleState
    fun stop()
}
