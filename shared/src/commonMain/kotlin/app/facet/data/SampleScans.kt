package app.facet.data

import app.facet.export.ExportFormat
import app.facet.mesh.Mesh
import app.facet.mesh.SampleMeshes
import app.facet.mesh.SampleShape
import app.facet.mesh.bounds
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** A sample scan built from [SampleMeshes]. */
class SampleScan(val scan: Scan, val shape: SampleShape) {
    fun mesh(): Mesh = SampleMeshes.create(shape)
}

/**
 * Five sample scans covering every gallery state (Processing, Quick, High detail, scale verified,
 * photos cleared). Used by [FakeScanRepository] and, in debug builds, Settings › Add sample scans.
 */
fun sampleScans(now: Instant): List<SampleScan> {
    fun sample(
        id: String, name: String, shape: SampleShape, age: Duration, quality: ResultQuality,
        processing: Int? = null, scale: String? = null, photosMb: Long = 0,
    ): SampleScan {
        val mesh = SampleMeshes.create(shape)
        return SampleScan(
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
    return listOf(
        sample("bracket-l", "Bracket_L", SampleShape.BRACKET, 1.hours, ResultQuality.QUICK, processing = 62, photosMb = 180),
        sample("mug-01", "Mug_01", SampleShape.MUG, 3.hours, ResultQuality.QUICK, photosMb = 240),
        sample("gear-32t", "Gear_32T", SampleShape.GEAR, 2.days, ResultQuality.HIGH_DETAIL, scale = "1 € coin", photosMb = 310),
        sample("cube-20", "Cube_20", SampleShape.CUBE, 4.days, ResultQuality.HIGH_DETAIL, photosMb = 95),
        sample("knob-m8", "Knob_M8", SampleShape.KNOB, 45.days, ResultQuality.QUICK),
    )
}

/**
 * Debug builds: adds the samples to a real gallery (the emulator can't scan). They have no saved
 * photos on disk, so they're added without any; adding again replaces them.
 */
suspend fun ScanRepository.addSampleScans(clock: Clock = Clock.System) {
    sampleScans(clock.now()).forEach { add(it.scan.copy(hasSavedPhotos = false, photoBytes = 0), it.mesh()) }
}

private const val MB = 1_000_000L
