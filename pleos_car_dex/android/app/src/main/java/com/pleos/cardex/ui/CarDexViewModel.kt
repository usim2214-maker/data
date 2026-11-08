package com.pleos.cardex.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pleos.cardex.BuildConfig
import com.pleos.cardex.data.CarDexRepository
import com.pleos.cardex.data.Challenge
import com.pleos.cardex.data.CollectionEntry
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
import com.pleos.cardex.pleos.FrameSource
import com.pleos.cardex.pleos.LandmarkTarget
import com.pleos.cardex.pleos.PleosNavi
import com.pleos.cardex.pleos.VehicleContextProvider
import com.pleos.cardex.pleos.MockVehicleContextProvider
import com.pleos.cardex.pleos.PleosVehicleContextProvider
import com.pleos.cardex.pleos.VideoFrameSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class UiState(
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val lastResult: RecognitionResponse? = null,
    val collection: List<CollectionEntry> = emptyList(),
    val synergy: SynergyScore? = null,
    val showroom: Showroom = Showroom(),
    val stampBook: StampBook? = null,
    /** 새 스탬프 획득 등 잠깐 띄울 알림 */
    val notice: String? = null,
    val keywords: List<String> = emptyList(),
    val driving: DrivingSnapshot? = null,
    val demoVideo: Uri? = null,
    val landmarks: List<LandmarkNearby> = emptyList(),
    val course: DriveCourse? = null,
    val courseLoading: Boolean = false,
    val challenge: Challenge? = null,
    val weekly: Leaderboard? = null,
    val rankingRegionId: String? = null,
    val masters: MastersResponse? = null,
    val error: String? = null,
)

class CarDexViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CarDexRepository()

    /** 화면의 VideoView 가 갱신하는 현재 재생 위치 */
    val videoPositionMs = AtomicLong(0)
    private val demoVideo = VideoFrameSource.demoVideoUri(app)
    private val frames: FrameSource =
        demoVideo?.let { VideoFrameSource(app, it) { videoPositionMs.get() } } ?: AssetFrameSource(app)

    private val _state = MutableStateFlow(UiState(demoVideo = demoVideo))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var scanJob: Job? = null
    private var locationJob: Job? = null
    private var provider: VehicleContextProvider? = null
    private val navi = PleosNavi(app).apply { onError = { msg -> _state.update { it.copy(error = msg) } } }

    init {
        refreshCollection()
        loadPreferences()
        loadShowroom()
        loadStamps()
        loadChallenge()
        loadRankings()
    }

    /** Pleos 위치 권한 결과를 받은 뒤 호출. SDK 미지원/권한 거부 시 목업 위치로 대체 */
    fun startLocation(permissionGranted: Boolean) {
        locationJob?.cancel()
        val pleos = if (!BuildConfig.USE_MOCK_PLEOS && permissionGranted) {
            PleosVehicleContextProvider(getApplication()).takeIf { it.isSupported() }
        } else null
        val p = pleos ?: MockVehicleContextProvider()
        provider = p
        locationJob = viewModelScope.launch {
            runCatching {
                p.snapshots().collect { s ->
                    _state.update { it.copy(driving = s) }
                    s.location?.let { reportVisit(it); refreshLandmarks(it); checkLandmarkDistance(it) }
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
        val frame = frames.next() ?: run {
            _state.update { it.copy(error = "시연 영상(assets/demo_video/demo_drive.mp4) 또는 이미지를 넣어주세요.") }; return
        }
        _state.update { it.copy(busy = true, error = null) }
        runCatching { repo.recognize(frame.jpeg, frame.name, _state.value.driving?.location) }
            .onSuccess { r ->
                // 차량이 없는 프레임은 직전 카드를 유지
                _state.update { it.copy(lastResult = if (r.card != null || it.lastResult == null) r else it.lastResult) }
                if (r.card != null) refreshCollection()
            }
            .onFailure { e -> _state.update { it.copy(error = "서버 연결 실패: ${e.message}") } }
        _state.update { it.copy(busy = false) }
    }

    // ---------------- 도감 / 시너지 ----------------
    fun refreshCollection() {
        viewModelScope.launch {
            runCatching { repo.collection() }
                .onSuccess { c -> _state.update { it.copy(collection = c.entries, synergy = c.synergy) } }
        }
    }

    fun deleteEntry(id: String) {
        viewModelScope.launch {
            runCatching { repo.delete(id) }; refreshCollection(); loadShowroom()
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
            runCatching { repo.visit(location) }.onSuccess { r ->
                if (r.isNew) {
                    _state.update { it.copy(notice = r.message) }
                    loadStamps(); loadRankings()
                }
            }
        }
    }

    fun loadStamps() {
        viewModelScope.launch {
            runCatching { repo.stamps() }.onSuccess { b -> _state.update { it.copy(stampBook = b) } }
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
            runCatching { repo.landmarksNearby(at) }.onSuccess { list ->
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
        provider?.watchLandmarks(proximityTargets()) { t -> visitLandmark(t.id) }
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
            runCatching { repo.visitLandmark(id, at) }.onSuccess { r ->
                r.challenge?.let { u ->
                    _state.update { it.copy(challenge = u.challenge, notice = r.message) }
                    registerProximity()
                    if (u.justCompleted) { loadRankings(); loadShowroom(); refreshCollection() }
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
    fun recommendCourse(maxHours: Double, note: String) {
        val at = _state.value.driving?.location ?: run {
            _state.update { it.copy(error = "현재 위치를 아직 받지 못했어요.") }; return
        }
        _state.update { it.copy(courseLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.recommendCourse(at, maxHours, note) }
                .onSuccess { c -> _state.update { it.copy(course = c) } }
                .onFailure { e -> _state.update { it.copy(error = "코스 추천 실패: ${e.message}") } }
            _state.update { it.copy(courseLoading = false) }
        }
    }

    /** 추천 코스로 완주 챌린지를 시작하고 Pleos 내비게이션 길안내 */
    fun startChallengeAndNavigate() {
        val course = _state.value.course ?: return
        if (course.stops.isEmpty()) return
        viewModelScope.launch {
            runCatching { repo.startChallenge(course) }
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
            runCatching { repo.abandonChallenge() }.onSuccess { _state.update { it.copy(challenge = null) }; registerProximity() }
        }
    }

    private fun loadChallenge() {
        viewModelScope.launch {
            runCatching { repo.challenge() }.onSuccess { ch -> _state.update { it.copy(challenge = ch) } }
        }
    }

    // ---------------- 랭킹 / 지역 마스터 ----------------
    fun loadRankings(regionId: String? = _state.value.rankingRegionId) {
        viewModelScope.launch {
            runCatching { repo.weeklyRanking(regionId) }
                .onSuccess { lb -> _state.update { it.copy(weekly = lb, rankingRegionId = regionId) } }
            runCatching { repo.masters() }.onSuccess { m -> _state.update { it.copy(masters = m) } }
        }
    }

    // ---------------- 쇼룸 ----------------
    private fun loadShowroom() {
        viewModelScope.launch {
            runCatching { repo.showroom() }.onSuccess { r -> _state.update { it.copy(showroom = r) } }
        }
    }

    private fun updateShowroom(transform: (ShowroomUpdate) -> ShowroomUpdate) {
        val cur = _state.value.showroom
        val req = transform(ShowroomUpdate(cur.car?.id, cur.background?.id, cur.stickers.map { it.id }))
        viewModelScope.launch {
            runCatching { repo.updateShowroom(req) }
                .onSuccess { r -> _state.update { it.copy(showroom = r) }; refreshCollection() }
                .onFailure { e -> _state.update { it.copy(error = "쇼룸 변경 실패: ${e.message}") } }
        }
    }

    /** 도감 차량을 쇼룸 대표 차량으로 전시 */
    fun feature(cardId: String) = updateShowroom { it.copy(cardId = cardId) }

    fun setBackground(regionId: String?) = updateShowroom { it.copy(backgroundRegionId = regionId) }

    fun toggleSticker(regionId: String) = updateShowroom {
        val ids = it.stickerRegionIds
        when {
            regionId in ids -> it.copy(stickerRegionIds = ids - regionId)
            ids.size < com.pleos.cardex.data.MAX_STICKERS -> it.copy(stickerRegionIds = ids + regionId)
            else -> it
        }
    }

    // ---------------- 키워드 ----------------
    private fun loadPreferences() {
        viewModelScope.launch {
            runCatching { repo.preferences() }.onSuccess { p -> _state.update { it.copy(keywords = p.keywords) } }
        }
    }

    fun saveKeywords(keywords: List<String>) {
        viewModelScope.launch {
            runCatching { repo.savePreferences(Preferences(keywords)) }
                .onSuccess { p -> _state.update { it.copy(keywords = p.keywords) } }
                .onFailure { e -> _state.update { it.copy(error = "저장 실패: ${e.message}") } }
        }
    }

    private companion object {
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
        frames.release()
    }
}
