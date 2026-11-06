package app.facet.data

import app.facet.data.db.ScanDao
import app.facet.data.db.toEntity
import app.facet.data.db.toScan
import app.facet.mesh.Mesh
import app.facet.mesh.bounds
import app.facet.mesh.scaled
import app.facet.platform.StorageUsage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * The gallery: records in Room ([dao]), files on disk. Each scan owns the folder `[scansDir]/<id>/`:
 *
 * - `model.fmesh`  the result mesh ([MeshFiles])
 * - `thumb.fmesh`  a small copy for gallery cards ([makeThumbnail])
 * - `keyframes/`   photos + depth for High detail (written by capture; removed by [clearSavedPhotos])
 * - `capture.json` the scan box and scale for High detail ([ScanFolder])
 *
 * Capture already works inside that folder (its name is the scan id), so [add] only adds the meshes.
 */
class RoomScanRepository(
    private val dao: ScanDao,
    private val fs: FileSystem,
    private val scansDir: Path,
    /** Reduces a mesh for thumbnails (meshoptimizer on device). Must not throw. */
    private val makeThumbnail: (Mesh) -> Mesh,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ScanRepository {

    override val scans: Flow<List<Scan>> = dao.observeAll().map { list -> list.map { it.toScan() } }

    override fun scan(id: String): Flow<Scan?> = dao.observe(id).map { it?.toScan() }

    override suspend fun loadMesh(id: String): Mesh? = withContext(io) { MeshFiles.read(fs, modelPath(id)) }

    override suspend fun loadThumbnail(id: String): Mesh? = withContext(io) {
        MeshFiles.read(fs, thumbPath(id)) ?: MeshFiles.read(fs, modelPath(id))
    }

    override suspend fun add(scan: Scan, mesh: Mesh) {
        val bytes = writeMeshes(scan.id, mesh)
        // The record is written last: a scan in the gallery always has its mesh on disk.
        dao.upsert(scan.copy(modelBytes = bytes).toEntity())
    }

    override suspend fun setProcessing(id: String, percent: Int?) = dao.setProcessing(id, percent)

    override suspend fun completeHighDetail(id: String, mesh: Mesh) {
        if (dao.get(id) == null) return
        val bytes = writeMeshes(id, mesh)
        val scan = dao.get(id)?.toScan() ?: return // deleted while the files were written
        dao.upsert(
            scan.copy(
                quality = ResultQuality.HIGH_DETAIL, processingPercent = null, size = mesh.bounds().size,
                triangleCount = mesh.triangleCount, modelBytes = bytes,
            ).toEntity(),
        )
    }

    /** Model + thumbnail; returns their size on disk. */
    private suspend fun writeMeshes(id: String, mesh: Mesh): Long = withContext(io) {
        val thumb = if (mesh.triangleCount > THUMBNAIL_TRIANGLES) makeThumbnail(mesh) else mesh
        MeshFiles.write(fs, modelPath(id), mesh)
        MeshFiles.write(fs, thumbPath(id), thumb)
        MeshFiles.byteSize(mesh) + MeshFiles.byteSize(thumb)
    }

    /** The scan's capture.json and keyframes. */
    fun folder(id: String): ScanFolder = ScanFolder(fs, dir(id))

    override suspend fun nextScanName(): String = nextFreeScanName(dao.names().toSet())

    override suspend fun rename(id: String, name: String) = dao.rename(id, name)

    override suspend fun applyScale(id: String, factor: Double, reference: String) {
        val scan = dao.get(id)?.toScan() ?: return
        val result = withContext(io) {
            val mesh = MeshFiles.read(fs, modelPath(id)) ?: return@withContext null
            val scaled = mesh.scaled(factor)
            val thumb = (MeshFiles.read(fs, thumbPath(id)) ?: mesh).scaled(factor)
            MeshFiles.write(fs, modelPath(id), scaled)
            MeshFiles.write(fs, thumbPath(id), thumb)
            // Remembered so a later High detail result gets the same correction.
            ScanFolder(fs, dir(id)).recordScale(factor)
            scaled
        } ?: return
        dao.upsert(scan.copy(size = result.bounds().size, scaleReference = reference).toEntity())
    }

    override suspend fun delete(id: String) {
        dao.delete(id)
        withContext(io) { fs.deleteRecursively(dir(id)) }
    }

    override suspend fun clearSavedPhotos() {
        val ids = dao.idsWithClearablePhotos()
        if (ids.isEmpty()) return
        withContext(io) { ids.forEach { fs.deleteRecursively(dir(it) / KEYFRAMES_DIR) } }
        dao.markPhotosCleared(ids)
    }

    override val usage: Flow<StorageUsage> = scans.map { list ->
        StorageUsage(
            scanCount = list.size,
            modelBytes = list.sumOf { it.modelBytes },
            savedPhotoBytes = list.sumOf { it.photoBytes },
        )
    }

    private fun dir(id: String): Path {
        require(id.isNotEmpty() && id.none { it == '/' || it == '\\' } && id != "." && id != "..") { "bad scan id: $id" }
        return scansDir / id
    }

    private fun modelPath(id: String) = dir(id) / MODEL_FILE
    private fun thumbPath(id: String) = dir(id) / THUMB_FILE

    companion object {
        const val MODEL_FILE = "model.fmesh"
        const val THUMB_FILE = "thumb.fmesh"
        const val KEYFRAMES_DIR = "keyframes"

        /** Gallery cards are ~170 dp wide; more triangles than this don't show. */
        const val THUMBNAIL_TRIANGLES = 6_000
    }
}

/** "Scan_07": the first "Scan_NN" not in [taken]. */
fun nextFreeScanName(taken: Set<String>): String {
    var n = 1
    while (true) {
        val name = "Scan_" + n.toString().padStart(2, '0')
        if (name !in taken) return name
        n++
    }
}
