package app.facet.data

import app.facet.geometry.BoxSize
import app.facet.mesh.Mesh
import app.facet.platform.StorageUsage
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

enum class ResultQuality { QUICK, HIGH_DETAIL }

/** What the gallery chip and filters show. Exactly one per scan. */
enum class ScanStatus { QUICK, HIGH_DETAIL, PROCESSING }

/** A saved scan (Room-backed from Phase 5; Phase 1 uses [FakeScanRepository]). */
data class Scan(
    val id: String,
    val name: String,
    val createdAt: Instant,
    val quality: ResultQuality,
    /** 0..100 while High detail processing runs, else null. */
    val processingPercent: Int? = null,
    val size: BoxSize,
    val triangleCount: Int,
    /** What the scale was verified against ("1 € coin", "ruler"), or null if unchecked. */
    val scaleReference: String? = null,
    /** Keyframes kept for High detail; cleared by Settings › Clear saved photos. */
    val hasSavedPhotos: Boolean = true,
    val modelBytes: Long = 0,
    val photoBytes: Long = 0,
) {
    val status: ScanStatus
        get() = when {
            processingPercent != null -> ScanStatus.PROCESSING
            quality == ResultQuality.HIGH_DETAIL -> ScanStatus.HIGH_DETAIL
            else -> ScanStatus.QUICK
        }

    /** "Reprocess in High detail" / viewer "High detail" button. */
    val canReprocess: Boolean
        get() = quality == ResultQuality.QUICK && hasSavedPhotos && processingPercent == null
}

interface ScanRepository {
    /** Newest first. */
    val scans: Flow<List<Scan>>
    fun scan(id: String): Flow<Scan?>
    suspend fun loadMesh(id: String): Mesh?
    /** Saves a new scan and its mesh (box-frame mm, Z up, resting on Z = 0, centred on X/Y). */
    suspend fun add(scan: Scan, mesh: Mesh)
    /** "Scan_07": the next free default name. */
    suspend fun nextScanName(): String
    suspend fun rename(id: String, name: String)
    suspend fun delete(id: String)
    /** Deletes every scan's keyframes; those scans can no longer be reprocessed. */
    suspend fun clearSavedPhotos()
    val usage: Flow<StorageUsage>
}
