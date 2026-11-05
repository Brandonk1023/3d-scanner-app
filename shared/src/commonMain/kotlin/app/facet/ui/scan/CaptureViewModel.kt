package app.facet.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.facet.geometry.Vec3
import app.facet.platform.HapticsPlayer
import app.facet.scan.CameraView
import app.facet.scan.CoverageBand
import app.facet.scan.CoverageModel
import app.facet.scan.CoverageState
import app.facet.scan.GuidanceEngine
import app.facet.scan.GuidanceInput
import app.facet.scan.GuidanceMessage
import app.facet.scan.MotionTracker
import app.facet.scan.ResultMode
import app.facet.scan.ScanFeedback
import app.facet.scan.ScanTuning
import app.facet.scan.ScanType
import app.facet.scan.TrackingState
import app.facet.scan.cameraView
import app.facet.settings.AppSettings
import app.facet.settings.DefaultResult
import app.facet.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Capture ⇄ Paused, plus Interrupted (app left) — docs/04 state machine. */
enum class CapturePhase { CAPTURING, PAUSED, INTERRUPTED }

/** The one sheet over the capture screen, if any. */
enum class CaptureSheet { INCOMPLETE, CHOOSE_RESULT, INTERRUPTED }

/** One AR frame, as the platform AR layer reports it. */
data class CaptureFrame(
    val timestampNs: Long,
    val tracking: TrackingState,
    /** Camera position in the plane frame (mm); null when not tracking. */
    val camera: Vec3?,
    /** 0..1 of the box's on-screen area that's in view. */
    val boxInView: Float,
    /** Ambient light 0..1 (ARCore pixel intensity), null if unknown. */
    val lightIntensity: Float?,
    /** The box anchor is tracking (needed to resume after an interruption). */
    val anchorTracking: Boolean,
)

data class CaptureUiState(
    val setup: ScanSetup,
    val phase: CapturePhase = CapturePhase.CAPTURING,
    val sheet: CaptureSheet? = null,
    val elapsedMs: Long = 0,
    val coverage: CoverageState,
    /** The guidance banner. */
    val message: GuidanceMessage? = null,
    /** "Low angles done" toast while non-null. */
    val toast: CoverageBand? = null,
    /** Current camera heading around the box (dial needle, ring marker) and band; null until tracked. */
    val headingDeg: Double? = null,
    val band: CoverageBand? = null,
    val meshVisible: Boolean = true,
    /** Interrupted sheet: the AR session found the box again. */
    val resumeAvailable: Boolean = false,
    /** Incomplete sheet: the weakest band under the limit, or null when overall coverage is what's low. */
    val missingBand: CoverageBand? = null,
    /** Choose result: show "Best for small parts" on High detail. */
    val highDetailBadge: Boolean = false,
    /** Finish needs at least this much coverage. */
    val finishEnabledAt: Float = 0.15f,
    val quickEstimateSec: Int = 0,
    val highDetailEstimateMin: Int = 0,
    /** One-shot: the user finished with this result. */
    val finished: ResultMode? = null,
    /** One-shot: the user discarded the scan. */
    val discarded: Boolean = false,
) {
    val trackingLost: Boolean get() = message == GuidanceMessage.TRACKING_LOST

    /** Depth is fused only while actively capturing with good tracking. */
    val fusing: Boolean get() = phase == CapturePhase.CAPTURING && sheet == null && !trackingLost

    val finishEnabled: Boolean get() = coverage.overall >= finishEnabledAt
}

/**
 * The capture part of the scan session (docs/04 §2–3): coverage, guidance, timer, pause and
 * interruption, Finish checks and the result choice. The platform AR layer feeds it frames and
 * fusion results; it decides what the user sees and feels.
 */
class CaptureViewModel(
    setup: ScanSetup,
    private val settings: SettingsRepository,
    private val haptics: HapticsPlayer,
    private val tuning: ScanTuning = ScanTuning.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(
        CaptureUiState(
            setup = setup,
            coverage = CoverageState.empty(tuning.coverage.azimuthSectors),
            highDetailBadge = setup.box.size.diagonalMm < tuning.finish.highDetailBadgeMaxDiagonalMm,
            finishEnabledAt = tuning.finish.finishEnabledAtCoverage,
            quickEstimateSec = tuning.reconstruction.quickEstimateSec,
            highDetailEstimateMin = tuning.reconstruction.highDetailEstimateMin,
        ),
    )
    val state: StateFlow<CaptureUiState> = _state

    private var appSettings = AppSettings()
    private var coverage: CoverageModel? = null
    private val guidance = GuidanceEngine(tuning)
    private val motion = MotionTracker()
    private var lastFrame: CaptureFrame? = null
    private var lastView: CameraView? = null
    private var toastUntilMs = 0L

    init {
        viewModelScope.launch { settings.settings.collect { appSettings = it } }
    }

    // ---- From the AR layer -----------------------------------------------------------------

    fun onFrame(frame: CaptureFrame) {
        val prev = lastFrame
        lastFrame = frame
        val nowMs = frame.timestampNs / 1_000_000
        val s = _state.value
        val box = s.setup.box

        val view = frame.camera?.let { cameraView(box, it) }
        lastView = view
        if (view != null && frame.camera != null) {
            if (coverage == null) coverage = CoverageModel(tuning, s.setup.scanType, view.azimuthDeg)
            motion.update(nowMs, frame.camera, view.azimuthDeg)
        }
        val band = view?.let { tuning.bandForElevation(it.elevationDeg) }

        val active = s.phase == CapturePhase.CAPTURING && s.sheet == null
        val dt = if (prev != null && active) ((frame.timestampNs - prev.timestampNs) / 1_000_000).coerceIn(0, 500) else 0

        val message = if (active) {
            val out = guidance.update(
                GuidanceInput(
                    nowMs = nowMs,
                    tracking = frame.tracking,
                    boxInView = frame.boxInView,
                    distanceMm = view?.distanceMm ?: 0.0,
                    distanceRangeMm = tuning.distanceRangeMm(box.size),
                    orbitSpeedDegPerSec = motion.orbitDegPerSec,
                    linearSpeedMmPerSec = motion.linearMmPerSec,
                    blurScore = null, // TODO: blur metric (keyframe sharpness) once calibrated
                    lightIntensity = frame.lightIntensity,
                    band = band,
                    coverage = s.coverage,
                ),
            )
            if (appSettings.hapticGuidance) out.haptics.forEach(haptics::play)
            out.message
        } else {
            null
        }

        _state.update {
            it.copy(
                elapsedMs = it.elapsedMs + dt,
                message = message,
                headingDeg = view?.azimuthDeg ?: it.headingDeg,
                band = band,
                toast = if (nowMs < toastUntilMs) it.toast else null,
                resumeAvailable = frame.anchorTracking && frame.tracking == TrackingState.TRACKING,
            )
        }
    }

    /**
     * A depth frame was fused. It counts toward coverage only if the frame was good: tracking,
     * slow enough, box mostly in view, confident depth (docs/04 §2 coverage model).
     */
    fun onDepthFused(meanConfidence: Float) {
        val s = _state.value
        if (!s.fusing) return
        val frame = lastFrame ?: return
        val view = lastView ?: return
        val model = coverage ?: return
        val c = tuning.coverage
        val g = tuning.guidance
        val accepted = frame.tracking == TrackingState.TRACKING &&
            motion.orbitDegPerSec <= g.maxOrbitSpeedDegPerSec &&
            motion.linearMmPerSec <= g.maxLinearSpeedMmPerSec &&
            frame.boxInView >= c.minBoxInViewFraction &&
            meanConfidence >= c.minDepthConfidence
        val band = tuning.bandForElevation(view.elevationDeg)
        if (!accepted || band == null) return

        val update = model.addAcceptedFrame(band, view.azimuthDeg)
        if (appSettings.hapticGuidance) {
            when {
                update.bandCompleted != null -> haptics.play(ScanFeedback.RING_COMPLETE)
                update.sectorCaptured -> haptics.play(ScanFeedback.SEGMENT_CAPTURED)
            }
        }
        val nowMs = frame.timestampNs / 1_000_000
        if (update.bandCompleted != null) toastUntilMs = nowMs + g.ringCompleteToastMs
        _state.update {
            it.copy(coverage = model.state(), toast = update.bandCompleted ?: it.toast)
        }
    }

    // ---- User actions ------------------------------------------------------------------------

    fun togglePause() = _state.update {
        when (it.phase) {
            CapturePhase.CAPTURING -> it.copy(phase = CapturePhase.PAUSED, message = null)
            CapturePhase.PAUSED -> it.copy(phase = CapturePhase.CAPTURING).also { resetMotion() }
            CapturePhase.INTERRUPTED -> it
        }
    }

    fun toggleMesh() = _state.update { it.copy(meshVisible = !it.meshVisible) }

    /** The app went to the background mid-scan. */
    fun onInterrupted() = _state.update {
        if (it.sheet == CaptureSheet.CHOOSE_RESULT) it
        else it.copy(phase = CapturePhase.INTERRUPTED, sheet = CaptureSheet.INTERRUPTED, message = null)
    }

    fun resumeAfterInterruption() {
        if (!_state.value.resumeAvailable) return
        resetMotion()
        _state.update { it.copy(phase = CapturePhase.CAPTURING, sheet = null) }
    }

    fun finish() {
        val s = _state.value
        if (!s.finishEnabled || s.sheet != null) return
        val f = tuning.finish
        val weakest = CoverageBand.entries.minBy { s.coverage.band(it) }
        val incomplete = s.setup.scanType == ScanType.FULL_OBJECT &&
            (s.coverage.overall < f.incompleteOverallBelow || s.coverage.band(weakest) < f.incompleteAnyBandBelow)
        if (incomplete) {
            _state.update {
                it.copy(
                    sheet = CaptureSheet.INCOMPLETE,
                    message = null,
                    missingBand = weakest.takeIf { b -> s.coverage.band(b) < f.incompleteAnyBandBelow },
                )
            }
        } else {
            chooseResultStep()
        }
    }

    fun keepScanning() {
        resetMotion()
        _state.update { it.copy(sheet = null) }
    }

    fun finishAnyway() = chooseResultStep()

    /** Interrupted sheet → "Finish with what I have". */
    fun finishWithWhatIHave() = chooseResultStep()

    fun chooseResult(mode: ResultMode, remember: Boolean) {
        if (remember) {
            viewModelScope.launch {
                settings.update {
                    it.copy(defaultResult = if (mode == ResultMode.QUICK) DefaultResult.QUICK else DefaultResult.HIGH_DETAIL)
                }
            }
        }
        _state.update { it.copy(sheet = null, finished = mode) }
    }

    /** Back from Choose result (or dismissing the sheet): keep scanning. */
    fun dismissSheet() {
        if (_state.value.sheet == CaptureSheet.INTERRUPTED) return
        keepScanning()
    }

    fun discard() = _state.update { it.copy(discarded = true, sheet = null) }

    private fun chooseResultStep() {
        when (appSettings.defaultResult) {
            DefaultResult.ASK -> _state.update { it.copy(sheet = CaptureSheet.CHOOSE_RESULT, message = null) }
            DefaultResult.QUICK -> _state.update { it.copy(sheet = null, finished = ResultMode.QUICK) }
            DefaultResult.HIGH_DETAIL -> _state.update { it.copy(sheet = null, finished = ResultMode.HIGH_DETAIL) }
        }
    }

    private fun resetMotion() {
        motion.reset()
        guidance.reset()
    }
}
