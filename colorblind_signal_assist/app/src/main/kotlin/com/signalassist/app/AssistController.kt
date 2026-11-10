package com.signalassist.app

import com.signalassist.app.camera.FrameSource
import com.signalassist.app.nav.NavigationDataSource
import com.signalassist.app.vehicle.CabinDataSource
import com.signalassist.app.vehicle.VehicleDataSource
import com.signalassist.core.DriverProfile
import com.signalassist.core.DrivingAssistEngine
import com.signalassist.core.DrivingInput
import com.signalassist.core.DrivingOutput
import com.signalassist.core.Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 한 번에 쓰는 입력 공급원 묶음 */
class Sources(
    val frames: FrameSource?,
    val vehicle: VehicleDataSource,
    val cabin: CabinDataSource,
    val navigation: NavigationDataSource,
) {
    val description: String
        get() = listOfNotNull(frames?.name ?: "영상 없음", vehicle.name, cabin.name, navigation.name).joinToString(" · ")

    fun start() { frames?.start(); vehicle.start(); cabin.start(); navigation.start() }
    fun stop() { frames?.stop(); vehicle.stop(); cabin.stop(); navigation.stop() }
}

/**
 * 입력을 일정 주기로 읽어 엔진에 넣고 결과를 넘긴다.
 * 성능 기준(주행 중 CPU 사용률 제한)을 고려해 영상 처리는 [frameEvery] 번에 한 번만 한다.
 */
class AssistController(
    private val scope: CoroutineScope,
    private val onOutput: (DrivingOutput, Frame?) -> Unit,
    private val tickHz: Int = 10,
    private val frameEvery: Int = 1,
) {
    private var job: Job? = null
    private var sources: Sources? = null
    private val engine = DrivingAssistEngine()

    var profile: DriverProfile
        get() = engine.profile
        set(value) { engine.profile = value }

    fun start(newSources: Sources) {
        stop()
        sources = newSources.also { it.start() }
        engine.reset()
        val interval = 1000L / tickHz
        job = scope.launch(Dispatchers.Default) {
            var tick = 0
            while (isActive) {
                val now = System.currentTimeMillis()
                val wantFrame = engine.profile.signalAssist && tick++ % frameEvery == 0
                val frame = if (wantFrame) newSources.frames?.nextFrame(now) else null
                val input = DrivingInput(
                    timestampMs = now,
                    vehicle = newSources.vehicle.current(now),
                    frame = frame,
                    cabin = newSources.cabin.current(now),
                    navigation = newSources.navigation.current(now),
                )
                onOutput(engine.process(input), frame)
                delay((interval - (System.currentTimeMillis() - now)).coerceAtLeast(5))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        sources?.stop()
        sources = null
    }
}
