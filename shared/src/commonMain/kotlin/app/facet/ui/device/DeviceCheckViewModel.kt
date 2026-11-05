package app.facet.ui.device

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.facet.platform.ArServicesStatus
import app.facet.platform.DepthStatus
import app.facet.platform.DeviceCapabilities
import app.facet.platform.DeviceCheckResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DeviceCheckUiState {
    data object Checking : DeviceCheckUiState
    data class Done(val result: DeviceCheckResult) : DeviceCheckUiState
}

class DeviceCheckViewModel(private val capabilities: DeviceCapabilities) : ViewModel() {
    private val _state = MutableStateFlow<DeviceCheckUiState>(DeviceCheckUiState.Checking)
    val state: StateFlow<DeviceCheckUiState> = _state.asStateFlow()
    private var job: Job? = null

    init { check() }

    /** Runs the check again (Check again, and on returning from the Play Store). */
    fun check() {
        job?.cancel()
        _state.value = DeviceCheckUiState.Checking
        job = viewModelScope.launch {
            val result = try {
                capabilities.check()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                DeviceCheckResult(ArServicesStatus.UNKNOWN, DepthStatus.UNKNOWN, deviceModel = "")
            }
            _state.value = DeviceCheckUiState.Done(result)
        }
    }
}
