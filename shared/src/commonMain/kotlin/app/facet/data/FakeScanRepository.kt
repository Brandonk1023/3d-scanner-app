package app.facet.data

import app.facet.export.ExportFormat
import app.facet.mesh.Mesh
import app.facet.mesh.SampleMeshes
import app.facet.mesh.SampleShape
import app.facet.mesh.bounds
import app.facet.platform.StorageUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Phase 1 in-memory gallery with sample scans built from [SampleMeshes]. Rename, delete and
 * clear-photos work until the app restarts. Replaced by the Room repository in Phase 5.
 */
class FakeScanRepository(clock: Clock = Clock.System) : ScanRepository {
    private class Entry(val scan: Scan, val shape: SampleShape)

    private val meshes = HashMap<SampleShape, Mesh>()
    private fun meshFor(shape: SampleShape): Mesh = meshes.getOrPut(shape) { SampleMeshes.create(shape) }

    private val entries = MutableStateFlow(
        run {
            val now = clock.now()
            fun sample(
                id: String, name: String, shape: SampleShape, age: Duration, quality: ResultQuality,
                processing: Int? = null, scale: String? = null, photosMb: Long = 0,
            ): Entry {
                val mesh = meshFor(shape)
                return Entry(
                    Scan(
                        id = id, name = name, createdAt = now - age, quality = quality,
                        processingPercent = processing, size = mesh.bounds().size,
                        triangleCount = mesh.triangleCount, scaleReference = scale,
                        hasSavedPhotos = photosMb > 0,
                        modelBytes = ExportFormat.STL.estimatedBytes(mesh), photoBytes = photosMb * MB,
                    ),
                    shape,
                )
            }
            listOf(
                sample("bracket-l", "Bracket_L", SampleShape.BRACKET, 1.hours, ResultQuality.QUICK, processing = 62, photosMb = 180),
                sample("mug-01", "Mug_01", SampleShape.MUG, 3.hours, ResultQuality.QUICK, photosMb = 240),
                sample("gear-32t", "Gear_32T", SampleShape.GEAR, 2.days, ResultQuality.HIGH_DETAIL, scale = "1 € coin", photosMb = 310),
                sample("cube-20", "Cube_20", SampleShape.CUBE, 4.days, ResultQuality.HIGH_DETAIL, photosMb = 95),
                sample("knob-m8", "Knob_M8", SampleShape.KNOB, 45.days, ResultQuality.QUICK),
            )
        },
    )

    override val scans: Flow<List<Scan>> =
        entries.map { list -> list.map { it.scan }.sortedByDescending { it.createdAt } }

    override fun scan(id: String): Flow<Scan?> = entries.map { list -> list.firstOrNull { it.scan.id == id }?.scan }

    override suspend fun loadMesh(id: String): Mesh? =
        entries.value.firstOrNull { it.scan.id == id }?.let { meshFor(it.shape) }

    override suspend fun rename(id: String, name: String) = edit(id) { it.copy(name = name) }

    override suspend fun delete(id: String) = entries.update { list -> list.filterNot { it.scan.id == id } }

    override suspend fun clearSavedPhotos() = entries.update { list ->
        list.map { Entry(it.scan.copy(hasSavedPhotos = false, photoBytes = 0), it.shape) }
    }

    override val usage: Flow<StorageUsage> = entries.map { list ->
        StorageUsage(
            scanCount = list.size,
            modelBytes = list.sumOf { it.scan.modelBytes },
            savedPhotoBytes = list.sumOf { it.scan.photoBytes },
        )
    }

    private fun edit(id: String, change: (Scan) -> Scan) = entries.update { list ->
        list.map { if (it.scan.id == id) Entry(change(it.scan), it.shape) else it }
    }

    private companion object {
        const val MB = 1_000_000L
    }
}
