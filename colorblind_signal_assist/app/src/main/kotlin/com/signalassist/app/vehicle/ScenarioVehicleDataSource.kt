package com.signalassist.app.vehicle

import com.signalassist.app.camera.ScenarioSession
import com.signalassist.core.VehicleState

/** 합성 시나리오의 차속을 그대로 내보낸다 (영상과 동기화됨) */
class ScenarioVehicleDataSource(private val session: ScenarioSession) : VehicleDataSource {
    override val name = "시나리오: ${session.scenario.name}"
    override fun start() = Unit
    override fun current(nowMs: Long): VehicleState = session.sampleAt(nowMs).vehicle
    override fun stop() = Unit
}
