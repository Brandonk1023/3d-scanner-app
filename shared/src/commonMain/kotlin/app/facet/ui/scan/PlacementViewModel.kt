package app.facet.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.facet.platform.StorageInfo
import app.facet.scan.BoxAxis
import app.facet.scan.PlacedBox
import app.facet.scan.ScanTuning
import app.facet.scan.ScanType
import app.facet.settings.LengthUnit
import app.facet.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** The first two states of the scan state machine (docs/04): FindingSurface → PlacingBox. */
enum class PlacementStage { FINDING_SURFACE, FITTING_BOX }

/** What Start scan hands to capture. */
data class ScanSetup(val box: PlacedBox, val scanType: ScanType)

data class LowStorage(val freeBytes: Long, val neededBytes: Long)

data class PlacementUiState(
    val stage: PlacementStage = PlacementStage.FINDING_SURFACE,
    /** A horizontal plane is tracked ("Surface found"). */
    val surfaceFound: Boolean = false,
    val box: PlacedBox? = null,
    /** true when the box came from depth points, false when it's the default cube. */
    val boxFitted: Boolean = false,
    val scanType: ScanType = ScanType.FULL_OBJECT,
    val units: LengthUnit = LengthUnit.MILLIMETRES,
    val torchOn: Boolean = false,
    /** Fit to object is waiting for the AR layer. */
    val fitting: Boolean = false,
    /** One-shot: Fit to object couldn't see the object. */
    val fitFailed: Boolean = false,
    val lowStorage: LowStorage? = null,
    /** One-shot: Start scan passed its checks. */
    val started: ScanSetup? = null,
)

/**
 * Placement screen logic (docs/04 §1). The AR layer reports surfaces and fitted boxes; the UI
 * reports handle drags, already converted to millimetres in the plane frame.
 */
class PlacementViewModel(
    settings: SettingsRepository,
    private val storage: StorageInfo,
    private val tuning: ScanTuning = ScanTuning.Default,
) : ViewModel() {
    private val local = MutableStateFlow(PlacementUiState())

    val state: StateFlow<PlacementUiState> =
        combine(local, settings.settings) { s, app -> s.copy(units = app.units) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, local.value)

    fun onSurfaceFound(found: Boolean) = local.update { if (it.surfaceFound == found) it else it.copy(surfaceFound = found) }

    /** A tap placed the box. [fitted] = sized from depth points rather than the default cube. */
    fun onBoxPlaced(box: PlacedBox, fitted: Boolean) = local.update {
        it.copy(stage = PlacementStage.FITTING_BOX, box = box.clamped(tuning.placement), boxFitted = fitted, fitting = false)
    }

    /** Back from Fit box: pick a different object. */
    fun clearBox() = local.update {
        it.copy(stage = PlacementStage.FINDING_SURFACE, box = null, boxFitted = false, fitting = false)
    }

    fun resize(axis: BoxAxis, side: Int, deltaMm: Double) = editBox { it.resized(axis, side, deltaMm, tuning.placement) }

    /** Bottom handle: put the base centre at ([xMm], [yMm]) in the plane frame. */
    fun moveTo(xMm: Double, yMm: Double) = editBox { it.copy(centerX = xMm, centerY = yMm) }

    fun rotate(deltaDeg: Double) = editBox { it.rotated(deltaDeg) }

    fun setScanType(type: ScanType) = local.update { it.copy(scanType = type) }

    fun toggleTorch() = local.update { it.copy(torchOn = !it.torchOn) }

    fun onFitStarted() = local.update { it.copy(fitting = true) }

    /** Result of Fit to object; null = nothing recognisable near the box, keep the current one. */
    fun onFitResult(box: PlacedBox?) = local.update {
        if (box == null) it.copy(fitting = false, fitFailed = true)
        else it.copy(fitting = false, box = box.clamped(tuning.placement), boxFitted = true)
    }

    fun consumeFitFailed() = local.update { it.copy(fitFailed = false) }

    /** Start scan: needs a box and enough free space for the scan's working files. */
    fun startScan() {
        val s = local.value
        val box = s.box ?: return
        val needed = tuning.storage.minFreeToStartScanBytes
        val free = storage.freeBytes()
        local.update {
            if (free < needed) it.copy(lowStorage = LowStorage(free, needed))
            else it.copy(started = ScanSetup(box, s.scanType))
        }
    }

    fun dismissLowStorage() = local.update { it.copy(lowStorage = null) }

    fun consumeStarted() = local.update { it.copy(started = null) }

    private fun editBox(change: (PlacedBox) -> PlacedBox) = local.update { s ->
        val box = s.box ?: return@update s
        s.copy(box = change(box))
    }
}
