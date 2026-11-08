package com.pleos.cardex.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pleos.cardex.BuildConfig
import com.pleos.cardex.data.CarDexRepository
import com.pleos.cardex.data.CollectionEntry
import com.pleos.cardex.data.Preferences
import com.pleos.cardex.data.RecognitionResponse
import com.pleos.cardex.data.Showroom
import com.pleos.cardex.data.ShowroomUpdate
import com.pleos.cardex.data.StampBook
import com.pleos.cardex.data.SynergyScore
import com.pleos.cardex.pleos.AssetFrameSource
import com.pleos.cardex.pleos.DrivingSnapshot
import com.pleos.cardex.pleos.FrameSource
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

    init {
        refreshCollection()
        loadPreferences()
        loadShowroom()
        loadStamps()
    }

    /** Pleos 위치 권한 결과를 받은 뒤 호출. SDK 미지원/권한 거부 시 목업 위치로 대체 */
    fun startLocation(permissionGranted: Boolean) {
        locationJob?.cancel()
        val pleos = if (!BuildConfig.USE_MOCK_PLEOS && permissionGranted) {
            PleosVehicleContextProvider(getApplication()).takeIf { it.isSupported() }
        } else null
        val provider = pleos ?: MockVehicleContextProvider()
        locationJob = viewModelScope.launch {
            runCatching {
                provider.snapshots().collect { s ->
                    _state.update { it.copy(driving = s) }
                    s.location?.let { reportVisit(it) }
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
                    loadStamps()
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

    private companion object { const val VISIT_REPORT_INTERVAL_MS = 30_000L }

    override fun onCleared() {
        frames.release()
    }
}
