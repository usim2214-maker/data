package com.signalassist.app.vehicle

import com.signalassist.app.camera.ScenarioSession
import com.signalassist.app.nav.NavigationDataSource
import com.signalassist.core.CabinState
import com.signalassist.core.NavigationState
import com.signalassist.core.VehicleState

/** 합성 시나리오의 차속·실내 상태·내비를 그대로 내보낸다 (영상과 동기화됨) */
class ScenarioVehicleDataSource(private val session: ScenarioSession) : VehicleDataSource {
    override val name = "시나리오 차량"
    override fun start() = Unit
    override fun current(nowMs: Long): VehicleState = session.sampleAt(nowMs).vehicle
    override fun stop() = Unit
}

class ScenarioCabinDataSource(private val session: ScenarioSession) : CabinDataSource {
    override val name = "시나리오 실내"
    override fun start() = Unit
    override fun current(nowMs: Long): CabinState = session.sampleAt(nowMs).cabin
    override fun stop() = Unit
}

class ScenarioNavigationSource(private val session: ScenarioSession) : NavigationDataSource {
    override val name = "시나리오 경로"
    override fun start() = Unit
    override fun current(nowMs: Long): NavigationState? = session.sampleAt(nowMs).navigation
    override fun stop() = Unit
}
