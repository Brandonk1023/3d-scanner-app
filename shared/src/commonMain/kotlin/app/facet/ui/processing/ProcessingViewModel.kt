package app.facet.ui.processing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.facet.data.Scan
import app.facet.data.ScanRepository
import app.facet.platform.BackgroundProcessor
import app.facet.platform.ProcessingProgress
import app.facet.platform.ProcessingStage
import app.facet.platform.ProcessingStatus
import app.facet.platform.StorageInfo
import app.facet.scan.ScanTuning
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which of the three processing pages is showing (Processing.png, Processing-Failed.png, Low-Storage.png). */
enum class ProcessingPhase { LOADING, LOW_STORAGE, RUNNING, FAILED }

enum class StageState { DONE, CURRENT, TODO }

/** The mono text at the end of a stage row: "214 photos", "0:48", "3 of 4". */
sealed interface StageDetail {
    data class Photos(val count: Int) : StageDetail
    data class Elapsed(val ms: Long) : StageDetail
    data class Items(val done: Int, val total: Int) : StageDetail
}

data class StageRow(val stage: ProcessingStage, val state: StageState, val detail: StageDetail?)

/** Low-storage numbers: free space, what High detail needs, and saved photos of *other* scans. */
data class LowStorage(val freeBytes: Long, val neededBytes: Long, val reclaimableBytes: Long)

data class ProcessingUiState(
    val phase: ProcessingPhase = ProcessingPhase.LOADING,
    val scan: Scan? = null,
    val missing: Boolean = false,
    val progress: ProcessingProgress = ProcessingProgress(),
    val lowStorage: LowStorage? = null,
    val failure: ProcessingStatus.Failed? = null,
    /** One-shot: leave for the viewer (finished, cancelled, Use / Keep Quick result). */
    val openViewer: Boolean = false,
) {
    val stages: List<StageRow> get() = stageRows(progress)
}

/** Stage list rows for [progress]: done ones show photos (stage 1) or time taken, the current one "k of N". */
fun stageRows(progress: ProcessingProgress): List<StageRow> = ProcessingStage.entries.map { stage ->
    val state = when {
        stage.ordinal < progress.stage.ordinal -> StageState.DONE
        stage == progress.stage -> StageState.CURRENT
        else -> StageState.TODO
    }
    val detail = when (state) {
        StageState.DONE -> when {
            stage == ProcessingStage.ALIGNING_PHOTOS && progress.photoCount != null -> StageDetail.Photos(progress.photoCount)
            else -> progress.stageDurationsMs.getOrNull(stage.ordinal)?.let { StageDetail.Elapsed(it) }
        }
        StageState.CURRENT -> if (progress.itemsTotal > 1) StageDetail.Items(progress.itemsDone, progress.itemsTotal) else null
        StageState.TODO -> null
    }
    StageRow(stage, state, detail)
}

/**
 * The `processing/{scanId}` route. With [start], High detail is started after the storage check
 * (viewer button, Reprocess, Finish → High detail); without it the screen only follows a run
 * (gallery card, notification).
 */
class ProcessingViewModel(
    private val scanId: String,
    private val start: Boolean,
    private val repository: ScanRepository,
    private val processor: BackgroundProcessor,
    private val storage: StorageInfo,
    private val tuning: ScanTuning = ScanTuning.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(ProcessingUiState())
    val state: StateFlow<ProcessingUiState> = _state.asStateFlow()

    private var observing: Job? = null

    init {
        viewModelScope.launch {
            val scan = repository.scan(scanId).first()
            if (scan == null) {
                _state.update { it.copy(missing = true) }
                return@launch
            }
            _state.update { it.copy(scan = scan) }
            if (start && scan.processingPercent == null) checkAndStart() else observe()
        }
        // Keep the name and the Quick preview's details current (rename elsewhere, completion).
        viewModelScope.launch { repository.scan(scanId).collect { s -> if (s != null) _state.update { it.copy(scan = s) } } }
    }

    /** Try again (failure screen). */
    fun retry() {
        viewModelScope.launch { checkAndStart() }
    }

    /** Use Quick result / Keep Quick result: the Quick mesh stays, go to the viewer. */
    fun useQuick() {
        viewModelScope.launch {
            repository.setProcessing(scanId, null)
            _state.update { it.copy(openViewer = true) }
        }
    }

    /** Cancel processing: stop the work, keep the Quick result. */
    fun cancel() {
        observing?.cancel()
        processor.cancel(scanId)
        viewModelScope.launch {
            repository.setProcessing(scanId, null)
            _state.update { it.copy(openViewer = true) }
        }
    }

    fun consumeOpenViewer() = _state.update { it.copy(openViewer = false) }

    private suspend fun checkAndStart() {
        val scan = _state.value.scan ?: return
        val free = storage.freeBytes()
        val needed = tuning.storage.highDetailEstimateBytes
        if (free < needed) {
            val photos = repository.usage.first().savedPhotoBytes
            _state.update {
                it.copy(
                    phase = ProcessingPhase.LOW_STORAGE,
                    lowStorage = LowStorage(free, needed, (photos - scan.photoBytes).coerceAtLeast(0)),
                )
            }
            return
        }
        _state.update { it.copy(phase = ProcessingPhase.RUNNING, progress = ProcessingProgress(), failure = null, lowStorage = null) }
        // The gallery card shows "Processing 0%" straight away, before the work is picked up.
        repository.setProcessing(scanId, 0)
        processor.start(scanId)
        observe()
    }

    private fun observe() {
        observing?.cancel()
        observing = viewModelScope.launch {
            processor.observe(scanId).collect { status ->
                when (status) {
                    is ProcessingStatus.Running ->
                        _state.update { it.copy(phase = ProcessingPhase.RUNNING, progress = status.progress, failure = null) }
                    is ProcessingStatus.Failed ->
                        _state.update { it.copy(phase = ProcessingPhase.FAILED, failure = status) }
                    // Done, or nothing to follow (cancelled elsewhere, long finished): show the scan.
                    ProcessingStatus.Succeeded, ProcessingStatus.Idle -> _state.update { it.copy(openViewer = true) }
                }
            }
        }
    }
}
