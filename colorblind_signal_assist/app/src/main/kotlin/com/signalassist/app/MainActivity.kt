package com.signalassist.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.signalassist.app.alert.AlertNotifier
import com.signalassist.app.camera.AssetImageFrameSource
import com.signalassist.app.camera.FrameSource
import com.signalassist.app.camera.ScenarioFrameSource
import com.signalassist.app.camera.ScenarioSession
import com.signalassist.app.camera.SyntheticCycleFrameSource
import com.signalassist.app.camera.toBitmap
import com.signalassist.app.ui.SignalPanelView
import com.signalassist.app.vehicle.PleosVehicleDataSource
import com.signalassist.app.vehicle.ScenarioVehicleDataSource
import com.signalassist.app.vehicle.VehicleDataSource
import com.signalassist.core.EngineOutput
import com.signalassist.core.Frame
import com.signalassist.core.Scenario

class MainActivity : AppCompatActivity() {

    /** 입력 모드: 카메라가 없으므로 합성 영상 / 테스트 이미지로 대체 */
    private sealed class Mode(val label: String) {
        class Simulated(val scenario: Scenario) : Mode("시뮬레이션: ${scenario.description}")
        data object LiveVehicleSyntheticSignal : Mode("실차 데이터 + 가상 신호 주기")
        data object LiveVehicleTestImages : Mode("실차 데이터 + 테스트 이미지(assets)")
    }

    private val modes: List<Mode> =
        Scenario.ALL.map { Mode.Simulated(it) } + Mode.LiveVehicleSyntheticSignal + Mode.LiveVehicleTestImages

    private lateinit var panel: SignalPanelView
    private lateinit var preview: ImageView
    private lateinit var status: TextView
    private lateinit var lastAlert: TextView
    private lateinit var notifier: AlertNotifier
    private lateinit var controller: AssistController
    private var selected: Mode = modes.first()
    private var showPreview = true
    private var clearFlashAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notifier = AlertNotifier(this)
        controller = AssistController(lifecycleScope, ::render)
        setContentView(buildUi())
        requestPermissionsIfNeeded()
    }

    override fun onStart() {
        super.onStart()
        startSelected()
    }

    override fun onStop() {
        controller.stop()
        super.onStop()
    }

    override fun onDestroy() {
        notifier.shutdown()
        super.onDestroy()
    }

    private fun startSelected() {
        val (frames: FrameSource, vehicle: VehicleDataSource) = when (val m = selected) {
            is Mode.Simulated -> ScenarioSession(m.scenario).let { ScenarioFrameSource(it) to ScenarioVehicleDataSource(it) }
            Mode.LiveVehicleSyntheticSignal -> SyntheticCycleFrameSource() to PleosVehicleDataSource(this)
            Mode.LiveVehicleTestImages -> {
                val images = AssetImageFrameSource(this)
                if (images.isEmpty) {
                    status.text = "assets/test_frames 에 이미지가 없습니다. README.txt 참고"
                    return
                }
                images to PleosVehicleDataSource(this)
            }
        }
        controller.start(frames, vehicle)
        status.text = "영상: ${frames.name} · 차량: ${vehicle.name}"
    }

    private fun render(out: EngineOutput, frame: Frame) {
        panel.state = out.state
        if (showPreview) preview.setImageBitmap(frame.toBitmap())
        val v = out.vehicle
        status.text = "%.0f km/h · 기어 %s · 프레임 인식 %s%s".format(
            v.speedKmh, v.gear, out.detection.color.labelKo, if (out.detection.leftTurnArrow) "+좌회전" else "",
        )
        for (alert in out.alerts) {
            notifier.notify(alert)
            lastAlert.text = alert.message
            panel.flashLevel = alert.level
            clearFlashAt = out.timestampMs + 1_500
        }
        if (panel.flashLevel != null && out.timestampMs > clearFlashAt) panel.flashLevel = null
    }

    // ---- UI (주행 중 조작 최소화: 큰 표시 + 설정은 최소한) ----

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(24, 16, 24, 16)
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        panel = SignalPanelView(this)
        preview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        body.addView(panel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f))
        body.addView(preview, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f))
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        lastAlert = label(34f, Color.WHITE)
        status = label(22f, Color.LTGRAY)
        root.addView(lastAlert)
        root.addView(status)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modes.map { it.label })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (selected != modes[pos]) { selected = modes[pos]; startSelected() }
                }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        }
        controls.addView(spinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(Button(this).apply { text = "다시 시작"; setOnClickListener { startSelected() } })
        controls.addView(CheckBox(this).apply {
            text = "음성"; setTextColor(Color.WHITE); isChecked = true
            setOnCheckedChangeListener { _, c -> notifier.voiceEnabled = c }
        })
        controls.addView(CheckBox(this).apply {
            text = "영상"; setTextColor(Color.WHITE); isChecked = true
            setOnCheckedChangeListener { _, c -> showPreview = c; preview.visibility = if (c) View.VISIBLE else View.GONE }
        })
        root.addView(controls)
        return root
    }

    private fun label(sizeSp: Float, color: Int) = TextView(this).apply {
        textSize = sizeSp; setTextColor(color); setPadding(0, 6, 0, 6)
    }

    private fun requestPermissionsIfNeeded() {
        val wanted = arrayOf("android.car.permission.CAR_SPEED", Manifest.permission.POST_NOTIFICATIONS)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 차속 권한을 받은 뒤 차량 데이터 구독을 다시 등록
        if (controller.isRunning) startSelected()
    }
}
