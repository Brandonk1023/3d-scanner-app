package app.facet.mesh

import app.facet.data.FakeScanRepository
import app.facet.data.ResultQuality
import app.facet.data.Scan
import app.facet.data.ScanFolder
import app.facet.geometry.BoxSize
import app.facet.platform.ProcessingStatus
import app.facet.processing.HighDetailProcessor
import app.facet.settings.AppSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.time.Instant
import app.facet.scan.ScanTuning
import app.facet.scan.SimulatedScan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import platform.Foundation.NSTemporaryDirectory

/** Runs on the iOS Simulator: calls the real C++ core through cinterop. */
class IosMeshEngineTest {
    private val engine = IosMeshEngine()

    @Test
    fun coreVersionIsReachable() {
        assertEquals("0.3.0", engine.coreVersion())
    }

    @Test
    fun boxDiagonalMatchesKotlin() {
        val box = BoxSize(2.0, 3.0, 6.0)
        assertEquals(7.0, engine.boxDiagonalMm(box), 1e-9)
        assertEquals(box.diagonalMm, engine.boxDiagonalMm(box), 1e-9)
    }

    @Test
    fun pipelineStagesRunThroughCinterop() {
        val cube = unitCube(20f)
        val repaired = engine.repair(cube, RepairOptions(plinthHeightMm = 2f))
        assertEquals(PipelineStatus.OK, repaired.status)
        val solid = repaired.mesh!!
        assertTrue(solid.triangleCount >= 12)

        assertEquals(PipelineStatus.OK, engine.decimate(solid, 1_000).status)
        val dir = NSTemporaryDirectory()
        assertEquals(PipelineStatus.OK, engine.writeStl(solid, dir + "facet_ios_test.stl"))
        assertEquals(PipelineStatus.OK, engine.write3mf(solid, dir + "facet_ios_test.3mf", "cube"))

        // A lone triangle is not a closed manifold: status reported, input returned unchanged.
        val open = Mesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2))
        val rejected = engine.repair(open)
        assertEquals(PipelineStatus.NOT_MANIFOLD, rejected.status)
        assertEquals(1, rejected.mesh!!.triangleCount)
    }

    /** Axis-aligned cube of side [s] resting on Z = 0, outward (counter-clockwise) winding. */
    @Test
    fun cleanupFillsHolesThroughCinterop() {
        // Drop the cube's top (triangles 2 and 3): cleanup closes it again with a flat patch.
        val cube = unitCube(20f)
        val openTop = Mesh(cube.positions, cube.indices.filterIndexed { i, _ -> i / 3 != 2 && i / 3 != 3 }.toIntArray())
        val filled = engine.cleanup(openTop, CleanupParams(keepLargest = true, fillHoles = true))
        assertEquals(PipelineStatus.OK, filled.status)
        assertEquals(12, filled.mesh!!.triangleCount)
        assertEquals(PipelineStatus.OK, engine.repair(filled.mesh!!).status)

        // Without fillHoles it stays open, so Manifold rejects it.
        val kept = engine.cleanup(openTop, CleanupParams())
        assertEquals(10, kept.mesh!!.triangleCount)
        assertEquals(PipelineStatus.NOT_MANIFOLD, engine.repair(kept.mesh!!).status)
    }

    @Test
    fun simulatedScanComesOutTrueToSizeAndClosed() {
        val tuning = ScanTuning.Default
        val raw = SimulatedScan.run(engine, tuning)!!
        assertTrue(raw.bounds().size.widthMm > 110) // the table fills the box
        val params = tuning.cleanup.params(watertight = true, smoothing = Smoothing.LOW, hasTable = true)
        val out = ScanPipeline(engine).finalMesh(raw, params)
        val size = out.mesh.bounds().size
        println("simulated scan: ${size.widthMm} × ${size.depthMm} × ${size.heightMm} mm, repaired=${out.repaired}")
        val d = 2 * SimulatedScan.RADIUS
        assertEquals(d, size.widthMm, 4.0) // the crumb and the table are gone
        assertEquals(d, size.depthMm, 4.0)
        assertEquals(SimulatedScan.HEIGHT, size.heightMm, 4.0)
        assertEquals(0.0, out.mesh.bounds().min.z, 1e-6)
        assertTrue(out.repaired, "watertight mesh should pass Manifold")
    }

    @Test
    fun highDetailRebuildsTheSimulatedScanFromItsKeyframes() = runBlocking {
        // A smaller voxel cap than the phone's keeps the test quick; the path is the same.
        val tuning = ScanTuning(highDetail = ScanTuning.HighDetail(maxVoxels = 2_000_000))
        val fs = FakeFileSystem()
        val folder = ScanFolder(fs, "/scans/sim".toPath())
        val repo = FakeScanRepository()
        val quick = ScanPipeline(engine).finalMesh(
            SimulatedScan.run(engine, tuning, folder)!!,
            tuning.cleanup.params(watertight = true, smoothing = Smoothing.LOW, hasTable = true),
        ).mesh
        repo.add(
            Scan("sim", "Sim", Instant.fromEpochMilliseconds(0), ResultQuality.QUICK, size = quick.bounds().size, triangleCount = quick.triangleCount),
            quick,
        )
        var t = 0L
        val processor = HighDetailProcessor(engine, repo, { folder }, { AppSettings(watertight = true) }, tuning) { t += 10; t }
        assertEquals(ProcessingStatus.Succeeded, processor.run("sim") {})

        val scan = repo.scan("sim").first()!!
        assertEquals(ResultQuality.HIGH_DETAIL, scan.quality)
        val size = scan.size
        println("high detail: ${size.widthMm} × ${size.depthMm} × ${size.heightMm} mm, ${scan.triangleCount} triangles (quick ${quick.triangleCount})")
        // Carved from the masks: the cylinder alone, no table or crumb.
        val d = 2 * SimulatedScan.RADIUS
        assertEquals(d, size.widthMm, 4.0)
        assertEquals(d, size.depthMm, 4.0)
        // A visual hull can't close a flat top: only the lowest views (20° here) bound it, so it may
        // stand up to radius · tan(20°) ≈ 13 mm proud. Spike finding: the fine method must intersect
        // the hull with the depth surface. Until then this is what the placeholder gives.
        assertTrue(size.heightMm >= SimulatedScan.HEIGHT - 2, "height ${size.heightMm}")
        assertTrue(size.heightMm <= SimulatedScan.HEIGHT + 2 + SimulatedScan.RADIUS * kotlin.math.tan(20 * kotlin.math.PI / 180), "height ${size.heightMm}")
    }

    private fun unitCube(s: Float): Mesh {
        val h = s / 2
        val p = floatArrayOf(
            -h, -h, 0f, h, -h, 0f, h, h, 0f, -h, h, 0f,
            -h, -h, s, h, -h, s, h, h, s, -h, h, s,
        )
        val i = intArrayOf(
            0, 2, 1, 0, 3, 2, // bottom
            4, 5, 6, 4, 6, 7, // top
            0, 1, 5, 0, 5, 4, // front
            1, 2, 6, 1, 6, 5, // right
            2, 3, 7, 2, 7, 6, // back
            3, 0, 4, 3, 4, 7, // left
        )
        return Mesh(p, i)
    }
}
