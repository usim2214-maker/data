package com.signalassist.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.signalassist.app.alert.AlertNotifier
import com.signalassist.app.camera.AssetImageFrameSource
import com.signalassist.app.camera.ScenarioFrameSource
import com.signalassist.app.camera.ScenarioSession
import com.signalassist.app.camera.SyntheticCycleFrameSource
import com.signalassist.app.camera.toBitmap
import com.signalassist.app.nav.NaviHelperNavigationSource
import com.signalassist.app.vehicle.CarPropertyVehicleDataSource
import com.signalassist.app.vehicle.PleosCabinDataSource
import com.signalassist.app.vehicle.ScenarioCabinDataSource
import com.signalassist.app.vehicle.ScenarioNavigationSource
import com.signalassist.app.vehicle.ScenarioVehicleDataSource
import com.signalassist.core.Scenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * 알림 엔진을 돌리는 포그라운드 서비스.
 * 주행 중에는 화면 사용이 제한될 수 있으므로(UX 제약), 핵심 기능인 알림은 화면과 무관하게 이 서비스가 담당한다.
 */
class AssistService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notifier: AlertNotifier
    private lateinit var controller: AssistController
    private var liveVehicle: CarPropertyVehicleDataSource? = null
    private var activeKey: String? = null
    private var activeToken = -1
    private var sourceDescription = ""

    override fun onCreate() {
        super.onCreate()
        notifier = AlertNotifier(this)
        controller = AssistController(scope, ::onOutput)
        startAsForeground()
        scope.launch {
            AssistBus.config.filterNotNull().collect { cfg ->
                controller.profile = cfg.profile
                if (cfg.mode.key != activeKey || cfg.restartToken != activeToken) {
                    activeKey = cfg.mode.key
                    activeToken = cfg.restartToken
                    startMode(cfg.mode)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        controller.stop()
        notifier.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    private fun startMode(mode: AssistMode) {
        liveVehicle = null
        val sources = when (mode) {
            is AssistMode.Simulated -> {
                val scenario = Scenario.ALL.firstOrNull { it.name == mode.scenarioName } ?: Scenario.ALL.first()
                val s = ScenarioSession(scenario)
                Sources(ScenarioFrameSource(s), ScenarioVehicleDataSource(s), ScenarioCabinDataSource(s), ScenarioNavigationSource(s))
            }
            else -> {
                val vehicle = CarPropertyVehicleDataSource(this) { AssistBus.speedOverrideKmh }.also { liveVehicle = it }
                val frames = when (mode) {
                    AssistMode.LiveSyntheticSignal -> SyntheticCycleFrameSource()
                    AssistMode.LiveTestImages -> AssetImageFrameSource(this).takeUnless { it.isEmpty }
                    else -> null // 카메라 없이: 차량 경고·내비 카드만 동작
                }
                Sources(frames, vehicle, PleosCabinDataSource(this), NaviHelperNavigationSource(this))
            }
        }
        sourceDescription = sources.description
        controller.start(sources)
    }

    private fun onOutput(out: com.signalassist.core.DrivingOutput, frame: com.signalassist.core.Frame?) {
        scope.launch {
            // 중재(AlertArbiter)를 거친 전달 목록: 같은 순간엔 가장 중요한 알림만 소리·음성·깜빡임
            for ((alert, plan) in out.deliveries) {
                notifier.notify(alert, plan)
                AssistBus.alerts.tryEmit(alert to plan)
            }
            AssistBus.snapshot.value = UiSnapshot(
                output = out,
                preview = frame?.toBitmap(),
                sourceDescription = sourceDescription,
                hasRealSpeed = liveVehicle?.hasRealSpeed ?: true,
            )
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(SERVICE_CHANNEL, getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_MIN),
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    companion object {
        private const val SERVICE_CHANNEL = "assist_service"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            context.startForegroundService(Intent(context, AssistService::class.java))
        }
    }
}
