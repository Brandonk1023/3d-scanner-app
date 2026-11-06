package app.facet.data

import app.facet.mesh.Mesh
import app.facet.mesh.SampleMeshes
import app.facet.mesh.SampleShape
import app.facet.mesh.bounds
import app.facet.mesh.scaled
import app.facet.platform.StorageUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Clock

/**
 * In-memory gallery for tests and previews, starting with [sampleScans]. Behaves like
 * [RoomScanRepository] but forgets everything when the process ends.
 */
class FakeScanRepository(clock: Clock = Clock.System) : ScanRepository {
    /** A sample ([shape]) or a scan added later ([mesh]). */
    private class Entry(val scan: Scan, val shape: SampleShape?, val mesh: Mesh? = null)

    private val meshes = HashMap<SampleShape, Mesh>()
    private fun meshFor(shape: SampleShape): Mesh = meshes.getOrPut(shape) { SampleMeshes.create(shape) }

    private val entries = MutableStateFlow(sampleScans(clock.now()).map { Entry(it.scan, it.shape) })

    override val scans: Flow<List<Scan>> =
        entries.map { list -> list.map { it.scan }.sortedByDescending { it.createdAt } }

    override fun scan(id: String): Flow<Scan?> = entries.map { list -> list.firstOrNull { it.scan.id == id }?.scan }

    override suspend fun loadMesh(id: String): Mesh? =
        entries.value.firstOrNull { it.scan.id == id }?.let { it.mesh ?: it.shape?.let(::meshFor) }

    override suspend fun loadThumbnail(id: String): Mesh? = loadMesh(id)

    override suspend fun add(scan: Scan, mesh: Mesh) = entries.update { list ->
        list.filterNot { it.scan.id == scan.id } + Entry(scan, shape = null, mesh = mesh)
    }

    override suspend fun nextScanName(): String = nextFreeScanName(entries.value.map { it.scan.name }.toSet())

    override suspend fun rename(id: String, name: String) = edit(id) { it.copy(name = name) }

    override suspend fun applyScale(id: String, factor: Double, reference: String) {
        val mesh = loadMesh(id)?.scaled(factor) ?: return
        entries.update { list ->
            list.map {
                if (it.scan.id != id) it
                else Entry(it.scan.copy(size = mesh.bounds().size, scaleReference = reference), shape = null, mesh = mesh)
            }
        }
    }

    override suspend fun delete(id: String) = entries.update { list -> list.filterNot { it.scan.id == id } }

    override suspend fun clearSavedPhotos() = entries.update { list ->
        list.map {
            if (it.scan.processingPercent != null) it
            else Entry(it.scan.copy(hasSavedPhotos = false, photoBytes = 0), it.shape, it.mesh)
        }
    }

    override val usage: Flow<StorageUsage> = entries.map { list ->
        StorageUsage(
            scanCount = list.size,
            modelBytes = list.sumOf { it.scan.modelBytes },
            savedPhotoBytes = list.sumOf { it.scan.photoBytes },
        )
    }

    private fun edit(id: String, change: (Scan) -> Scan) = entries.update { list ->
        list.map { if (it.scan.id == id) Entry(change(it.scan), it.shape, it.mesh) else it }
    }
}
