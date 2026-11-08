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
import com.pleos.cardex.data.SynergyScore
import com.pleos.cardex.data.VehicleCard
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
    val summoned: VehicleCard? = null,
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
        loadGarage()
    }

    /** Pleos 위치 권한 결과를 받은 뒤 호출. SDK 미지원/권한 거부 시 목업 위치로 대체 */
    fun startLocation(permissionGranted: Boolean) {
        locationJob?.cancel()
        val pleos = if (!BuildConfig.USE_MOCK_PLEOS && permissionGranted) {
            PleosVehicleContextProvider(getApplication()).takeIf { it.isSupported() }
        } else null
        val provider = pleos ?: MockVehicleContextProvider()
        locationJob = viewModelScope.launch {
            runCatching { provider.snapshots().collect { s -> _state.update { it.copy(driving = s) } } }
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
            runCatching { repo.delete(id) }; refreshCollection(); loadGarage()
        }
    }

    // ---------------- 소환 ----------------
    private fun loadGarage() {
        viewModelScope.launch {
            runCatching { repo.garage() }.onSuccess { g -> _state.update { it.copy(summoned = g.active) } }
        }
    }

    fun summon(id: String) {
        viewModelScope.launch {
            runCatching { repo.summon(id) }
                .onSuccess { g -> _state.update { it.copy(summoned = g.active) }; refreshCollection() }
                .onFailure { e -> _state.update { it.copy(error = "소환 실패: ${e.message}") } }
        }
    }

    fun dismissSummon() {
        viewModelScope.launch {
            runCatching { repo.dismiss() }.onSuccess { _state.update { it.copy(summoned = null) }; refreshCollection() }
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

    override fun onCleared() {
        frames.release()
    }
}
