package com.signalassist.app

import android.graphics.Bitmap
import com.signalassist.core.Alert
import com.signalassist.core.AlertType
import com.signalassist.core.DriverProfile
import com.signalassist.core.DrivingOutput
import com.signalassist.core.OutputPlan
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** 입력 모드: 카메라가 없으므로 합성 영상 / 테스트 이미지로 대체 */
sealed class AssistMode(val key: String) {
    class Simulated(val scenarioName: String) : AssistMode("scenario:$scenarioName")
    data object LiveSyntheticSignal : AssistMode("live_synthetic")
    data object LiveTestImages : AssistMode("live_images")
    data object LiveNoCamera : AssistMode("live_nocam")

    companion object {
        fun fromKey(key: String?): AssistMode = when {
            key == null -> Simulated("stop_and_go")
            key.startsWith("scenario:") -> Simulated(key.removePrefix("scenario:"))
            key == LiveSyntheticSignal.key -> LiveSyntheticSignal
            key == LiveTestImages.key -> LiveTestImages
            key == LiveNoCamera.key -> LiveNoCamera
            else -> Simulated("stop_and_go")
        }
    }
}

/** 화면에서 서비스로 보내는 요청 */
sealed class UserRequest {
    /** 마지막 알림 다시 듣기 */
    data object Replay : UserRequest()
    /** 알림 배우기: 해당 알림을 시범으로 */
    data class Lesson(val type: AlertType) : UserRequest()
}

data class AssistConfig(val mode: AssistMode, val profile: DriverProfile, val restartToken: Int = 0)

data class UiSnapshot(
    val output: DrivingOutput,
    val preview: Bitmap?,
    val sourceDescription: String,
    val hasRealSpeed: Boolean,
)

/**
 * 서비스(엔진)와 화면 사이의 단순한 메시지 버스.
 * 화면이 꺼져 있거나 다른 앱이 떠 있어도 서비스는 계속 알림을 낸다.
 */
object AssistBus {
    val config = MutableStateFlow<AssistConfig?>(null)
    val snapshot = MutableStateFlow<UiSnapshot?>(null)
    val alerts = MutableSharedFlow<Pair<Alert, OutputPlan>>(extraBufferCapacity = 16)
    val requests = MutableSharedFlow<UserRequest>(extraBufferCapacity = 8)

    /** Pleos 에뮬레이터는 차속을 시뮬레이션하지 못하므로 시연용 차속 (null 이면 실제 값) */
    @Volatile var speedOverrideKmh: Float? = null
}
