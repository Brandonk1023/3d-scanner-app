package app.facet.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.facet.data.Scan
import app.facet.data.ScanRepository
import app.facet.export.ExportFormat
import app.facet.export.sanitizeFileName
import app.facet.mesh.Mesh
import app.facet.mesh.PipelineStatus
import app.facet.platform.FileExporter
import app.facet.platform.SavedFile
import app.facet.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/** Writes [mesh] as [format] to [path] (the native STL / 3MF writers, via `ScanPipeline.export`). */
fun interface ExportWriter {
    fun write(mesh: Mesh, format: ExportFormat, path: String, objectName: String): PipelineStatus
}

enum class ExportAction { SAVE, SHARE }

/** One-shot results for the snackbar; the screen calls [ExportViewModel.consumeEvent] once shown. */
sealed interface ExportEvent {
    data class Saved(val file: SavedFile) : ExportEvent
    data class Failed(val action: ExportAction) : ExportEvent
}

data class ExportUiState(
    val loading: Boolean = true,
    val scan: Scan? = null,
    val mesh: Mesh? = null,
    /** What's in the text field (no extension). */
    val fileName: String = "",
    val format: ExportFormat = ExportFormat.THREE_MF,
    /** STL is exact; 3MF is an estimate until the real file has been written once. */
    val sizes: Map<ExportFormat, Long> = emptyMap(),
    /** Save or Share in progress (buttons disabled). */
    val busy: ExportAction? = null,
    val event: ExportEvent? = null,
) {
    /** The name the file will actually get, e.g. "Mug_01.3mf". */
    val exportFileName: String
        get() = sanitizeFileName(fileName, fallback = scan?.name ?: "scan") + "." + format.extension

    /** The 3MF object name: what the user typed, else the scan name. */
    val objectName: String
        get() = fileName.trim().ifEmpty { scan?.name ?: "scan" }
}

class ExportViewModel(
    scanId: String,
    private val repository: ScanRepository,
    private val settings: SettingsRepository,
    private val writer: ExportWriter,
    private val exporter: FileExporter,
    private val fs: FileSystem,
    /** Scratch folder for export files (Android: inside the FileProvider's cache path). */
    private val workDir: Path,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val mesh = MutableStateFlow<Mesh?>(null)
    /** null = not edited yet → defaults to the scan name. */
    private val editedName = MutableStateFlow<String?>(null)
    private val measured = MutableStateFlow<Map<ExportFormat, Long>>(emptyMap())
    private val busy = MutableStateFlow<ExportAction?>(null)
    private val event = MutableStateFlow<ExportEvent?>(null)

    private val base = combine(repository.scan(scanId), mesh, editedName, settings.settings, measured) { scan, m, name, s, sizes ->
        ExportUiState(
            loading = false,
            scan = scan,
            mesh = m,
            fileName = name ?: scan?.name.orEmpty(),
            format = s.lastExportFormat,
            sizes = m?.let { ExportFormat.entries.associateWith { f -> sizes[f] ?: f.estimatedBytes(it) } }.orEmpty(),
        )
    }

    val state: StateFlow<ExportUiState> =
        combine(base, busy, event) { s, b, e -> s.copy(busy = b, event = e) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ExportUiState())

    init {
        viewModelScope.launch {
            val m = repository.loadMesh(scanId) ?: return@launch
            mesh.value = m
            measure3mf(m)
        }
    }

    fun setFileName(value: String) { editedName.value = value }

    /** Remembered for next time (AppSettings.lastExportFormat). */
    fun setFormat(format: ExportFormat) {
        viewModelScope.launch { settings.update { it.copy(lastExportFormat = format) } }
    }

    /** Writes the file and copies it to user-visible storage; [ExportEvent.Saved] on success. */
    fun save() = run(ExportAction.SAVE) { path, s ->
        event.value = ExportEvent.Saved(exporter.save(path, s.exportFileName, s.format.mimeType))
    }

    /** Writes the file and opens the share sheet. */
    fun share() = run(ExportAction.SHARE) { path, s ->
        exporter.share(path, s.exportFileName, s.format.mimeType)
    }

    fun openSaved(file: SavedFile): Boolean = exporter.open(file)

    fun consumeEvent() { event.value = null }

    private fun run(action: ExportAction, deliver: suspend (path: String, ExportUiState) -> Unit) {
        val s = state.value
        val m = s.mesh ?: return
        if (busy.value != null) return
        busy.value = action
        viewModelScope.launch {
            try {
                val path = withContext(work) { writeFresh(m, s.format, s.exportFileName, s.objectName) }
                if (path == null) {
                    event.value = ExportEvent.Failed(action)
                } else {
                    if (s.format == ExportFormat.THREE_MF) measured.update { it + (ExportFormat.THREE_MF to fs.size(path)) }
                    deliver(path.toString(), s)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                event.value = ExportEvent.Failed(action)
            } finally {
                busy.value = null
            }
        }
    }

    /** Clears [workDir] (the previous export is no longer needed) and writes one file into it. */
    private fun writeFresh(mesh: Mesh, format: ExportFormat, fileName: String, objectName: String): Path? {
        fs.deleteRecursively(workDir)
        fs.createDirectories(workDir)
        val path = workDir / fileName
        val status = writer.write(mesh, format, path.toString(), objectName)
        return path.takeIf { status == PipelineStatus.OK && fs.exists(path) }
    }

    /** The export sheet shows the real 3MF size (it's compressed, so it can't be computed up front). */
    private suspend fun measure3mf(mesh: Mesh) {
        val bytes = runCatching {
            withContext(work) {
                fs.createDirectories(workDir)
                val probe = workDir / ".size-probe.3mf"
                try {
                    if (writer.write(mesh, ExportFormat.THREE_MF, probe.toString(), "probe") == PipelineStatus.OK) fs.size(probe) else null
                } finally {
                    fs.delete(probe)
                }
            }
        }.getOrNull() ?: return
        measured.update { if (ExportFormat.THREE_MF in it) it else it + (ExportFormat.THREE_MF to bytes) }
    }

    private fun FileSystem.size(path: Path): Long = metadata(path).size ?: 0L
}
