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
import android.widget.ScrollView
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AlertDialog
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
            ModeItem(AssistMode.LiveNoCamera, "차량 보조 · 신호·내비 미연결"),
            ModeItem(AssistMode.LiveSyntheticSignal, "체험 · 차량 정보 + 가상 신호"),
            ModeItem(AssistMode.LiveTestImages, "체험 · 차량 정보 + 테스트 이미지"),
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
    private var drivingRestricted = true
    private var platformRestricted = true
    private var selectedPage = 0
    private var onboardingDialog: AlertDialog? = null
    private var onboardingPromptShown = false
    private lateinit var modeBadge: TextView
    private lateinit var headline: TextView
    private lateinit var replayButton: Button
    private lateinit var homePage: View
    private lateinit var settingsPage: View
    private lateinit var demoPage: View
    private lateinit var tabs: LinearLayout
    private lateinit var connectionView: TextView
    private lateinit var previewCard: LinearLayout
    private lateinit var previewHint: TextView
    private var hasPreview = false
    private lateinit var modeSpinner: Spinner
    private var lastAlertAt = 0L
    private val backgroundColor = Color.rgb(11, 18, 29)
    private val cardColor = Color.rgb(22, 34, 50)
    private val mutedColor = Color.rgb(178, 194, 211)
    private val accentColor = Color.rgb(114, 210, 223)
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
        if (!packageManager.hasSystemFeature("android.hardware.type.automotive")) {
            platformRestricted = false
            updateRestrictions()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { AssistBus.snapshot.collect { it?.let(::render) } }
                launch { AssistBus.alerts.collect { (alert, plan) -> showAlert(alert, plan) } }
                launch { AssistBus.tripSummary.collect { it?.let(::showTrip) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AssistBus.uiVisible = true
    }

    override fun onPause() {
        AssistBus.uiVisible = false
        super.onPause()
    }

    override fun onDestroy() {
        onboardingDialog?.dismiss()
        onboardingTone.stop()
        flashAnim?.cancel()
        hazardPulse?.cancel()
        runCatching { uxManager?.unregisterListener() }
        car?.disconnect()
        super.onDestroy()
    }

    private fun publishConfig() {
        AssistBus.config.value = AssistConfig(settings.mode, settings.profile, restartToken)
        applyProfileToUi(settings.profile)
        if (::modeSpinner.isInitialized) {
            val index = modeItems.indexOfFirst { it.mode.key == settings.mode.key }.coerceAtLeast(0)
            if (modeSpinner.selectedItemPosition != index) modeSpinner.setSelection(index)
        }
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
                Car.createCar(this, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready -> if (ready) onReady(c) else runOnUiThread {
                    platformRestricted = true; updateRestrictions()
                } }
            } else {
                Car.createCar(this)?.also(onReady)
            }
        }.getOrNull()
    }

    /** 주행 중에는 설정·영상 미리보기를 숨기고 큰 상태 표시만 남긴다 */
    private fun applyRestrictions(r: CarUxRestrictions) {
        platformRestricted = r.isRequiresDistractionOptimization
        updateRestrictions()
    }

    private fun updateRestrictions() {
        val snap = AssistBus.snapshot.value
        val moving = !settings.mode.isDemo && snap?.output?.input?.vehicle?.let {
            it.hasValidSpeed && it.speedKmh > 0f
        } == true
        drivingRestricted = platformRestricted || moving
        if (drivingRestricted) {
            selectedPage = 0
            onboardingDialog?.dismiss()
            onboardingTone.stop()
            onboardingDialog = null
            muteButton.visibility = View.GONE
        }
        showPage(selectedPage)
        if (!drivingRestricted && !settings.onboarded && !onboardingPromptShown) {
            onboardingPromptShown = true
            startOnboarding()
        }
    }

    private fun showPage(page: Int) {
        selectedPage = if (drivingRestricted) 0 else page
        homePage.visibility = if (selectedPage == 0) View.VISIBLE else View.GONE
        settingsPage.visibility = if (selectedPage == 1) View.VISIBLE else View.GONE
        demoPage.visibility = if (selectedPage == 2) View.VISIBLE else View.GONE
        tabs.visibility = if (drivingRestricted) View.GONE else View.VISIBLE
        for (i in 0 until tabs.childCount) {
            (tabs.getChildAt(i) as Button).apply {
                isSelected = i == selectedPage
                setTextColor(if (isSelected) backgroundColor else Color.WHITE)
                background = rounded(if (isSelected) accentColor else cardColor)
            }
        }
        preview.visibility = if (selectedPage == 2 && hasPreview) View.VISIBLE else View.GONE
    }

    // ---- 표시 ----
    private fun render(s: UiSnapshot) {
        val out = s.output
        panel.state = out.signalState
        hasPreview = s.preview != null
        previewHint.text = when {
            !settings.mode.isDemo -> "위에서 체험 시나리오를 선택하세요."
            !settings.profile.signalAssist -> "이 체험에서는 차량·소리 알림을 확인합니다."
            !hasPreview -> "체험 영상 입력을 기다리고 있습니다."
            else -> "체험용 합성 영상"
        }
        updateRestrictions()
        renderHazards(out.activeHazards)
        if (selectedPage == 2 && s.preview != null) preview.setImageBitmap(s.preview)
        val nav = out.input.navigation
        navCard.text = when {
            !settings.mode.isDemo -> "내비게이션 미연결\n경로 안내를 사용할 수 없습니다"
            nav?.guiding == true && nav.distanceToManeuverM != null && nav.nextManeuver != null ->
                "${nav.nextManeuver!!.symbol} ${Math.round(nav.distanceToManeuverM!! / 10f) * 10}m · ${nav.nextManeuver!!.labelKo}" +
                    (nav.roadName?.let { "\n$it" } ?: "")
            nav == null -> "체험 내비 정보 없음"
            else -> "목적지를 설정하면 경로를 표시합니다"
        }
        cabinView.text = describeCabin(out.input.cabin)
        val v = out.input.vehicle
        val speed = if (v.hasValidSpeed) "%.0f km/h".format(v.speedKmh) else "차속 확인 불가"
        val signal = when {
            !settings.profile.signalAssist -> "신호 보조 꺼짐"
            !settings.mode.isDemo -> "신호 인식 미연결"
            out.signal == null -> "신호 확인 불가"
            else -> "신호 체험 중"
        }
        val mic = when {
            !settings.profile.sirenAlerts -> "사이렌 감지 꺼짐"
            out.input.sirenNearby == null -> "마이크 사용 불가"
            settings.mode is AssistMode.Simulated -> "사이렌 체험 중"
            else -> "마이크 감지 중"
        }
        status.text = "$speed · 기어 ${gearKo(v.gear)} · $signal"
        connectionView.text = "$signal\n" +
            (if (settings.mode.isDemo) "체험 입력 사용 중" else "내비게이션 미연결") + "\n$mic\n" +
            (if (s.hasRealSpeed && !settings.mode.isDemo) "차속 수신 중" else if (settings.mode.isDemo) "체험용 차속 사용 가능" else "차속 미수신 또는 연결 끊김")
        modeBadge.text = if (settings.mode.isDemo) "체험 모드 · 가상 정보 포함" else "차량 보조 · 지원 상태 확인"
        modeBadge.setTextColor(if (settings.mode.isDemo) SafePalette.AMBER else accentColor)
        speedRow.visibility = if (settings.mode.isDemo && settings.mode !is AssistMode.Simulated && !drivingRestricted) View.VISIBLE else View.GONE
        panel.visibility = if (settings.profile.signalAssist && settings.mode.isDemo) View.VISIBLE else View.GONE
        queryButton.visibility = if (settings.profile.signalAssist) View.VISIBLE else View.GONE
        if (lastAlertAt == 0L || android.os.SystemClock.elapsedRealtime() - lastAlertAt > 8_000) {
            lastAlertType = null
            muteButton.visibility = View.GONE
            headline.text = when {
                out.activeHazards.isNotEmpty() -> out.activeHazards.first().headlineKo
                !v.hasValidSpeed && !settings.mode.isDemo -> "차량 연결을 확인하세요"
                settings.mode.isDemo -> "알림 체험 중"
                else -> "차량 보조 사용 중"
            }
            banner.text = if (settings.mode.isDemo) "가상 신호입니다. 실제 주행 판단에 사용하지 마세요." else "신호 인식·내비 안내는 연결되지 않았습니다."
        }
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
        lastAlertAt = android.os.SystemClock.elapsedRealtime()
        headline.text = "${alert.symbol} ${alert.headline}"
        banner.text = alert.message
        lastAlertType = alert.type
        muteButton.visibility = if (alert.type.mutable && !drivingRestricted) View.VISIBLE else View.GONE
        if (!plan.visualFlash) return
        val pattern = plan.flashPattern
        val fullScreen = settings.fullScreenFlash && pattern.fullScreen
        val target: View = if (fullScreen) flash else edgeFlash
        val color = levelColor(alert.level)
        if (fullScreen) {
            val head = "${alert.symbol} ${alert.headline}"
            flash.textSize = 30f
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
        hazardBar.text = "⚠ " + hazards.first().ongoingLabelKo + if (hazards.size > 1) " · 외 ${hazards.size - 1}건" else ""
        hazardBar.contentDescription = hazards.joinToString(". ") { it.ongoingLabelKo }
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
        val scale = if (settings.largeText || p.has(DriverNeed.LOW_VISION) || p.has(DriverNeed.ATTENTION)) 1.25f else 1f
        headline.textSize = 32f * scale
        banner.textSize = 20f * scale
        navCard.textSize = 26f * scale
        navCard.visibility = if (p.navigationCards) View.VISIBLE else View.GONE
        replayButton.text = if (p.has(DriverNeed.HEARING)) "알림 다시 보기" else "알림 다시 확인"
        cabinView.textSize = 22f * scale
        flash.textSize = 54f * scale
        panel.visibility = if (p.signalAssist && settings.mode.isDemo) View.VISIBLE else View.GONE
        queryButton.isEnabled = settings.mode.isDemo
        queryButton.alpha = if (queryButton.isEnabled) 1f else 0.45f
        if (::queryButton.isInitialized) queryButton.visibility = if (p.signalAssist) View.VISIBLE else View.GONE
        preview.visibility = if (selectedPage == 2 && hasPreview && !drivingRestricted) View.VISIBLE else View.GONE
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
                textSize = 20f
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
        lessonRow.addView(label(18f, Color.LTGRAY).apply { text = "알림 배우기" })
        for (lesson in AlertGuide.lessonsFor(p)) {
            lessonRow.addView(Button(this).apply {
                text = "${lesson.type.symbol} ${lesson.titleKo}"
                minimumHeight = dp(76)
                textSize = 20f
                contentDescription = "${lesson.titleKo} 알림 들어보기. ${lesson.explanationKo}"
                setOnClickListener { if (!drivingRestricted) AssistBus.requests.tryEmit(UserRequest.Lesson(lesson.type)) }
            })
        }
    }

    // ---- UI 구성 ----

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(18).toFloat()
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun card() = column().apply {
        background = rounded(cardColor)
        setPadding(dp(20), dp(16), dp(20), dp(16))
    }
    private fun addSpace(parent: LinearLayout, height: Int = 12) {
        parent.addView(View(this), LinearLayout.LayoutParams(1, dp(height)))
    }
    private fun action(title: String, click: () -> Unit) = Button(this).apply {
        text = title; textSize = 20f; isAllCaps = false
        minimumHeight = dp(76); setTextColor(Color.WHITE)
        background = rounded(cardColor)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { click() }
    }
    private fun scroll(content: View) = ScrollView(this).apply {
        isFillViewport = true; clipToPadding = false
        addView(content, FrameLayout.LayoutParams(-1, -2))
    }
    private fun resetDisplayedAlert() {
        lastAlertAt = 0L; lastAlertType = null
        headline.text = "입력 연결 확인 중"
        banner.text = "연결 상태를 확인하고 있습니다."
        hasPreview = false
        preview.setImageDrawable(null)
        panel.state = com.signalassist.core.SignalState.UNKNOWN
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(backgroundColor) }
        val shell = column().apply { setPadding(dp(20), dp(12), dp(20), dp(12)) }
        modeBadge = label(16f, accentColor).apply { typeface = Typeface.DEFAULT_BOLD }
        modeBadge.text = if (settings.mode.isDemo) "체험 모드 · 가상 정보 포함" else "차량 보조 · 지원 상태 확인"
        shell.addView(modeBadge)
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("주행", "설정", "체험").forEachIndexed { index, title ->
            tabs.addView(action(title) { showPage(index) }, LinearLayout.LayoutParams(0, dp(60), 1f).apply {
                setMargins(dp(3), dp(6), dp(3), dp(10))
            })
        }
        shell.addView(tabs)
        val pages = FrameLayout(this)
        shell.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))
        val home = column()
        hazardBar = label(24f, Color.WHITE).apply {
            gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(12), dp(12), dp(12)); visibility = View.GONE
        }
        home.addView(hazardBar); addSpace(home)
        val focus = card()
        headline = label(32f, Color.WHITE).apply { typeface = Typeface.DEFAULT_BOLD; text = "차량 연결을 확인하세요" }
        banner = label(20f, mutedColor).apply { text = "사용 가능한 기능을 확인하고 있습니다." }
        focus.addView(headline); focus.addView(banner)
        panel = SignalPanelView(this)
        focus.addView(panel, LinearLayout.LayoutParams(-1, dp(180)))
        navCard = label(26f, Color.WHITE)
        focus.addView(navCard)
        home.addView(focus); addSpace(home)
        val buttons = LinearLayout(this).apply {
            orientation = if (resources.configuration.screenWidthDp < 600 || resources.configuration.fontScale > 1.2f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        queryButton = action("지금 신호 확인") { AssistBus.requests.tryEmit(UserRequest.QuerySignal) }
        replayButton = action("알림 다시 확인") { AssistBus.requests.tryEmit(UserRequest.Replay) }
        listOf(queryButton, replayButton).forEach { b ->
            buttons.addView(b, if (buttons.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) }
            else LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        status = label(16f, mutedColor)
        val homeContainer = column()
        homeContainer.addView(scroll(home), LinearLayout.LayoutParams(-1, 0, 1f))
        homeContainer.addView(buttons)
        homeContainer.addView(status)
        homePage = homeContainer
        pages.addView(homePage, FrameLayout.LayoutParams(-1, -1))

        controls = column()
        controls.addView(label(26f, Color.WHITE).apply { text = "내게 맞는 알림"; typeface = Typeface.DEFAULT_BOLD })
        controls.addView(label(17f, mutedColor).apply { text = "주차 중에 설정할 수 있습니다. 필요한 도움을 선택하세요." })
        controls.addView(buildProfileRow()); addSpace(controls)
        val appearance = card()
        appearance.addView(label(22f, Color.WHITE).apply { text = "보기 편하게" })
        appearance.addView(CheckBox(this).apply {
            text = "글씨 더 크게"; textSize = 20f; minimumHeight = dp(76); setTextColor(Color.WHITE)
            isChecked = settings.largeText
            setOnCheckedChangeListener { _, checked -> settings.largeText = checked; applyProfileToUi(settings.profile) }
        })
        appearance.addView(CheckBox(this).apply {
            text = "전체 화면 강조 사용"; textSize = 20f; minimumHeight = dp(76); setTextColor(Color.WHITE)
            isChecked = settings.fullScreenFlash
            setOnCheckedChangeListener { _, checked -> settings.fullScreenFlash = checked }
        })
        appearance.addView(label(17f, mutedColor).apply { text = "기본은 테두리 강조입니다. 전체 화면 강조는 알림 배우기에서 먼저 확인하세요." })
        appearance.addView(CheckBox(this).apply {
            text = "내비와 함께 쓰기"; textSize = 20f; minimumHeight = dp(76); setTextColor(Color.WHITE)
            isChecked = settings.navCompanion
            setOnCheckedChangeListener { _, checked -> settings.navCompanion = checked; AssistService.refreshStatus(this@MainActivity) }
        })
        appearance.addView(label(17f, mutedColor).apply { text = "시동을 걸면 자동으로 켜지고, 내비를 보는 동안 알림이 내비 위 카드로 뜹니다. 카드에서 '다시 듣기'·'지금 신호'를 누를 수 있습니다." })
        controls.addView(appearance); addSpace(controls)
        verbosityRow = column(); controls.addView(verbosityRow)
        muteButton = action("최근 알림 끄기") {
            if (!drivingRestricted) lastAlertType?.takeIf { it.mutable }?.let { type ->
                val p = settings.profile
                settings.profile = p.copy(prefs = p.prefs.copy(muted = p.prefs.muted + type))
                publishConfig(); muteButton.visibility = View.GONE
            }
        }.apply { visibility = View.GONE }
        controls.addView(muteButton); addSpace(controls)
        lessonRow = column(); controls.addView(lessonRow)
        tripView = label(18f, Color.WHITE)
        tripRow = column().apply {
            visibility = View.GONE; addView(tripView)
            addView(action("주행 요약 공유") { if (!drivingRestricted) shareTrip() })
        }
        settings.trips.firstOrNull()?.let { tripView.text = "지난 주행\n$it"; tripRow.visibility = View.VISIBLE }
        controls.addView(tripRow); addSpace(controls)
        connectionView = label(18f, mutedColor)
        controls.addView(label(22f, Color.WHITE).apply { text = "기능 연결 상태" })
        controls.addView(connectionView)
        cabinView = label(18f, mutedColor); controls.addView(cabinView)
        settingsPage = scroll(controls); pages.addView(settingsPage)

        val demo = column()
        demo.addView(label(26f, Color.WHITE).apply { text = "알림 체험"; typeface = Typeface.DEFAULT_BOLD })
        demo.addView(label(18f, SafePalette.AMBER).apply { text = "가상 신호와 시나리오입니다. 실제 주행 판단에 사용하지 마세요." })
        demo.addView(buildModeRow()); addSpace(demo)
        demo.addView(action("체험 종료 · 차량 보조로") {
            if (!drivingRestricted) {
                settings.mode = AssistMode.LiveNoCamera
                restartToken++; resetDisplayedAlert(); publishConfig(); showPage(0)
            }
        })
        speedRow = buildSpeedRow(); demo.addView(speedRow)
        preview = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "체험용 합성 영상" }
        addSpace(demo)
        previewHint = label(18f, mutedColor).apply { text = "위에서 체험 시나리오를 선택하세요." }
        previewCard = card()
        previewCard.addView(previewHint)
        previewCard.addView(preview, LinearLayout.LayoutParams(-1, dp(220)))
        demo.addView(previewCard)
        demoPage = scroll(demo); pages.addView(demoPage)
        root.addView(shell)
        flash = TextView(this).apply {
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 32f
            typeface = Typeface.DEFAULT_BOLD; setPadding(dp(28), dp(28), dp(28), dp(28))
            visibility = View.GONE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(flash, FrameLayout.LayoutParams(-1, -1))
        edgeFlash = View(this).apply {
            background = GradientDrawable().apply { setColor(Color.TRANSPARENT); setStroke(dp(8), accentColor) }
            visibility = View.GONE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(edgeFlash, FrameLayout.LayoutParams(-1, -1))
        showPage(0)
        return root
    }

    private fun buildModeRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val spinner = Spinner(this@MainActivity).apply {
            modeSpinner = this
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modeItems.map { it.label })
            setSelection(modeItems.indexOfFirst { it.mode.key == settings.mode.key }.coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val m = modeItems[pos].mode
                    if (m.key != settings.mode.key && !drivingRestricted) {
                        settings.mode = m
                        // 시나리오를 고르면 그 시나리오가 보여주는 프로필로 맞춰 줌 (시연 편의)
                        if (m is AssistMode.Simulated) {
                            Scenario.ALL.firstOrNull { it.name == m.scenarioName }?.let { settings.profile = settings.profile.copy(needs = it.targetNeeds) }
                            syncProfileChecks()
                        }
                        resetDisplayedAlert()
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
            setOnClickListener { if (!drivingRestricted) { restartToken++; resetDisplayedAlert(); publishConfig() } }
        })
    }

    private val profileChecks = HashMap<DriverNeed, CheckBox>()

    private fun buildProfileRow() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(18f, Color.LTGRAY).apply { text = "필요한 도움" })
        for (need in DriverNeed.entries) {
            val cb = CheckBox(this@MainActivity).apply {
                text = when (need) {
                    DriverNeed.COLOR_VISION -> "신호 색을 소리·모양으로 확인"
                    DriverNeed.HEARING -> "소리 알림을 화면으로 확인"
                    DriverNeed.LOW_VISION -> "작은 정보를 크게 보고 음성으로 듣기"
                    DriverNeed.ATTENTION -> "놓치기 쉬운 정보를 한 번 더 확인"
                }
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
            text = "내게 맞는 알림 설정"
            minimumHeight = dp(76)
            contentDescription = "질문과 소리 테스트로 알림을 다시 맞추기"
            setOnClickListener { startOnboarding() }
        })
    }

    // ---- 첫 설정: 상황 질문 4개 + 소리 테스트 3개 (주차 중에만) ----

    private val onboardingTone = com.signalassist.app.alert.TonePlayer()

    private fun startOnboarding() {
        if (drivingRestricted || onboardingDialog?.isShowing == true) return
        val questions = com.signalassist.core.OnboardingQuestion.entries
        val answers = LinkedHashMap<com.signalassist.core.OnboardingQuestion, Boolean>()
        fun ask(i: Int) {
            if (drivingRestricted || isFinishing) return
            if (i == questions.size) { hearingCheck(answers); return }
            val q = questions[i]
            onboardingDialog = androidx.appcompat.app.AlertDialog.Builder(this)
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
            if (drivingRestricted || isFinishing) return
            if (i == probes.size) { finishOnboarding(answers, heard); return }
            val p = probes[i]
            onboardingTone.play(
                com.signalassist.core.TonePattern.DOUBLE_MID,
                OutputPlan(tone = true, voice = false, visualFlash = false, largeText = false,
                    toneGain = com.signalassist.core.ToneLibrary.gainFor(p.level), lowPitchTones = p.lowPitch),
            )
            onboardingDialog = androidx.appcompat.app.AlertDialog.Builder(this)
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
        if (drivingRestricted) return
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
        onboardingDialog = androidx.appcompat.app.AlertDialog.Builder(this)
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
                    requestPermissionsIfNeeded()
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
                    if (!fromUser || drivingRestricted || !settings.mode.isDemo) return
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
        textSize = sizeSp; setTextColor(color); setPadding(0, dp(6), 0, dp(6))
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
