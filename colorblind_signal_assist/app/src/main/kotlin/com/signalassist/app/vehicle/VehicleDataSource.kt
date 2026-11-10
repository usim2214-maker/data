package com.signalassist.app.vehicle

import com.signalassist.core.CabinState
import com.signalassist.core.VehicleState

/** 주행 신호(차속·기어) 공급원 */
interface VehicleDataSource {
    val name: String
    fun start()
    fun current(nowMs: Long): VehicleState
    fun stop()
}

/** 실내·차체 상태(벨트·문·타이어·방향지시등·전조등·좌석) 공급원 */
interface CabinDataSource {
    val name: String
    fun start()
    fun current(nowMs: Long): CabinState
    fun stop()
}
