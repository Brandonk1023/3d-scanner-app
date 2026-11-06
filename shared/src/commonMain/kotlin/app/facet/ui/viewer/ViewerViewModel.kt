package app.facet.ui.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.facet.data.Scan
import app.facet.data.ScanRepository
import app.facet.mesh.Mesh
import app.facet.settings.LengthUnit
import app.facet.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ViewerUiState(
    val loading: Boolean = true,
    val scan: Scan? = null,
    val mesh: Mesh? = null,
    val units: LengthUnit = LengthUnit.MILLIMETRES,
) {
    /** Loaded, but the scan doesn't exist (deleted): the screen should close. */
    val missing: Boolean get() = !loading && scan == null
}

class ViewerViewModel(
    private val scanId: String,
    private val repository: ScanRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val mesh = MutableStateFlow<Mesh?>(null)

    val state: StateFlow<ViewerUiState> =
        combine(repository.scan(scanId), mesh, settings.settings) { scan, m, s ->
            ViewerUiState(loading = false, scan = scan, mesh = m, units = s.units)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ViewerUiState())

    init {
        // Reload whenever the stored mesh changes size (Scale check rewrites it).
        viewModelScope.launch {
            repository.scan(scanId).map { it?.size }.distinctUntilChanged().collect {
                mesh.value = repository.loadMesh(scanId)
            }
        }
    }

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) viewModelScope.launch { repository.rename(scanId, trimmed) }
    }

    fun delete() {
        viewModelScope.launch { repository.delete(scanId) }
    }
}
