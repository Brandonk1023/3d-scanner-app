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

/** Export to user-visible storage (Android: MediaStore Downloads/Facet; iOS: Files) or the share sheet. */
interface FileExporter {
    /** Saves [bytes] as [fileName] and returns a user-readable location, e.g. "Downloads/Facet/Mug_01.3mf". */
    suspend fun save(fileName: String, mimeType: String, bytes: ByteArray): String
    suspend fun share(fileName: String, mimeType: String, bytes: ByteArray)
}

/** High detail processing that survives leaving the app (WorkManager / BGProcessingTask). */
interface BackgroundProcessor {
    fun start(scanId: String)
    fun cancel(scanId: String)
    fun observe(scanId: String): Flow<ProcessingStatus>
}

enum class ProcessingStage { ALIGNING_PHOTOS, DEPTH_MAPS, BUILDING_FINE_MESH, CLEANUP }

sealed interface ProcessingStatus {
    data class Running(val stage: ProcessingStage, val progress: Float, val remainingSeconds: Int?) : ProcessingStatus
    data object Succeeded : ProcessingStatus
    data class Failed(val reason: String, val technicalLog: String) : ProcessingStatus
}
