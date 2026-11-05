package app.facet.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.facet.data.ScanRepository
import app.facet.platform.StorageInfo
import app.facet.platform.StorageUsage
import app.facet.settings.AppSettings
import app.facet.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val usage: StorageUsage = StorageUsage(0, 0, 0),
    val freeBytes: Long = 0,
) {
    /** Share of the storage bar used by models / saved photos, relative to used + free. */
    val modelsFraction: Float get() = fraction(usage.modelBytes)
    val photosFraction: Float get() = fraction(usage.savedPhotoBytes)

    private fun fraction(bytes: Long): Float {
        val total = usage.totalBytes + freeBytes
        return if (total <= 0) 0f else (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }
}

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val scans: ScanRepository,
    private val storage: StorageInfo,
) : ViewModel() {
    val state: StateFlow<SettingsUiState> =
        combine(settingsRepository.settings, scans.usage) { s, usage ->
            SettingsUiState(s, usage, storage.freeBytes())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState())

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    fun clearSavedPhotos() {
        viewModelScope.launch { scans.clearSavedPhotos() }
    }
}
