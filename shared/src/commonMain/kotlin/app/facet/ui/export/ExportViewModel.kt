package app.facet.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.facet.data.Scan
import app.facet.data.ScanRepository
import app.facet.export.ExportFormat
import app.facet.export.sanitizeFileName
import app.facet.mesh.Mesh
import app.facet.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ExportUiState(
    val loading: Boolean = true,
    val scan: Scan? = null,
    val mesh: Mesh? = null,
    /** What's in the text field (no extension). */
    val fileName: String = "",
    val format: ExportFormat = ExportFormat.THREE_MF,
    val sizes: Map<ExportFormat, Long> = emptyMap(),
) {
    /** The name the file will actually get, e.g. "Mug_01.3mf". */
    val exportFileName: String
        get() = sanitizeFileName(fileName, fallback = scan?.name ?: "scan") + "." + format.extension
}

class ExportViewModel(
    scanId: String,
    private val repository: ScanRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val mesh = MutableStateFlow<Mesh?>(null)
    /** null = not edited yet → defaults to the scan name. */
    private val editedName = MutableStateFlow<String?>(null)

    val state: StateFlow<ExportUiState> =
        combine(repository.scan(scanId), mesh, editedName, settings.settings) { scan, m, name, s ->
            ExportUiState(
                loading = false,
                scan = scan,
                mesh = m,
                fileName = name ?: scan?.name.orEmpty(),
                format = s.lastExportFormat,
                sizes = m?.let { ExportFormat.entries.associateWith { f -> f.estimatedBytes(it) } }.orEmpty(),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ExportUiState())

    init {
        viewModelScope.launch { mesh.value = repository.loadMesh(scanId) }
    }

    fun setFileName(value: String) { editedName.value = value }

    /** Remembered for next time (AppSettings.lastExportFormat). */
    fun setFormat(format: ExportFormat) {
        viewModelScope.launch { settings.update { it.copy(lastExportFormat = format) } }
    }
}
