package app.facet.mesh

import app.facet.export.ExportFormat
import app.facet.geometry.BoxSize
import app.facet.scan.ScanTuning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Stage order and fallbacks of [ScanPipeline], against a recording fake engine. */
class ScanPipelineTest {
    private val triangle = Mesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2))
    private val repairedMesh = Mesh(floatArrayOf(0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f, 0f), intArrayOf(0, 1, 2))
    private val decimatedMesh = Mesh(floatArrayOf(0f, 0f, 0f, 3f, 0f, 0f, 0f, 3f, 0f), intArrayOf(0, 1, 2))

    private class FakeEngine(
        val repairStatus: PipelineStatus,
        val repaired: Mesh,
        val decimated: Mesh,
        val cleaned: Mesh = Mesh(floatArrayOf(5f, 5f, 2f, 6f, 5f, 2f, 5f, 6f, 3f), intArrayOf(0, 1, 2)),
    ) : MeshEngine {
        val calls = mutableListOf<String>()
        var decimateInput: Mesh? = null
        override fun coreVersion() = "fake"
        override fun boxDiagonalMm(box: BoxSize) = box.diagonalMm
        override fun createVolume(box: BoxSize, voxelMm: Double, truncationMm: Double): TsdfVolume? = null
        override fun loadVolume(path: String): TsdfVolume? = null
        override fun repair(mesh: Mesh, options: RepairOptions): MeshResult {
            calls += "repair"
            return if (repairStatus == PipelineStatus.OK) MeshResult(repairStatus, repaired) else MeshResult(repairStatus, mesh)
        }
        var cleanupStatus = PipelineStatus.OK
        var cleanupParams: CleanupParams? = null
        override fun cleanup(mesh: Mesh, params: CleanupParams): MeshResult {
            calls += "cleanup"
            cleanupParams = params
            return if (cleanupStatus == PipelineStatus.OK) MeshResult(cleanupStatus, cleaned) else MeshResult(cleanupStatus, mesh)
        }
        override fun decimate(mesh: Mesh, targetTriangles: Int, maxError: Float): MeshResult {
            calls += "decimate:$targetTriangles"
            decimateInput = mesh
            return MeshResult(PipelineStatus.OK, decimated)
        }
        override fun writeStl(mesh: Mesh, path: String): PipelineStatus = PipelineStatus.OK.also { calls += "stl" }
        override fun write3mf(mesh: Mesh, path: String, objectName: String) = PipelineStatus.OK.also { calls += "3mf:$objectName" }
        override fun createPointCloud(box: BoxSize, floorMarginMm: Double): PointCloud? = null
    }

    private class FakeVolume(val mesh: Mesh) : TsdfVolume {
        override fun integrate(frame: DepthFrame, cameraToBox: FloatArray, minConfidence: Float) = IntegrateResult(0, 0, 0f)
        override fun extract(step: Int, minWeight: Float) = mesh
        override fun carve(mask: ObjectMask, fx: Float, fy: Float, cx: Float, cy: Float, cameraToBox: FloatArray) = Unit
        override val carvedViews = 0
        override fun extractHull(maxOutsideFraction: Float, minSeen: Int) = mesh
        override fun extractMarchingCubes(minWeight: Float) = mesh
        override fun save(path: String) = true
        override fun close() = Unit
    }

    private class FakeCloud(val result: MeshResult) : PointCloud {
        override fun add(frame: DepthFrame, cameraToBox: FloatArray, minConfidence: Float) = 0
        override val size = 0
        override fun reconstructPoisson(depth: Int, trimFraction: Float) = result
        override fun close() = Unit
    }

    @Test
    fun liveRunsMarchingCubesThenRepairThenDecimate() {
        val engine = FakeEngine(PipelineStatus.OK, repairedMesh, decimatedMesh)
        val out = ScanPipeline(engine).live(FakeVolume(triangle), ScanPipeline.Options(targetTriangles = 500))!!
        assertEquals(listOf("repair", "decimate:500"), engine.calls)
        assertSame(repairedMesh, engine.decimateInput)
        assertSame(decimatedMesh, out.mesh)
        assertTrue(out.repaired)
    }

    @Test
    fun liveFallsBackToTheUnrepairedMeshWhenManifoldRejectsIt() {
        val engine = FakeEngine(PipelineStatus.NOT_MANIFOLD, repairedMesh, decimatedMesh)
        val out = ScanPipeline(engine).live(FakeVolume(triangle))!!
        assertSame(triangle, engine.decimateInput)
        assertFalse(out.repaired)
        assertEquals(PipelineStatus.NOT_MANIFOLD, out.repairStatus)
    }

    @Test
    fun emptyVolumeOrFailedPoissonGivesNoResult() {
        val engine = FakeEngine(PipelineStatus.OK, repairedMesh, decimatedMesh)
        val pipeline = ScanPipeline(engine)
        assertNull(pipeline.live(FakeVolume(Mesh(FloatArray(0), IntArray(0)))))
        assertNull(pipeline.photo(FakeCloud(MeshResult(PipelineStatus.EMPTY, null))))
        assertTrue(engine.calls.isEmpty())
    }

    @Test
    fun photoRunsPoissonThenRepairThenDecimate() {
        val engine = FakeEngine(PipelineStatus.OK, repairedMesh, decimatedMesh)
        val out = ScanPipeline(engine).photo(FakeCloud(MeshResult(PipelineStatus.OK, triangle)))!!
        assertEquals(listOf("repair", "decimate:200000"), engine.calls)
        assertSame(decimatedMesh, out.mesh)
    }

    @Test
    fun finalMeshCleansThenRepairsThenDecimatesAndRestsOnTheTable() {
        // Decimation hands back an off-centre, raised triangle; the result is moved onto Z = 0.
        val raised = Mesh(floatArrayOf(10f, 10f, 4f, 14f, 10f, 4f, 10f, 14f, 9f), intArrayOf(0, 1, 2))
        val engine = FakeEngine(PipelineStatus.OK, repairedMesh, raised)
        val params = CleanupParams(tableSearchMm = 12f, fillHoles = true)
        val out = ScanPipeline(engine).finalMesh(triangle, params, ScanPipeline.Options(targetTriangles = 500))
        assertEquals(listOf("cleanup", "repair", "decimate:500"), engine.calls)
        assertEquals(params, engine.cleanupParams)
        assertTrue(out.repaired)
        val b = out.mesh.bounds()
        assertEquals(0.0, b.min.z, 1e-6)
        assertEquals(0.0, b.center.x, 1e-6)
        assertEquals(0.0, b.center.y, 1e-6)
    }

    @Test
    fun finalMeshUsesTheRawMeshWhenCleanupFails() {
        val engine = FakeEngine(PipelineStatus.OK, repairedMesh, decimatedMesh).apply { cleanupStatus = PipelineStatus.EMPTY }
        var repairInput: Mesh? = null
        val spy = object : MeshEngine by engine {
            override fun repair(mesh: Mesh, options: RepairOptions): MeshResult {
                repairInput = mesh
                return engine.repair(mesh, options)
            }
        }
        ScanPipeline(spy).finalMesh(triangle, CleanupParams())
        assertSame(triangle, repairInput)
    }

    @Test
    fun cleanupParamsFollowSettingsAndTuning() {
        val t = ScanTuning().cleanup
        val depthOnly = t.params(watertight = true, smoothing = Smoothing.MEDIUM, hasTable = true)
        assertEquals(t.tableSearchMm, depthOnly.tableSearchMm)
        assertEquals(t.smoothingMedium, depthOnly.smoothingIterations)
        assertTrue(depthOnly.fillHoles && depthOnly.keepLargest)
        val hull = t.params(watertight = false, smoothing = Smoothing.OFF, hasTable = false)
        assertEquals(0f, hull.tableSearchMm)
        assertEquals(0, hull.smoothingIterations)
        assertFalse(hull.fillHoles)
        assertTrue(t.smoothingLow < t.smoothingMedium && t.smoothingMedium < t.smoothingHigh)
    }

    @Test
    fun exportPicksTheWriterForTheFormat() {
        val engine = FakeEngine(PipelineStatus.OK, repairedMesh, decimatedMesh)
        val pipeline = ScanPipeline(engine)
        pipeline.export(triangle, ExportFormat.STL, "a.stl", "Mug")
        pipeline.export(triangle, ExportFormat.THREE_MF, "a.3mf", "Mug")
        assertEquals(listOf("stl", "3mf:Mug"), engine.calls)
    }

    @Test
    fun statusCodesMatchTheCHeader() {
        assertEquals(PipelineStatus.NOT_MANIFOLD, PipelineStatus.fromCode(3))
        assertEquals(PipelineStatus.INTERNAL, PipelineStatus.fromCode(99))
    }
}
