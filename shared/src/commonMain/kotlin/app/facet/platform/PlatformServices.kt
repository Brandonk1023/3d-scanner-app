package app.facet.platform

import app.facet.scan.GuidanceMessage
import app.facet.scan.ScanFeedback
import kotlinx.coroutines.flow.Flow

// Platform services the shared code depends on. Implementations live in androidMain / iosMain
// (or in the app modules when they need UI/OS objects). Methods are filled in by the phase that
// first needs them.

/** Vibration patterns per guidance message (design/android/scan/Haptics.kt; iOS mirrors with Core Haptics). */
interface HapticsPlayer {
    fun play(message: GuidanceMessage)
    fun play(feedback: ScanFeedback)
    fun cancel()
}

/** Whether this device can scan (ARCore Depth API / ARKit scene depth). */
interface DeviceCapabilities {
    suspend fun check(): DeviceCheckResult
}

/** AR services: Google Play Services for AR on Android; always [READY] on iOS. */
enum class ArServicesStatus { READY, NEEDS_INSTALL, NEEDS_UPDATE, UNSUPPORTED, UNKNOWN }

/** Depth API support. [UNKNOWN] until AR services are installed and up to date. */
enum class DepthStatus { SUPPORTED, UNSUPPORTED, UNKNOWN }

enum class DeviceSupport {
    SUPPORTED,

    /** AR services missing or outdated but installable (ARCore via Play Store). */
    NEEDS_INSTALL_OR_UPDATE,

    /** No AR or no depth support: show the Unsupported device screen. */
    UNSUPPORTED,

    /** The check itself failed or timed out: offer Check again. */
    CHECK_FAILED,
}

data class DeviceCheckResult(
    val arServices: ArServicesStatus,
    val depth: DepthStatus,
    /** For the Unsupported copy, e.g. "Samsung SM-S928B". */
    val deviceModel: String,
) {
    val support: DeviceSupport
        get() = when {
            arServices == ArServicesStatus.UNSUPPORTED || depth == DepthStatus.UNSUPPORTED -> DeviceSupport.UNSUPPORTED
            arServices == ArServicesStatus.NEEDS_INSTALL || arServices == ArServicesStatus.NEEDS_UPDATE ->
                DeviceSupport.NEEDS_INSTALL_OR_UPDATE
            arServices == ArServicesStatus.READY && depth == DepthStatus.SUPPORTED -> DeviceSupport.SUPPORTED
            else -> DeviceSupport.CHECK_FAILED
        }
}

/** Free space on the volume that holds the app's files (low-storage checks, Settings › Storage). */
interface StorageInfo {
    fun freeBytes(): Long
}

/** The app's own usage split (Settings › Storage). */
data class StorageUsage(val scanCount: Int, val modelBytes: Long, val savedPhotoBytes: Long) {
    val totalBytes: Long get() = modelBytes + savedPhotoBytes
}

/**
 * Export to user-visible storage (Android: MediaStore Downloads/Facet; iOS: Files) or the share
 * sheet. [sourcePath] is a finished file in the app's cache; [fileName] is what the user sees.
 */
interface FileExporter {
    /** Copies the file out. The saved name can differ from [fileName] if that name was taken. */
    suspend fun save(sourcePath: String, fileName: String, mimeType: String): SavedFile

    /** Opens the system share sheet for the file. */
    fun share(sourcePath: String, fileName: String, mimeType: String)

    /** Opens a saved file in another app (snackbar "Open"); false when no app can open it. */
    fun open(file: SavedFile): Boolean
}

/** A file saved by [FileExporter.save]. */
data class SavedFile(
    /** User-readable location, e.g. "Downloads/Facet/Mug_01.3mf". */
    val location: String,
    /** Platform handle used by [FileExporter.open] (Android: content URI). */
    val uri: String,
    val mimeType: String,
)

/**
 * High detail processing that survives leaving the app (Android: WorkManager foreground work;
 * iOS: BGProcessingTask, Phase 9). The work itself is [app.facet.processing.HighDetailProcessor].
 */
interface BackgroundProcessor {
    /**
     * Queues High detail for [scanId]; does nothing if it is already queued or running. Returns once
     * queued, so [observe] afterwards follows the new run rather than an earlier one.
     */
    suspend fun start(scanId: String)
    fun cancel(scanId: String)

    /** The latest run for [scanId]; [ProcessingStatus.Idle] when there is none. */
    fun observe(scanId: String): Flow<ProcessingStatus>
}

/** The four stages shown on the Processing screen, in order. */
enum class ProcessingStage { ALIGNING_PHOTOS, DEPTH_MAPS, BUILDING_FINE_MESH, CLEANUP }

/** Why High detail stopped, in terms the failure screen can explain. */
enum class FailureReason {
    /** Native allocation failed or the process ran out of memory. */
    OUT_OF_MEMORY,

    /** No usable keyframes (none saved, or cleared). */
    NO_PHOTOS,

    /** Keyframes from an app version that didn't record the scan box (capture.json). */
    OLD_PHOTOS,

    /** Fusion produced no surface. */
    EMPTY_RESULT,

    /** Reading or writing files failed (usually a full disk). */
    STORAGE,

    /** The system stopped the work and it couldn't resume. */
    INTERRUPTED,
    UNKNOWN,
}

sealed interface ProcessingStatus {
    /** No run known for this scan (never started, cancelled, or long finished). */
    data object Idle : ProcessingStatus

    data class Running(val progress: ProcessingProgress) : ProcessingStatus
    data object Succeeded : ProcessingStatus

    /** [stage] is where it stopped (null = before it began); [technicalLog] is for "Copy error details". */
    data class Failed(val reason: FailureReason, val stage: ProcessingStage?, val technicalLog: String) : ProcessingStatus
}

/**
 * A running pass, as the Processing screen and the notification show it.
 *
 * - [overall]: 0..1 across all stages (weighted by ScanTuning.HighDetail.stageWeights)
 * - [stageDurationsMs]: how long each finished stage took (index = stage ordinal)
 * - [itemsDone] / [itemsTotal]: photos handled in the current stage (0 / 0 = no count)
 */
data class ProcessingProgress(
    val stage: ProcessingStage = ProcessingStage.ALIGNING_PHOTOS,
    val overall: Float = 0f,
    val remainingSeconds: Int? = null,
    val photoCount: Int? = null,
    val stageDurationsMs: List<Long> = emptyList(),
    val itemsDone: Int = 0,
    val itemsTotal: Int = 0,
) {
    val percent: Int get() = (overall * 100).toInt().coerceIn(0, 100)
}
