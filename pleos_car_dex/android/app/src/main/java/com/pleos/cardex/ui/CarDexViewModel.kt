package com.pleos.cardex.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pleos.cardex.BuildConfig
import com.pleos.cardex.data.CarDexRepository
import com.pleos.cardex.data.CollectionEntry
import com.pleos.cardex.data.RecognitionResponse
import com.pleos.cardex.pleos.AssetFrameSource
import com.pleos.cardex.pleos.DrivingSnapshot
import com.pleos.cardex.pleos.FrameSource
import com.pleos.cardex.pleos.MockVehicleContextProvider
import com.pleos.cardex.pleos.PleosVehicleContextProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class UiState(
    val scanning: Boolean = false,
    val busy: Boolean = false,
    val lastResult: RecognitionResponse? = null,
    val collection: List<CollectionEntry> = emptyList(),
    val keywords: List<String> = emptyList(),
    val driving: DrivingSnapshot? = null,
    val error: String? = null,
)

class CarDexViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CarDexRepository()
    private val frames: FrameSource = AssetFrameSource(app)
    private val vehicle = if (BuildConfig.USE_MOCK_PLEOS) MockVehicleContextProvider() else PleosVehicleContextProvider()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var scanJob: Job? = null

    init {
        viewModelScope.launch { vehicle.snapshots().collect { s -> _state.update { it.copy(driving = s) } } }
        refreshCollection()
        loadPreferences()
    }

    /** 한 장 인식 (시연 버튼) */
    fun scanOnce() {
        viewModelScope.launch { recognizeNextFrame() }
    }

    /** 자동 스캔: 일정 주기로 프레임 전송 (주행 중 시연용) */
    fun toggleAutoScan(intervalMs: Long = 4_000) {
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
            _state.update { it.copy(error = "assets/demo_frames 에 시연 이미지를 넣어주세요.") }; return
        }
        _state.update { it.copy(busy = true, error = null) }
        runCatching { repo.recognize(frame.jpeg, frame.name, _state.value.driving?.location) }
            .onSuccess { r ->
                _state.update { it.copy(lastResult = r) }
                if (r.card != null) refreshCollection()
            }
            .onFailure { e -> _state.update { it.copy(error = "서버 연결 실패: ${e.message}") } }
        _state.update { it.copy(busy = false) }
    }

    fun refreshCollection() {
        viewModelScope.launch {
            runCatching { repo.collection() }.onSuccess { c -> _state.update { it.copy(collection = c.entries) } }
        }
    }

    fun deleteEntry(id: String) {
        viewModelScope.launch {
            runCatching { repo.delete(id) }; refreshCollection()
        }
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            runCatching { repo.preferences() }.onSuccess { p -> _state.update { it.copy(keywords = p.keywords) } }
        }
    }

    fun saveKeywords(keywords: List<String>) {
        viewModelScope.launch {
            runCatching { repo.savePreferences(com.pleos.cardex.data.Preferences(keywords)) }
                .onSuccess { p -> _state.update { it.copy(keywords = p.keywords) } }
                .onFailure { e -> _state.update { it.copy(error = "저장 실패: ${e.message}") } }
        }
    }
}
