package com.signalassist.app

import com.signalassist.app.camera.FrameSource
import com.signalassist.app.vehicle.VehicleDataSource
import com.signalassist.core.EngineOutput
import com.signalassist.core.Frame
import com.signalassist.core.SignalAssistEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 영상 + 차량 데이터를 일정 주기로 읽어 엔진에 넣고, 결과를 UI/알림으로 넘긴다.
 */
class AssistController(
    private val scope: CoroutineScope,
    private val onOutput: (EngineOutput, Frame) -> Unit,
    private val fps: Int = 10,
) {
    private var job: Job? = null
    private var frameSource: FrameSource? = null
    private var vehicleSource: VehicleDataSource? = null
    private val engine = SignalAssistEngine()

    val isRunning get() = job?.isActive == true

    fun start(frames: FrameSource, vehicle: VehicleDataSource) {
        stop()
        frameSource = frames.also { it.start() }
        vehicleSource = vehicle.also { it.start() }
        engine.reset()
        val interval = 1000L / fps
        job = scope.launch(Dispatchers.Default) {
            while (isActive) {
                val began = System.currentTimeMillis()
                val frame = frames.nextFrame(began)
                if (frame != null) {
                    val out = engine.process(frame, vehicle.current(began), began)
                    withContext(Dispatchers.Main) { onOutput(out, frame) }
                }
                delay((interval - (System.currentTimeMillis() - began)).coerceAtLeast(5))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        frameSource?.stop()
        vehicleSource?.stop()
        frameSource = null
        vehicleSource = null
    }
}
