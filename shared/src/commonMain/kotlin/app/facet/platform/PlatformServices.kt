package app.facet.platform

import app.facet.scan.GuidanceMessage
import app.facet.scan.ScanFeedback
import kotlinx.coroutines.flow.Flow

// Platform services the shared code depends on. Implementations live in androidMain / iosMain
// (or in the app modules when they need UI/OS objects). Phase 0 defines the contracts only;
// methods are filled in by the phase that first needs them.

/** Vibration patterns per guidance message (design/android/scan/Haptics.kt; iOS mirrors with Core Haptics). */
interface HapticsPlayer {
    fun play(message: GuidanceMessage)
    fun play(feedback: ScanFeedback)
    fun cancel()
}

/** Whether this device can scan (ARCore Depth API / ARKit scene depth). */
interface DeviceCapabilities {
    suspend fun check(): DeviceSupport
}

sealed interface DeviceSupport {
    data object Supported : DeviceSupport
    /** AR services missing or outdated but installable (ARCore via Play Store). */
    data object NeedsInstallOrUpdate : DeviceSupport
    /** No depth support at all: show the Unsupported device screen. [deviceModel] for the copy. */
    data class Unsupported(val deviceModel: String) : DeviceSupport
}

/** Free space and the app's own usage split (Settings › Storage, low-storage checks). */
interface StorageInfo {
    fun freeBytes(): Long
    fun usage(): StorageUsage
}

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
