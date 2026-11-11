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
import com.signalassist.core.ongoingLabelKo
import com.signalassist.core.mutable
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
    private lateinit var edgeFlash: View
    private lateinit var hazardBar: TextView
    private lateinit var muteButton: Button
    private lateinit var queryButton: Button
    private lateinit var verbosityRow: LinearLayout
    private var lastAlertType: com.signalassist.core.AlertType? = null
    private lateinit var speedRow: LinearLayout
    private lateinit var lessonRow: LinearLayout
    private lateinit var tripView: TextView
    private lateinit var tripRow: LinearLayout
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
        if (!settings.onboarded) startOnboarding()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AssistBus.snapshot.collect { it?.let(::render) } }
                launch { AssistBus.alerts.collect { (alert, plan) -> showAlert(alert, plan) } }
                launch { AssistBus.tripSummary.collect { it?.let(::showTrip) } }
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
        renderHazards(out.activeHazards)
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

    private var flashAnim: android.animation.ValueAnimator? = null

    private fun levelColor(level: AlertLevel) = when (level) {
        AlertLevel.URGENT -> SafePalette.VERMILION
        AlertLevel.WARNING -> Color.rgb(170, 70, 0)
        AlertLevel.CAUTION -> Color.rgb(120, 110, 20)
        AlertLevel.INFO -> Color.rgb(0, 90, 140)
    }

    /**
     * 화면 깜빡임: 위험도에 맞는 방식(FlashPattern)으로.
     * - 안내: 테두리만 한 번 / 주의: 화면 한 번 / 경고: 두 번 / 긴급: 빠르게 다섯 번 (모두 초당 3회 이하)
     * - 큰 한두 단어(headline)를 먼저, 문장은 작게: 한눈에 읽히도록
     * - 야간에는 밝기를 낮춰 눈부심 방지
     */
    private fun showAlert(alert: Alert, plan: OutputPlan) {
        banner.text = "${alert.symbol}  ${alert.message}"
        lastAlertType = alert.type
        muteButton.visibility = if (alert.type.mutable && !drivingRestricted) View.VISIBLE else View.GONE
        if (!plan.visualFlash) return
        val pattern = plan.flashPattern
        val target: View = if (pattern.fullScreen) flash else edgeFlash
        val color = levelColor(alert.level)
        if (pattern.fullScreen) {
            val head = "${alert.symbol} ${alert.headline}"
            flash.text = android.text.SpannableString("$head\n${alert.message}").apply {
                setSpan(android.text.style.RelativeSizeSpan(1.9f), 0, head.length, 0)
            }
            flash.setBackgroundColor(color)
        } else {
            (edgeFlash.background as android.graphics.drawable.GradientDrawable).setStroke(dp(14), color)
        }
        flashAnim?.cancel()
        flash.visibility = View.GONE; edgeFlash.visibility = View.GONE
        target.visibility = View.VISIBLE
        val peak = if (plan.dimmed) 0.45f else 1f
        flashAnim = android.animation.ValueAnimator.ofInt(0, pattern.durationMs).apply {
            duration = pattern.durationMs.toLong()
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { target.alpha = pattern.alphaAt((it.animatedValue as Int).toLong()) * peak }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { target.visibility = View.GONE }
            })
            start()
        }
    }

    /**
     * 계속되는 위험은 깜빡임이 끝나도 화면 위쪽에 남겨 둔다 (도로를 보느라 깜빡임을 놓친 운전자용).
     * 천천히(1초 주기) 밝기가 변해 주변 시야에서도 눈에 띄게 한다.
     */
    private fun renderHazards(hazards: List<com.signalassist.core.AlertType>) {
        if (hazards.isEmpty()) {
            hazardBar.visibility = View.GONE
            hazardPulse?.cancel(); hazardPulse = null
            return
        }
        hazardBar.text = "⚠ " + hazards.joinToString("  ·  ") { it.ongoingLabelKo }
        hazardBar.setBackgroundColor(levelColor(hazards.first().level))
        hazardBar.visibility = View.VISIBLE
        if (hazardPulse == null) {
            hazardPulse = ObjectAnimator.ofFloat(hazardBar, View.ALPHA, 1f, 0.55f).apply {
                duration = 500
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
        }
    }

    private var hazardPulse: ObjectAnimator? = null

    /** 주행이 끝나면(주차) 요약 표시 + 가족에게 공유 */
    private fun showTrip(text: String) {
        tripView.text = "지난 주행\n$text"
        tripRow.visibility = if (drivingRestricted) View.GONE else View.VISIBLE
    }

    private fun shareTrip() {
        val text = settings.trips.firstOrNull() ?: return
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, "[모두의 운전석] 주행 요약\n$text")
        }
        runCatching { startActivity(android.content.Intent.createChooser(send, "주행 요약 공유")) }
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
        if (::queryButton.isInitialized) queryButton.visibility = if (p.signalAssist) View.VISIBLE else View.GONE
        preview.visibility = if (p.signalAssist && !drivingRestricted) View.VISIBLE else View.GONE
        rebuildLessons(p)
        rebuildVerbosity(p)
    }

    /** 알림 양 (위험할 때만 / 보통 / 자세히) + 끈 알림 다시 켜기 */
    private fun rebuildVerbosity(p: DriverProfile) {
        if (!::verbosityRow.isInitialized) return
        verbosityRow.removeAllViews()
        verbosityRow.addView(label(18f, Color.LTGRAY).apply { text = "알림 양: " })
        for (v in com.signalassist.core.Verbosity.entries) {
            val selected = p.prefs.verbosity == v
            verbosityRow.addView(Button(this).apply {
                text = (if (selected) "✓ " else "") + v.labelKo
                minimumHeight = dp(76)
                contentDescription = "알림 양 ${v.labelKo}: ${v.descriptionKo}" + if (selected) ", 선택됨" else ""
                setOnClickListener {
                    settings.profile = settings.profile.copy(prefs = settings.profile.prefs.copy(verbosity = v))
                    publishConfig()
                }
            })
        }
        verbosityRow.addView(label(16f, Color.GRAY).apply { text = "  " + p.prefs.verbosity.descriptionKo })
        if (p.prefs.muted.isNotEmpty()) {
            verbosityRow.addView(Button(this).apply {
                text = "끈 알림 다시 켜기 (${p.prefs.muted.size})"
                contentDescription = "끈 알림: " + p.prefs.muted.joinToString { it.headlineKo }
                setOnClickListener {
                    settings.profile = settings.profile.copy(prefs = settings.profile.prefs.copy(muted = emptySet()))
                    publishConfig()
                }
            })
        }
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
        hazardBar = label(28f, Color.WHITE).apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(16, 10, 16, 10)
            visibility = View.GONE
            contentDescription = "진행 중인 위험"
        }
        content.addView(hazardBar)
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
        // 주차 중에만: 방금 알림이 필요 없으면 끄기 (안전에 중요한 알림은 버튼이 나오지 않음)
        muteButton = Button(this).apply {
            text = "이 알림 끄기"
            visibility = View.GONE
            minimumHeight = dp(76)
            setOnClickListener {
                val t = lastAlertType ?: return@setOnClickListener
                val p = settings.profile
                settings.profile = p.copy(prefs = p.prefs.copy(muted = p.prefs.muted + t))
                publishConfig()
                visibility = View.GONE
                android.widget.Toast.makeText(this@MainActivity, "'${t.headlineKo}' 알림을 껐습니다. 설정에서 다시 켤 수 있습니다.", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        bannerRow.addView(muteButton)
        // 교차로에서 헷갈릴 때: 현재 신호와 확신 정도를 말해 줌 (주행 중에도 한 번 누르기)
        queryButton = Button(this).apply {
            text = "? 지금 신호"
            textSize = 24f
            contentDescription = "지금 신호가 무엇인지 묻기"
            minimumHeight = dp(76)
            minimumWidth = dp(180)
            setOnClickListener { AssistBus.requests.tryEmit(UserRequest.QuerySignal) }
        }
        bannerRow.addView(queryButton)
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
        verbosityRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controls.addView(verbosityRow)
        speedRow = buildSpeedRow()
        controls.addView(speedRow)
        tripView = label(20f, Color.WHITE)
        tripRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            addView(tripView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "가족에게 공유"
                contentDescription = "지난 주행 요약을 가족에게 공유"
                setOnClickListener { shareTrip() }
            })
        }
        controls.addView(tripRow)
        settings.trips.firstOrNull()?.let { tripView.text = "지난 주행\n$it"; tripRow.visibility = View.VISIBLE }
        lessonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controls.addView(HorizontalScrollView(this).apply { addView(lessonRow) })
        content.addView(controls)
        root.addView(content)

        flash = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            visibility = View.GONE
            // 같은 내용을 음성 알림이 이미 전달하므로 화면 낭독기가 중복으로 읽지 않게
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(flash, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // 안내(INFO)용: 화면을 덮지 않고 테두리만 빛남
        edgeFlash = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.TRANSPARENT); setStroke(dp(14), SafePalette.SKY_BLUE)
            }
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(edgeFlash, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
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
                            Scenario.ALL.firstOrNull { it.name == m.scenarioName }?.let { settings.profile = settings.profile.copy(needs = it.targetNeeds) }
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
            minimumHeight = dp(76)
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
                textSize = 20f
                minimumHeight = dp(76) // 차량 화면 권장 터치 영역
                contentDescription = "${need.labelKo}: ${need.descriptionKo}"
                isChecked = settings.profile.has(need)
                setOnCheckedChangeListener { _, _ ->
                    val needs = profileChecks.filter { it.value.isChecked }.keys
                    settings.profile = settings.profile.copy(needs = needs)
                    publishConfig()
                    requestPermissionsIfNeeded()
                }
            }
            profileChecks[need] = cb
            addView(cb)
        }
        addView(Button(this@MainActivity).apply {
            text = "처음 설정 다시 하기"
            minimumHeight = dp(76)
            contentDescription = "질문과 소리 테스트로 알림을 다시 맞추기"
            setOnClickListener { startOnboarding() }
        })
    }

    // ---- 첫 설정: 상황 질문 4개 + 소리 테스트 3개 (주차 중에만) ----

    private val onboardingTone by lazy { com.signalassist.app.alert.TonePlayer() }

    private fun startOnboarding() {
        val questions = com.signalassist.core.OnboardingQuestion.entries
        val answers = LinkedHashMap<com.signalassist.core.OnboardingQuestion, Boolean>()
        fun ask(i: Int) {
            if (i == questions.size) { hearingCheck(answers); return }
            val q = questions[i]
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("처음 설정 ${i + 1} / ${questions.size + 1}")
                .setMessage(q.textKo)
                .setPositiveButton("예") { _, _ -> answers[q] = true; ask(i + 1) }
                .setNegativeButton("아니요") { _, _ -> answers[q] = false; ask(i + 1) }
                .setCancelable(false)
                .show()
        }
        ask(0)
    }

    /** 실제 차 스피커로 소리 3가지를 들려주고 들렸는지 묻는다 */
    private fun hearingCheck(answers: Map<com.signalassist.core.OnboardingQuestion, Boolean>) {
        val probes = com.signalassist.core.HearingProbe.entries
        val heard = LinkedHashMap<com.signalassist.core.HearingProbe, Boolean>()
        fun probe(i: Int) {
            if (i == probes.size) { finishOnboarding(answers, heard); return }
            val p = probes[i]
            onboardingTone.play(
                com.signalassist.core.TonePattern.DOUBLE_MID,
                OutputPlan(tone = true, voice = false, visualFlash = false, largeText = false,
                    toneGain = com.signalassist.core.ToneLibrary.gainFor(p.level), lowPitchTones = p.lowPitch),
            )
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("소리 테스트 ${i + 1} / ${probes.size}")
                .setMessage("방금 ${p.textKo}이 들리셨나요?\n(평소 운전할 때의 음량으로 맞춰 두고 들어 보세요)")
                .setPositiveButton("들렸어요") { _, _ -> heard[p] = true; probe(i + 1) }
                .setNegativeButton("안 들렸어요") { _, _ -> heard[p] = false; probe(i + 1) }
                .setNeutralButton("다시 듣기") { _, _ -> probe(i) }
                .setCancelable(false)
                .show()
        }
        probe(0)
    }

    private fun finishOnboarding(
        answers: Map<com.signalassist.core.OnboardingQuestion, Boolean>,
        heard: Map<com.signalassist.core.HearingProbe, Boolean>,
    ) {
        val p = com.signalassist.core.Onboarding.build(answers, heard)
        settings.profile = p
        settings.onboarded = true
        syncProfileChecks()
        publishConfig()
        requestPermissionsIfNeeded()
        val parts = buildList {
            if (p.needs.isEmpty()) add("기본 안전 알림(뒷좌석 확인)만 사용") else add(p.needs.joinToString(" · ") { it.labelKo })
            if (p.prefs.lowPitch == true) add("낮은 알림음")
            if (p.prefs.volumeBoost > 1f) add("알림음 크게")
            if (p.prefs.soundForHearing) add("화면 깜빡임 + 알림음 함께")
            else if (p.has(DriverNeed.HEARING)) add("소리 없이 화면으로")
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("설정 완료")
            .setMessage(parts.joinToString("\n") + "\n\n아래 '알림 배우기'에서 각 알림의 소리를 미리 들어 보세요." +
                if (p.signalAssist) "\n교차로에서 헷갈리면 '? 지금 신호' 버튼을 누르세요." else "")
            .setPositiveButton("확인", null)
            .show()
    }

    private fun syncProfileChecks() {
        val p = settings.profile
        for ((need, cb) in profileChecks) {
            if (cb.isChecked != p.has(need)) {
                cb.setOnCheckedChangeListener(null)
                cb.isChecked = p.has(need)
                cb.setOnCheckedChangeListener { _, _ ->
                    settings.profile = settings.profile.copy(needs = profileChecks.filter { it.value.isChecked }.keys)
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
