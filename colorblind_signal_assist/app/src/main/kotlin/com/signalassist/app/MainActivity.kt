package com.signalassist.app

import android.Manifest
import android.animation.ObjectAnimator
import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.signalassist.app.ui.SafePalette
import com.signalassist.app.ui.SignalPanelView
import com.signalassist.core.Alert
import com.signalassist.core.AlertGuide
import com.signalassist.core.AlertLevel
import com.signalassist.core.CabinState
import com.signalassist.core.DriverNeed
import com.signalassist.core.DriverProfile
import com.signalassist.core.OutputPlan
import com.signalassist.core.Scenario
import com.signalassist.core.TurnSignal
import kotlinx.coroutines.launch

/**
 * 화면: 설정(주차 중) + 큰 상태 표시(주행 중).
 * 알림 자체는 [AssistService] 가 내므로 이 화면이 없어도 동작한다.
 */
class MainActivity : AppCompatActivity() {

    private data class ModeItem(val mode: AssistMode, val label: String)

    private val modeItems: List<ModeItem> =
        Scenario.ALL.map { ModeItem(AssistMode.Simulated(it.name), "시뮬레이션: ${it.titleKo}") } + listOf(
            ModeItem(AssistMode.LiveNoCamera, "실차: 차량 경고·내비 (카메라 없이)"),
            ModeItem(AssistMode.LiveSyntheticSignal, "실차 + 가상 신호 주기"),
            ModeItem(AssistMode.LiveTestImages, "실차 + 테스트 이미지(assets)"),
        )

    private lateinit var settings: Settings
    private lateinit var panel: SignalPanelView
    private lateinit var preview: ImageView
    private lateinit var navCard: TextView
    private lateinit var cabinView: TextView
    private lateinit var banner: TextView
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private lateinit var flash: TextView
    private lateinit var speedRow: LinearLayout
    private lateinit var lessonRow: LinearLayout
    private var restartToken = 0
    private var drivingRestricted = false
    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        setContentView(buildUi())
        publishConfig()
        rebuildLessons(settings.profile)
        AssistService.start(this)
        requestPermissionsIfNeeded()
        connectUxRestrictions()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AssistBus.snapshot.collect { it?.let(::render) } }
                launch { AssistBus.alerts.collect { (alert, plan) -> showAlert(alert, plan) } }
            }
        }
    }

    override fun onDestroy() {
        runCatching { uxManager?.unregisterListener() }
        car?.disconnect()
        super.onDestroy()
    }

    private fun publishConfig() {
        AssistBus.config.value = AssistConfig(settings.mode, settings.profile, restartToken)
        applyProfileToUi(settings.profile)
    }

    // ---- 주행 중 UX 제약 ----

    private fun connectUxRestrictions() {
        val onReady: (Car) -> Unit = { c ->
            val mgr = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            uxManager = mgr
            mgr?.registerListener { r -> runOnUiThread { applyRestrictions(r) } }
            mgr?.currentCarUxRestrictions?.let { r -> runOnUiThread { applyRestrictions(r) } }
        }
        car = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready -> if (ready) onReady(c) }
            } else {
                Car.createCar(this)?.also(onReady)
            }
        }.getOrNull()
    }

    /** 주행 중에는 설정·영상 미리보기를 숨기고 큰 상태 표시만 남긴다 */
    private fun applyRestrictions(r: CarUxRestrictions) {
        drivingRestricted = r.isRequiresDistractionOptimization
        val vis = if (drivingRestricted) View.GONE else View.VISIBLE
        controls.visibility = vis
        preview.visibility = if (drivingRestricted || !settings.profile.signalAssist) View.GONE else View.VISIBLE
    }

    // ---- 표시 ----

    private fun render(s: UiSnapshot) {
        val out = s.output
        panel.state = out.signalState
        if (!drivingRestricted && s.preview != null) preview.setImageBitmap(s.preview)

        val nav = out.input.navigation
        navCard.text = if (nav?.guiding == true && nav.distanceToManeuverM != null && nav.nextManeuver != null) {
            val m = nav.nextManeuver!!
            "${m.symbol}  ${Math.round(nav.distanceToManeuverM!! / 10f) * 10}m  ${m.labelKo}" + (nav.roadName?.let { "\n$it" } ?: "")
        } else {
            "경로 안내 없음"
        }
        cabinView.text = describeCabin(out.input.cabin)
        val v = out.input.vehicle
        status.text = "%.0f km/h · 기어 %s · %s".format(v.speedKmh, gearKo(v.gear), s.sourceDescription)
        speedRow.visibility = if (!s.hasRealSpeed && !drivingRestricted) View.VISIBLE else View.GONE
    }

    private fun showAlert(alert: Alert, plan: OutputPlan) {
        banner.text = "${alert.symbol}  ${alert.message}"
        if (!plan.visualFlash) return
        flash.text = "${alert.symbol}\n${alert.message}"
        flash.setBackgroundColor(
            when (alert.level) {
                AlertLevel.URGENT -> SafePalette.VERMILION
                AlertLevel.WARNING -> Color.rgb(170, 70, 0)
                AlertLevel.CAUTION -> Color.rgb(120, 110, 20)
                AlertLevel.INFO -> Color.rgb(0, 90, 140)
            },
        )
        flash.visibility = View.VISIBLE
        // 두 번 깜빡인 뒤 사라짐 (소리를 못 듣는 운전자가 주변 시야로도 알아챌 수 있게).
        // 초당 3회 미만으로 깜빡여 광과민성 발작 기준을 넘지 않고, 야간에는 밝기를 낮춰 눈부심을 막는다.
        val peak = if (plan.dimmed) 0.45f else 1f
        ObjectAnimator.ofFloat(flash, View.ALPHA, peak, 0.2f * peak, peak, 0.2f * peak, peak, 0f).apply {
            duration = if (alert.level == AlertLevel.URGENT) 2_400 else 1_800
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { flash.visibility = View.GONE }
            })
            start()
        }
    }

    private fun describeCabin(c: CabinState): String {
        fun line(label: String, value: Boolean?, okWhen: Boolean, ok: String, bad: String) =
            if (value == null) null else "${if (value == okWhen) "○" else "⚠"} $label ${if (value == okWhen) ok else bad}"
        return listOfNotNull(
            line("벨트", c.driverSeatbeltBuckled, true, "착용", "미착용"),
            line("문", c.doorOpen, false, "닫힘", "열림"),
            line("타이어", c.lowTirePressure, false, "정상", "저압"),
            c.turnSignal?.let {
                when (it) {
                    TurnSignal.NONE -> "○ 방향지시등 꺼짐"
                    TurnSignal.LEFT -> "⇦ 방향지시등 왼쪽"
                    TurnSignal.RIGHT -> "⇨ 방향지시등 오른쪽"
                    TurnSignal.HAZARD -> "⚠ 비상등"
                }
            },
            line("전조등", c.headlightsOn, true, "켜짐", "꺼짐"),
            c.rearSeatOccupied?.let { if (it) "◉ 뒷좌석 탑승" else "○ 뒷좌석 비어 있음" },
        ).joinToString("\n").ifEmpty { "차량 상태 정보 없음" }
    }

    private fun gearKo(g: com.signalassist.core.Gear) = when (g) {
        com.signalassist.core.Gear.PARK -> "P"
        com.signalassist.core.Gear.REVERSE -> "R"
        com.signalassist.core.Gear.NEUTRAL -> "N"
        com.signalassist.core.Gear.DRIVE -> "D"
        com.signalassist.core.Gear.UNKNOWN -> "-"
    }

    private fun applyProfileToUi(p: DriverProfile) {
        val scale = if (p.has(DriverNeed.LOW_VISION) || p.has(DriverNeed.ATTENTION)) 1.4f else 1f
        banner.textSize = 30f * scale
        navCard.textSize = (if (p.navigationCards) 40f else 26f) * scale
        cabinView.textSize = 22f * scale
        flash.textSize = 54f * scale
        panel.visibility = if (p.signalAssist) View.VISIBLE else View.GONE
        preview.visibility = if (p.signalAssist && !drivingRestricted) View.VISIBLE else View.GONE
        rebuildLessons(p)
    }

    /** 알림 배우기 버튼 (주차 중에만 보이는 설정 영역 안) */
    private fun rebuildLessons(p: DriverProfile) {
        if (!::lessonRow.isInitialized) return
        lessonRow.removeAllViews()
        lessonRow.addView(label(18f, Color.LTGRAY).apply { text = "알림 배우기: " })
        for (lesson in AlertGuide.lessonsFor(p)) {
            lessonRow.addView(Button(this).apply {
                text = "${lesson.type.symbol} ${lesson.titleKo}"
                contentDescription = "${lesson.titleKo} 알림 들어보기. ${lesson.explanationKo}"
                setOnClickListener { AssistBus.requests.tryEmit(UserRequest.Lesson(lesson.type)) }
            })
        }
    }

    // ---- UI 구성 ----

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        panel = SignalPanelView(this)
        val side = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 0, 0, 0) }
        preview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        navCard = label(40f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; setBackgroundColor(Color.rgb(20, 30, 45)); setPadding(20, 12, 20, 12) }
        cabinView = label(22f, Color.LTGRAY)
        side.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        side.addView(navCard)
        side.addView(cabinView)
        body.addView(panel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f))
        body.addView(side, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f))
        content.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        banner = label(30f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD }
        status = label(18f, Color.GRAY)
        // 마지막 알림 + 큰 '다시 듣기' 버튼 (주행 중에도 한 번 누르기로 사용 가능, 터치 영역 76dp 이상)
        val bannerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bannerRow.addView(banner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bannerRow.addView(Button(this).apply {
            text = "↻ 다시 듣기"
            textSize = 24f
            contentDescription = "마지막 알림 다시 듣기"
            minimumHeight = dp(76)
            minimumWidth = dp(180)
            setOnClickListener { AssistBus.requests.tryEmit(UserRequest.Replay) }
        })
        content.addView(bannerRow)
        content.addView(status)

        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controls.addView(buildModeRow())
        controls.addView(buildProfileRow())
        speedRow = buildSpeedRow()
        controls.addView(speedRow)
        lessonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controls.addView(HorizontalScrollView(this).apply { addView(lessonRow) })
        content.addView(controls)
        root.addView(content)

        flash = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
        }
        root.addView(flash, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun buildModeRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val spinner = Spinner(this@MainActivity).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modeItems.map { it.label })
            setSelection(modeItems.indexOfFirst { it.mode.key == settings.mode.key }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val m = modeItems[pos].mode
                    if (m.key != settings.mode.key) {
                        settings.mode = m
                        // 시나리오를 고르면 그 시나리오가 보여주는 프로필로 맞춰 줌 (시연 편의)
                        if (m is AssistMode.Simulated) {
                            Scenario.ALL.firstOrNull { it.name == m.scenarioName }?.let { settings.profile = DriverProfile(it.targetNeeds) }
                            syncProfileChecks()
                        }
                        publishConfig()
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) = Unit
            }
        }
        addView(spinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(Button(this@MainActivity).apply {
            text = "다시 시작"
            setOnClickListener { restartToken++; publishConfig() }
        })
    }

    private val profileChecks = HashMap<DriverNeed, CheckBox>()

    private fun buildProfileRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(label(18f, Color.LTGRAY).apply { text = "운전자 특성: " })
        for (need in DriverNeed.entries) {
            val cb = CheckBox(this@MainActivity).apply {
                text = need.labelKo
                setTextColor(Color.WHITE)
                textSize = 18f
                isChecked = settings.profile.has(need)
                setOnCheckedChangeListener { _, _ ->
                    val needs = profileChecks.filter { it.value.isChecked }.keys
                    settings.profile = DriverProfile(needs)
                    publishConfig()
                    requestPermissionsIfNeeded()
                }
            }
            profileChecks[need] = cb
            addView(cb)
        }
    }

    private fun syncProfileChecks() {
        val p = settings.profile
        for ((need, cb) in profileChecks) {
            if (cb.isChecked != p.has(need)) {
                cb.setOnCheckedChangeListener(null)
                cb.isChecked = p.has(need)
                cb.setOnCheckedChangeListener { _, _ ->
                    settings.profile = DriverProfile(profileChecks.filter { it.value.isChecked }.keys)
                    publishConfig()
                }
            }
        }
    }

    /** Pleos 에뮬레이터에는 차속 시뮬레이션이 없어, 실차 모드 시연용 차속 조절 막대를 둔다 */
    private fun buildSpeedRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        val value = label(18f, Color.LTGRAY).apply { text = "시연용 차속: 0 km/h" }
        addView(value)
        addView(SeekBar(this@MainActivity).apply {
            max = 80
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    AssistBus.speedOverrideKmh = p.toFloat()
                    value.text = "시연용 차속: $p km/h"
                }
                override fun onStartTrackingTouch(s: SeekBar?) = Unit
                override fun onStopTrackingTouch(s: SeekBar?) = Unit
            })
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(sizeSp: Float, color: Int) = TextView(this).apply {
        textSize = sizeSp; setTextColor(color); setPadding(0, 6, 0, 6)
    }

    private fun requestPermissionsIfNeeded() {
        val wanted = buildList {
            add("android.car.permission.CAR_SPEED")
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            // 청각장애 프로필: 사이렌 감지용 마이크 (기기 안에서만 분석, 저장·전송 없음)
            if (settings.profile.sirenAlerts) add(Manifest.permission.RECORD_AUDIO)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) requestPermissions(wanted.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // 차속 권한을 받은 뒤 차량 데이터 구독을 다시 등록
        restartToken++
        publishConfig()
    }
}
