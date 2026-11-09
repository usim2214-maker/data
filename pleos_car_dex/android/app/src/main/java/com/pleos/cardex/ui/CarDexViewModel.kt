package com.pleos.cardex.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pleos.cardex.BuildConfig
import com.pleos.cardex.data.CarDexRepository
import com.pleos.cardex.data.Challenge
import com.pleos.cardex.data.CatalogVehicle
import com.pleos.cardex.data.CollectionEntry
import com.pleos.cardex.data.HomeSummary
import com.pleos.cardex.data.DecorCatalog
import com.pleos.cardex.data.MAX_PROPS
import com.pleos.cardex.data.PlacedProp
import com.pleos.cardex.data.Leaderboard
import com.pleos.cardex.data.MastersResponse
import com.pleos.cardex.data.DriveCourse
import com.pleos.cardex.data.GeoPoint
import com.pleos.cardex.data.LandmarkNearby
import com.pleos.cardex.data.Preferences
import com.pleos.cardex.data.RecognitionResponse
import com.pleos.cardex.data.Showroom
import com.pleos.cardex.data.ShowroomUpdate
import com.pleos.cardex.data.StampBook
import com.pleos.cardex.data.SynergyScore
import com.pleos.cardex.pleos.AssetFrameSource
import com.pleos.cardex.pleos.DrivingSnapshot
import com.pleos.cardex.pleos.DrivingRestriction
import com.pleos.cardex.pleos.FrameSource
import com.pleos.cardex.pleos.PleosAi
import com.pleos.cardex.pleos.VoiceCommand
import com.pleos.cardex.pleos.GearMonitor
import com.pleos.cardex.pleos.VoiceGuide
import com.pleos.cardex.pleos.LandmarkTarget
import com.pleos.cardex.pleos.PleosNavi
import com.pleos.cardex.pleos.VehicleContextProvider
import com.pleos.cardex.pleos.MockVehicleContextProvider
import com.pleos.cardex.pleos.PleosVehicleContextProvider
import com.pleos.cardex.pleos.VideoFrameSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class UiState(
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val lastResult: RecognitionResponse? = null,
    /** 마지막으로 분석한 프레임 (수집 탭 미리보기 + 감지 박스) */
    val lastFrameJpeg: ByteArray? = null,
    val collection: List<CollectionEntry> = emptyList(),
    val catalog: List<CatalogVehicle> = emptyList(),
    val synergy: SynergyScore? = null,
    val showroom: Showroom = Showroom(),
    val decor: DecorCatalog? = null,
    val stampBook: StampBook? = null,
    /** 새 스탬프 획득 등 잠깐 띄울 알림 */
    val notice: String? = null,
    val keywords: List<String> = emptyList(),
    val myCarId: String? = null,
    val fields: List<String> = com.pleos.cardex.ui.screens.DEFAULT_FIELDS,
    val driving: DrivingSnapshot? = null,
    val demoVideo: Uri? = null,
    val landmarks: List<LandmarkNearby> = emptyList(),
    val course: DriveCourse? = null,
    val courseLoading: Boolean = false,
    /** 코스를 고른 주체: "Pleos LLM" | "서버" */
    val courseBy: String = "",
    /** 아이용 차 맞히기 퀴즈 */
    val quiz: com.pleos.cardex.data.QuizQuestion? = null,
    val quizFrameJpeg: ByteArray? = null,
    val quizPicked: String? = null, // null = 답 기다리는 중
    val quizStars: Int = 0,
    val quizLoading: Boolean = false,
    val quizMessage: String? = null,
    /** 음성 명령 듣는 중 / 마지막으로 들은 말 */
    val listening: Boolean = false,
    val heard: String? = null,
    val aiStatus: String = "",
    val voiceStatus: String = "",
    val challenge: Challenge? = null,
    val weekly: Leaderboard? = null,
    val rankingRegionId: String? = null,
    val masters: MastersResponse? = null,
    /** 주행 중이면 조작 화면을 잠그고 '주행 모드'만 보여준다 */
    val isDriving: Boolean = false,
    val drivingSource: String = "",
    val speedKmh: Float? = null,
    val voiceOn: Boolean = true,
    val home: HomeSummary? = null,
    /** 서버에 연결할 수 없음 -> 상단 배너 + 다시 시도 */
    val serverDown: Boolean = false,
    /** 큰 성취 축하 연출 (코스 완주·한정 배지·지역 마스터) */
    val celebration: Celebration? = null,
    val onboardingDone: Boolean = true,
    val error: String? = null,
)

data class Celebration(val emoji: String, val title: String, val subtitle: String)

class CarDexViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("cardex", android.content.Context.MODE_PRIVATE)

    /** 네트워크/서버 오류를 사용자 문구로 바꾸고 서버 연결 상태를 갱신 */
    private suspend fun <T> call(block: suspend () -> T): Result<T> = runCatching { block() }
        .onSuccess { if (_state.value.serverDown) _state.update { it.copy(serverDown = false) } }
        .recoverCatching { e -> throw Exception(friendly(e), e) }

    private fun friendly(e: Throwable): String = when (e) {
        is java.net.ConnectException, is java.net.UnknownHostException, is java.net.SocketTimeoutException -> {
            _state.update { it.copy(serverDown = true) }
            "서버에 연결할 수 없어요. 백엔드가 켜져 있는지 확인해 주세요."
        }
        is retrofit2.HttpException -> runCatching {
            val body = e.response()?.errorBody()?.string().orEmpty()
            kotlinx.serialization.json.Json.parseToJsonElement(body)
                .let { (it as kotlinx.serialization.json.JsonObject)["detail"].toString().trim('"') }
        }.getOrDefault("요청을 처리하지 못했어요 (${e.code()})")
        else -> e.message ?: "알 수 없는 오류"
    }

    /** 모든 화면 데이터 다시 불러오기 (다시 시도 버튼, 시연 데이터 채우기 후) */
    fun refreshAll() {
        refreshCollection(); loadShowroom(); loadStamps(); loadChallenge(); loadRankings(); loadHome()
        lastLandmarkFetch = null
    }

    fun loadHome() {
        viewModelScope.launch { call { repo.home() }.onSuccess { h -> _state.update { it.copy(home = h) } } }
    }

    fun seedDemo() {
        viewModelScope.launch {
            call { repo.demoSeed() }
                .onSuccess { _state.update { it.copy(notice = "시연 데이터를 채웠어요 (차량 4 · 스탬프 3 · 히든 2)") }; refreshAll() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun resetDemo() {
        viewModelScope.launch {
            call { repo.demoReset() }
                .onSuccess { _state.update { it.copy(notice = "모든 진행 데이터를 초기화했어요", lastResult = null, course = null) }; refreshAll() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun finishOnboarding() {
        prefs.edit().putBoolean("onboarding_done", true).apply()
        _state.update { it.copy(onboardingDone = true) }
    }

    fun dismissCelebration() = _state.update { it.copy(celebration = null) }
    fun clearError() = _state.update { it.copy(error = null) }

    private var knownTitles: Set<String>? = null

    private val repo = CarDexRepository()

    /** 화면의 VideoView 가 갱신하는 현재 재생 위치 */
    val videoPositionMs = AtomicLong(0)
    private var frames: FrameSource = AssetFrameSource(app)

    private val _state = MutableStateFlow(UiState(onboardingDone = prefs.getBoolean("onboarding_done", false), quizStars = prefs.getInt("quiz_stars", 0)))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var scanJob: Job? = null
    private var locationJob: Job? = null
    private var provider: VehicleContextProvider? = null
    private val navi = PleosNavi(app).apply { onError = { msg -> _state.update { it.copy(error = msg) } } }
    private val voice = VoiceGuide(app)
    private val pleosAi = PleosAi(app)
    private val voiceCommand = VoiceCommand(app).apply {
        onListening = { on -> _state.update { it.copy(listening = on) } }
        onText = { text -> viewModelScope.launch { handleVoice(text) } }
    }
    private val gear = GearMonitor(app)
    private val uxRestriction = DrivingRestriction(app)
    private var gearAvailable = false
    private var uxAvailable = false
    private var simulated: Boolean? = null
    private val fixes = ArrayDeque<Pair<GeoPoint, Long>>()

    init {
        // 데모 주행 영상(수십 MB) 준비는 IO 스레드에서
        viewModelScope.launch {
            val uri = withContext(Dispatchers.IO) { VideoFrameSource.demoVideoUri(app) }
            if (uri != null) {
                frames = VideoFrameSource(app, uri) { videoPositionMs.get() }
                _state.update { it.copy(demoVideo = uri) }
            }
        }
        // 1순위: Android Automotive 표준 주행 중 UX 제한
        uxAvailable = uxRestriction.start { restricted ->
            if (simulated == null) setDriving(restricted, "차량 주행 상태")
        }
        refreshCollection()
        loadPreferences()
        loadShowroom()
        loadStamps()
        loadChallenge()
        loadRankings()
        loadHome()
        viewModelScope.launch { // SDK 초기화가 비동기라 잠시 뒤 상태 표시
            delay(3_000)
            _state.update { it.copy(aiStatus = pleosAi.modeLabel, voiceStatus = if (voiceCommand.available) "Pleos 음성 인식" else "사용 안 함") }
        }
        // 알림은 음성으로도 (주행 중 화면을 보지 않도록)
        viewModelScope.launch {
            state.map { it.notice }.distinctUntilChanged().filterNotNull().collect { msg ->
                if (_state.value.voiceOn) voice.speak(msg)
                launch {
                    delay(NOTICE_MS)
                    _state.update { if (it.notice == msg) it.copy(notice = null) else it }
                }
            }
        }
    }

    // ---------------- 주행 상태 (안전 모드) ----------------
    /** Pleos Vehicle SDK 기어 상태 구독. 권한이 없거나 미지원이면 위치 속도로 추정 */
    fun startGear(permissionGranted: Boolean) {
        if (!permissionGranted || BuildConfig.USE_MOCK_PLEOS) return
        if (uxAvailable) return // 표준 UX 제한을 쓸 수 있으면 그걸 우선
        gearAvailable = gear.start { parked ->
            if (simulated == null && parked != null) setDriving(!parked, "Pleos 기어")
        }
    }

    private fun setDriving(driving: Boolean, source: String) {
        if (_state.value.isDriving != driving || _state.value.drivingSource != source) {
            _state.update { it.copy(isDriving = driving, drivingSource = source) }
        }
    }

    /** Vehicle SDK 가 없을 때: 위치 변화로 속도 추정 (8km/h 이상 주행, 3km/h 이하 정차) */
    private fun estimateSpeed(at: GeoPoint) {
        val now = System.currentTimeMillis()
        fixes.addLast(at to now)
        while (fixes.size > 2 && now - fixes[1].second >= SPEED_WINDOW_MS) fixes.removeFirst()
        val prev = fixes.first()
        if (now - prev.second < SPEED_WINDOW_MS) return
        val kmh = distanceM(prev.first, at) / ((now - prev.second) / 1000f) * 3.6f
        if (kmh > 250f) { fixes.clear(); fixes.addLast(at to now); return } // adb geo fix 등 순간이동은 무시
        _state.update { it.copy(speedKmh = kmh) }
        if (uxAvailable || gearAvailable || simulated != null) return
        when {
            kmh >= 8f -> setDriving(true, "위치 속도 추정")
            kmh <= 3f -> setDriving(false, "위치 속도 추정")
        }
    }

    /** 시연용: 주행/정차 강제 전환 (null 이면 자동) */
    fun simulateDriving(on: Boolean?) {
        simulated = on
        if (on != null) setDriving(on, "시연 전환") else setDriving(false, "")
    }

    fun toggleVoice() = _state.update { it.copy(voiceOn = !it.voiceOn) }

    /** Pleos 위치 권한 결과를 받은 뒤 호출. SDK 미지원/권한 거부 시 목업 위치로 대체 */
    fun startLocation(permissionGranted: Boolean) {
        locationJob?.cancel()
        val pleos = if (!BuildConfig.USE_MOCK_PLEOS && permissionGranted) {
            runCatching { PleosVehicleContextProvider(getApplication()) }.getOrNull()?.takeIf { it.isSupported() }
        } else null
        val p = pleos ?: MockVehicleContextProvider()
        provider = p
        locationJob = viewModelScope.launch {
            runCatching {
                p.snapshots().collect { s ->
                    _state.update { it.copy(driving = s) }
                    s.location?.let { estimateSpeed(it); reportVisit(it); refreshLandmarks(it); checkLandmarkDistance(it) }
                }
            }
                .onFailure { e -> _state.update { it.copy(error = "위치 서비스 오류: ${e.message}") } }
        }
    }

    // ---------------- 인식 ----------------
    fun scanOnce() {
        viewModelScope.launch { recognizeNextFrame() }
    }

    /** 자동 스캔: 주행 영상 재생 중 일정 주기로 프레임 전송 */
    fun toggleAutoScan(intervalMs: Long = 2_000) {
        if (scanJob?.isActive == true) {
            scanJob?.cancel(); _state.update { it.copy(scanning = false) }; return
        }
        _state.update { it.copy(scanning = true) }
        scanJob = viewModelScope.launch {
            while (isActive) { recognizeNextFrame(); delay(intervalMs) }
        }
    }

    private suspend fun recognizeNextFrame() {
        if (_state.value.busy) return
        val frame = withContext(Dispatchers.IO) { frames.next() } ?: run {
            _state.update { it.copy(error = "시연 영상(assets/demo_video/demo_drive.mp4) 또는 이미지를 넣어주세요.") }; return
        }
        _state.update { it.copy(busy = true, error = null, lastFrameJpeg = frame.jpeg) }
        call { repo.recognize(frame.jpeg, frame.name, _state.value.driving?.location) }
            .onSuccess { r ->
                val card = r.card
                _state.update {
                    it.copy(
                        // 차량이 없는 프레임은 직전 카드를 유지 (박스는 이번 프레임 기준)
                        lastResult = if (card != null || it.lastResult == null) r else it.lastResult?.copy(detections = r.detections),
                        // 새 카드: 희귀·전설은 축하 연출, 일반은 알림 (주행 중엔 음성만)
                        celebration = if (card != null && r.twin) Celebration("👯", "쌍둥이 차 발견!", r.message)
                        else if (card != null && r.isNewEntry) when (card.rarity) {
                            "legendary" -> Celebration("🌟", "LEGENDARY 카드 획득!", "${card.brand} ${card.model}")
                            "rare" -> Celebration("💎", "희귀 카드 획득!", "${card.brand} ${card.model}")
                            else -> it.celebration
                        } else it.celebration,
                        notice = if (card != null && r.isNewEntry) "🃏 새 카드: ${card.brand} ${card.model}" else it.notice,
                    )
                }
                if (card != null) refreshCollection()
            }
            .onFailure { e -> _state.update { it.copy(error = "서버 연결 실패: ${e.message}") } }
        _state.update { it.copy(busy = false) }
    }

    // ---------------- 도감 / 시너지 ----------------
    fun refreshCollection() {
        viewModelScope.launch {
            call { repo.collection() }
                .onSuccess { c -> _state.update { it.copy(collection = c.entries, synergy = c.synergy) } }
            if (_state.value.catalog.isEmpty()) call { repo.catalog() }.onSuccess { c -> _state.update { it.copy(catalog = c) } }
            loadHome()
        }
    }

    fun deleteEntry(id: String) {
        viewModelScope.launch {
            call { repo.delete(id) }; refreshCollection(); loadShowroom()
        }
    }

    // ---------------- 지역 스탬프 ----------------
    private var lastVisitReportMs = 0L

    /** Pleos 위치를 30초마다 서버에 보고 -> 새 지역이면 스탬프 획득 */
    private fun reportVisit(location: com.pleos.cardex.data.GeoPoint) {
        val now = System.currentTimeMillis()
        if (now - lastVisitReportMs < VISIT_REPORT_INTERVAL_MS) return
        lastVisitReportMs = now
        viewModelScope.launch {
            call { repo.visit(location) }.onSuccess { r ->
                if (r.isNew) {
                    _state.update { it.copy(notice = r.message) }
                    loadStamps(); loadRankings()
                }
            }
        }
    }

    fun loadStamps() {
        viewModelScope.launch {
            call { repo.stamps() }.onSuccess { b -> _state.update { it.copy(stampBook = b) } }
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    // ---------------- 히든 스탬프 (TourAPI 명소 근접) ----------------
    private var lastLandmarkFetch: Pair<GeoPoint, Long>? = null
    private val visiting = mutableSetOf<String>()

    /** 3km 이상 이동했거나 10분 지나면 주변 명소를 다시 받아 Pleos 근접 알림 재등록 */
    private fun refreshLandmarks(at: GeoPoint, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val last = lastLandmarkFetch
        if (!force && last != null && distanceM(last.first, at) < 3_000 && now - last.second < 10 * 60_000) return
        lastLandmarkFetch = at to now
        viewModelScope.launch {
            call { repo.landmarksNearby(at) }.onSuccess { list ->
                _state.update { it.copy(landmarks = list) }
                registerProximity()
            }
        }
    }

    /** 근접 알림 대상 = 미획득 주변 명소 + 진행 중인 챌린지 코스의 남은 장소 */
    private fun proximityTargets(): List<LandmarkTarget> {
        val s = _state.value
        val landmarks = s.landmarks.filterNot { it.collected }.map {
            LandmarkTarget(it.attraction.contentId, it.attraction.title, it.attraction.lat, it.attraction.lon, LANDMARK_RADIUS_M)
        }
        val ch = s.challenge?.takeIf { it.status == "active" }
        val stops = ch?.course?.stops.orEmpty().filterNot { it.contentId in ch!!.visited }.map {
            LandmarkTarget(it.contentId, it.name, it.lat, it.lon, LANDMARK_RADIUS_M)
        }
        return (stops + landmarks).distinctBy { it.id }.take(MAX_PROXIMITY_ALERTS)
    }

    private fun registerProximity() {
        provider?.watchLandmarks(proximityTargets()) { t -> viewModelScope.launch { visitLandmark(t.id) } }
    }

    /** Pleos 근접 알림이 없는 환경(목업 등)을 위한 거리 판정. 서버가 중복을 걸러준다. */
    private fun checkLandmarkDistance(at: GeoPoint) {
        proximityTargets()
            .filter { distanceM(at, GeoPoint(it.lat, it.lon)) <= it.radiusMeters }
            .forEach { visitLandmark(it.id) }
    }

    private fun visitLandmark(id: String) {
        val at = _state.value.driving?.location ?: return
        if (!visiting.add(id)) return
        viewModelScope.launch {
            call { repo.visitLandmark(id, at) }.onSuccess { r ->
                r.challenge?.let { u ->
                    _state.update { it.copy(challenge = u.challenge, notice = r.message) }
                    registerProximity()
                    if (u.justCompleted) {
                        val badge = u.badge
                        _state.update {
                            it.copy(
                                celebration = if (badge != null) Celebration("🎖️", "축제 한정 배지 획득!", "${badge.title} · 완주 +${u.bonusPoints} · 배지 +50")
                                else Celebration("🏁", "코스 완주!", "${u.challenge.course.title} · +${u.bonusPoints}점"),
                            )
                        }
                        loadRankings(); loadShowroom(); refreshCollection()
                    }
                    loadHome()
                }
                if (r.isNew) {
                    _state.update { s ->
                        s.copy(
                            notice = r.message,
                            landmarks = s.landmarks.map { if (it.attraction.contentId == id) it.copy(collected = true) else it },
                        )
                    }
                    loadStamps(); refreshCollection(); loadRankings()
                }
            }
            visiting.remove(id)
        }
    }

    // ---------------- AI 드라이브 코스 ----------------
    /**
     * AI 드라이브 코스: Pleos LLM 을 쓸 수 있으면 서버 후보·프롬프트 → Pleos LLM 선택 → 서버가 시간·점수 계산,
     * 아니면 서버 추천(규칙 기반/서버 LLM).
     */
    fun recommendCourse(maxHours: Double, note: String) {
        val at = _state.value.driving?.location ?: run {
            _state.update { it.copy(error = "현재 위치를 아직 받지 못했어요.") }; return
        }
        _state.update { it.copy(courseLoading = true, error = null) }
        viewModelScope.launch {
            val req = com.pleos.cardex.data.CourseRequest(at.lat, at.lon, maxHours, note)
            val byPleos = if (pleosAi.available) {
                call { repo.courseCandidates(req) }.getOrNull()?.let { c -> pleosAi.pickCourse(c.prompt) }?.let { pick ->
                    call { repo.buildCourse(com.pleos.cardex.data.CourseBuildRequest(req, pick.title, pick.summary, pick.stopIds, pick.reasons)) }.getOrNull()
                }
            } else null
            if (byPleos != null) {
                _state.update { it.copy(course = byPleos, courseBy = "Pleos LLM") }
            } else {
                call { repo.recommendCourse(at, maxHours, note) }
                    .onSuccess { c -> _state.update { it.copy(course = c, courseBy = "서버") } }
                    .onFailure { e -> _state.update { it.copy(error = "코스 추천 실패: ${e.message}") } }
            }
            _state.update { it.copy(courseLoading = false) }
        }
    }

    // ---------------- 아이용 차 맞히기 퀴즈 ----------------
    /** 방금 지나간 장면으로 문제 내기 (문제와 보기는 음성으로도 읽어 줌) */
    fun nextQuiz() {
        if (_state.value.quizLoading) return
        _state.update { it.copy(quizLoading = true, quizMessage = null) }
        viewModelScope.launch {
            val frame = withContext(Dispatchers.IO) { frames.next() }
            if (frame == null) {
                _state.update { it.copy(quizLoading = false, quizMessage = "시연 영상이나 이미지를 넣어 주세요") }; return@launch
            }
            call { repo.quiz(frame.jpeg, frame.name) }
                .onSuccess { r ->
                    val q = r.question
                    _state.update {
                        it.copy(quiz = q, quizFrameJpeg = if (q != null) frame.jpeg else it.quizFrameJpeg, quizPicked = null, quizMessage = r.message.takeIf { m -> q == null && m.isNotBlank() })
                    }
                    if (q != null) say("${q.prompt} ${q.choices.joinToString(", ")}")
                }
                .onFailure { e -> _state.update { it.copy(quizMessage = e.message) } }
            _state.update { it.copy(quizLoading = false) }
        }
    }

    fun answerQuiz(choice: String) {
        val q = _state.value.quiz ?: return
        if (_state.value.quizPicked != null) return
        val correct = choice == q.answer
        val stars = _state.value.quizStars + if (correct) 1 else 0
        prefs.edit().putInt("quiz_stars", stars).apply()
        _state.update { it.copy(quizPicked = choice, quizStars = stars) }
        say(if (correct) "정답이에요! 별 하나 받았어요" else "아쉬워요. 정답은 ${q.answer}이에요")
    }

    /** 말로 한 대답을 보기 중 하나로 (번호 / 색·종류 이름) */
    private fun matchQuizAnswer(text: String, q: com.pleos.cardex.data.QuizQuestion): String? {
        val t = text.replace(" ", "")
        listOf("1번" to 0, "첫번째" to 0, "2번" to 1, "두번째" to 1, "3번" to 2, "세번째" to 2).firstOrNull { it.first in t }
            ?.let { (_, i) -> return q.choices.getOrNull(i) }
        val synonyms = mapOf(
            "빨간색" to listOf("빨간", "빨강"), "주황색" to listOf("주황"), "노란색" to listOf("노란", "노랑"),
            "초록색" to listOf("초록", "녹색"), "파란색" to listOf("파란", "파랑"), "보라색" to listOf("보라"),
            "흰색" to listOf("흰", "하얀", "하양"), "검은색" to listOf("검은", "검정", "까만"), "회색" to listOf("회색", "은색", "쥐색"),
            "버스" to listOf("버스"), "트럭" to listOf("트럭"), "오토바이" to listOf("오토바이", "바이크"), "승용차" to listOf("승용차", "자동차", "차"),
        )
        return q.choices.firstOrNull { c -> synonyms[c].orEmpty().any { it in t } }
    }

    // ---------------- 음성 명령 (Pleos SpeechToText) ----------------
    fun listenVoice() {
        if (!voiceCommand.listen()) _state.update { it.copy(notice = "음성 인식을 사용할 수 없어요") }
    }

    /** 운전 중에 쓸 짧은 명령만 (화면 조작 없이) */
    private fun handleVoice(text: String) {
        val t = text.replace(" ", "")
        _state.update { it.copy(heard = text) }
        val s = _state.value
        // 퀴즈 답을 기다리는 중이면 먼저 대답으로 처리
        val q = s.quiz
        if (q != null && s.quizPicked == null) {
            matchQuizAnswer(text, q)?.let { answerQuiz(it); return }
        }
        when {
            listOf("퀴즈", "문제", "다음").any { it in t } -> nextQuiz()
            listOf("안내", "길안내", "가자", "출발").any { it in t } -> {
                if (s.challenge?.status == "active") { resumeNavigation(); say("남은 곳으로 길안내를 시작할게요") }
                else say("진행 중인 코스가 없어요. 정차 후 코스를 추천받아 주세요")
            }
            listOf("인식", "수집", "찍어").any { it in t } -> { scanOnce(); say("차량을 인식할게요") }
            listOf("순위", "점수", "랭킹").any { it in t } -> s.weekly?.me?.let { me ->
                say("이번 주 ${me.rank}위, ${me.points}점이에요" + (s.weekly.gapToNext?.let { ", 한 계단 위까지 ${it}점" } ?: ""))
            } ?: say("순위를 불러오는 중이에요")
            listOf("도감", "몇종", "카드").any { it in t } -> s.home?.let { say("도감 ${it.dexOwned}종, 전설 ${it.legendaryOwned}종을 모았어요") }
                ?: say("도감을 불러오는 중이에요")
            listOf("조용", "음성꺼", "그만").any { it in t } -> { _state.update { it.copy(voiceOn = false) } }
            else -> say("'퀴즈', '길안내', '인식', '순위', '도감' 이라고 말해 보세요")
        }
    }

    private fun say(msg: String) = _state.update { it.copy(notice = msg) }

    /** 추천 코스로 완주 챌린지를 시작하고 Pleos 내비게이션 길안내 */
    fun startChallengeAndNavigate() {
        val course = _state.value.course ?: return
        if (course.stops.isEmpty()) return
        viewModelScope.launch {
            call { repo.startChallenge(course) }
                .onSuccess { ch ->
                    _state.update { it.copy(challenge = ch, notice = "🏁 챌린지 시작! 완주하면 +${course.completionBonus}") }
                    registerProximity()
                    navi.navigate(ch.course.stops.filterNot { it.contentId in ch.visited })
                }
                .onFailure { e -> _state.update { it.copy(error = "챌린지 시작 실패: ${e.message}") } }
        }
    }

    /** 진행 중인 챌린지의 남은 장소로 다시 길안내 */
    fun resumeNavigation() {
        val ch = _state.value.challenge ?: return
        navi.navigate(ch.course.stops.filterNot { it.contentId in ch.visited })
    }

    fun abandonChallenge() {
        viewModelScope.launch {
            call { repo.abandonChallenge() }.onSuccess { _state.update { it.copy(challenge = null) }; registerProximity() }
        }
    }

    private fun loadChallenge() {
        viewModelScope.launch {
            call { repo.challenge() }.onSuccess { ch -> _state.update { it.copy(challenge = ch) } }
        }
    }

    // ---------------- 랭킹 / 지역 마스터 ----------------
    fun loadRankings(regionId: String? = _state.value.rankingRegionId) {
        viewModelScope.launch {
            call { repo.weeklyRanking(regionId) }
                .onSuccess { lb -> _state.update { it.copy(weekly = lb, rankingRegionId = regionId) } }
            call { repo.masters() }.onSuccess { m ->
                val prev = knownTitles
                val gained = if (prev == null) emptyList() else m.myTitles.filterNot { it in prev }
                knownTitles = m.myTitles.toSet()
                _state.update {
                    it.copy(
                        masters = m,
                        celebration = gained.firstOrNull()?.let { t -> Celebration("👑", "$t 마스터 등극!", "쇼룸에 왕관·골드 아이템이 열렸어요") } ?: it.celebration,
                    )
                }
                if (gained.isNotEmpty()) loadShowroom()
            }
            loadHome()
        }
    }

    // ---------------- 쇼룸 ----------------
    fun loadShowroom() {
        viewModelScope.launch {
            call { repo.showroom() }.onSuccess { r -> _state.update { it.copy(showroom = r) } }
            call { repo.decorItems() }.onSuccess { c -> _state.update { it.copy(decor = c) } }
        }
    }

    private fun updateShowroom(transform: (ShowroomUpdate) -> ShowroomUpdate) {
        val req = transform(_state.value.showroom.toUpdate())
        viewModelScope.launch {
            call { repo.updateShowroom(req) }
                .onSuccess { r -> _state.update { it.copy(showroom = r) }; refreshCollection() }
                .onFailure { e -> _state.update { it.copy(error = "쇼룸 변경 실패: ${e.message}") } }
        }
    }

    /** 도감 차량을 쇼룸 대표 차량으로 전시 */
    fun feature(cardId: String) = updateShowroom { it.copy(cardId = cardId) }

    fun setBackground(regionId: String?) = updateShowroom { it.copy(backgroundRegionId = regionId) }

    /** 바닥/조명/전시대/간판 장착 */
    fun equip(slot: String, itemId: String) = updateShowroom {
        when (slot) {
            "floor" -> it.copy(floorId = itemId)
            "light" -> it.copy(lightId = itemId)
            "pedestal" -> it.copy(pedestalId = itemId)
            "sign" -> it.copy(signId = itemId)
            else -> it
        }
    }

    /** 소품을 무대에 추가 (빈 자리에 자동 배치) */
    fun addProp(itemId: String) = updateShowroom {
        if (it.props.size >= MAX_PROPS) return@updateShowroom it
        val slots = listOf(0.12f to 0.86f, 0.88f to 0.86f, 0.2f to 0.62f, 0.8f to 0.62f, 0.06f to 0.4f, 0.94f to 0.4f, 0.35f to 0.9f, 0.65f to 0.9f)
        val (x, y) = slots[it.props.size % slots.size]
        it.copy(props = it.props + PlacedProp(itemId, x, y))
    }

    /** 드래그로 옮긴 소품 위치 저장 (화면은 즉시 반영, 서버는 손을 뗄 때) */
    fun moveProp(index: Int, x: Float, y: Float, commit: Boolean) {
        val cur = _state.value.showroom
        if (index !in cur.props.indices) return
        val moved = cur.props.toMutableList().also { l -> l[index] = l[index].copy(x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f)) }
        _state.update { it.copy(showroom = cur.copy(props = moved)) }
        if (commit) updateShowroom { it }
    }

    fun resizeProp(index: Int) = updateShowroom {
        if (index !in it.props.indices) return@updateShowroom it
        val p = it.props[index]
        val next = when { p.scale < 1f -> 1f; p.scale < 1.5f -> 1.5f; p.scale < 2f -> 2f; else -> 0.5f }
        it.copy(props = it.props.toMutableList().also { l -> l[index] = p.copy(scale = next) })
    }

    fun removeProp(index: Int) = updateShowroom {
        it.copy(props = it.props.filterIndexed { i, _ -> i != index })
    }

    // ---------------- 키워드 ----------------
    private fun loadPreferences() {
        viewModelScope.launch {
            call { repo.preferences() }.onSuccess { p -> _state.update { it.copy(keywords = p.keywords, myCarId = p.myCarId) } }
            call { repo.fields() }.onSuccess { f -> _state.update { it.copy(fields = f) } }
        }
    }

    /** 내 차 등록 (도감 28종 중) — 쌍둥이 차 보너스, 카드 뒷면 '내 차와 비교' */
    fun saveMyCar(id: String?) {
        viewModelScope.launch {
            call { repo.savePreferences(Preferences(_state.value.keywords, myCarId = id)) }
                .onSuccess { p -> _state.update { it.copy(myCarId = p.myCarId) }; refreshCollection() }
                .onFailure { e -> _state.update { it.copy(error = "저장 실패: ${e.message}") } }
        }
    }

    fun saveKeywords(keywords: List<String>) {
        viewModelScope.launch {
            call { repo.savePreferences(Preferences(keywords, myCarId = _state.value.myCarId)) }
                .onSuccess { p -> _state.update { it.copy(keywords = p.keywords) } }
                .onFailure { e -> _state.update { it.copy(error = "저장 실패: ${e.message}") } }
        }
    }

    private companion object {
        const val NOTICE_MS = 4_000L
        const val SPEED_WINDOW_MS = 5_000L
        const val VISIT_REPORT_INTERVAL_MS = 30_000L
        const val LANDMARK_RADIUS_M = 500 // 백엔드 LANDMARK_RADIUS_M 과 동일하게
        const val MAX_PROXIMITY_ALERTS = 20

        fun distanceM(a: GeoPoint, b: GeoPoint): Float {
            val out = FloatArray(1)
            android.location.Location.distanceBetween(a.lat, a.lon, b.lat, b.lon, out)
            return out[0]
        }
    }

    override fun onCleared() {
        navi.release()
        voice.release()
        pleosAi.release()
        voiceCommand.release()
        gear.release()
        uxRestriction.release()
        frames.release()
    }
}
