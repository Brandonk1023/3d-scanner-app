package app.facet.processing

import app.facet.data.CaptureInfo
import app.facet.data.FakeScanRepository
import app.facet.data.ResultQuality
import app.facet.data.Scan
import app.facet.data.ScanFolder
import app.facet.geometry.BoxSize
import app.facet.mesh.CleanupParams
import app.facet.mesh.DepthFrame
import app.facet.mesh.IntegrateResult
import app.facet.mesh.Mesh
import app.facet.mesh.MeshEngine
import app.facet.mesh.MeshResult
import app.facet.mesh.ObjectMask
import app.facet.mesh.PipelineStatus
import app.facet.mesh.PointCloud
import app.facet.mesh.RepairOptions
import app.facet.mesh.SampleMeshes
import app.facet.mesh.SampleShape
import app.facet.mesh.TsdfVolume
import app.facet.mesh.bounds
import app.facet.platform.FailureReason
import app.facet.platform.ProcessingProgress
import app.facet.platform.ProcessingStage
import app.facet.platform.ProcessingStatus
import app.facet.scan.ScanTuning
import app.facet.scan.SimulatedScan
import app.facet.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Records what the processor asks of the native core; meshes are a 20 mm cube. */
private class FakeEngine(var allocate: Boolean = true) : MeshEngine {
    val volumes = ArrayList<FakeVolume>()
    var cleanupParams: CleanupParams? = null
    var decimateTarget = 0

    override fun coreVersion() = "fake"
    override fun boxDiagonalMm(box: BoxSize) = box.diagonalMm
    override fun createVolume(box: BoxSize, voxelMm: Double, truncationMm: Double): TsdfVolume? =
        if (allocate) FakeVolume(box, voxelMm).also { volumes += it } else null
    override fun loadVolume(path: String): TsdfVolume? = null
    override fun repair(mesh: Mesh, options: RepairOptions) = MeshResult(PipelineStatus.OK, mesh)
    override fun cleanup(mesh: Mesh, params: CleanupParams): MeshResult {
        cleanupParams = params
        return MeshResult(PipelineStatus.OK, mesh)
    }
    override fun decimate(mesh: Mesh, targetTriangles: Int, maxError: Float): MeshResult {
        decimateTarget = targetTriangles
        return MeshResult(PipelineStatus.OK, mesh)
    }
    override fun writeStl(mesh: Mesh, path: String) = PipelineStatus.OK
    override fun write3mf(mesh: Mesh, path: String, objectName: String) = PipelineStatus.OK
    override fun createPointCloud(box: BoxSize, floorMarginMm: Double): PointCloud? = null
}

private class FakeVolume(val box: BoxSize, val voxelMm: Double) : TsdfVolume {
    var integrated = 0
    var hullExtracted = false
    var closed = false
    var empty = false
    override var carvedViews = 0
    override fun integrate(frame: DepthFrame, cameraToBox: FloatArray, minConfidence: Float): IntegrateResult {
        integrated++
        return IntegrateResult(1, 1, 1f)
    }
    override fun extract(step: Int, minWeight: Float): Mesh = if (empty) Mesh(FloatArray(0), IntArray(0)) else SampleMeshes.create(SampleShape.CUBE)
    override fun carve(mask: ObjectMask, fx: Float, fy: Float, cx: Float, cy: Float, cameraToBox: FloatArray) {
        carvedViews++
    }
    override fun extractHull(maxOutsideFraction: Float, minSeen: Int): Mesh {
        hullExtracted = true
        return extract(1, 0f)
    }
    override fun extractMarchingCubes(minWeight: Float): Mesh = extract(1, minWeight)
    override fun save(path: String) = false
    override fun close() {
        closed = true
    }
}

class HighDetailTest {
    private val fs = FakeFileSystem()
    private val root = "/files/scans".toPath()
    private val tuning = ScanTuning()
    private var clock = 0L
    private fun folder(id: String) = ScanFolder(fs, root / id)

    private fun processor(engine: MeshEngine, repo: FakeScanRepository, settings: AppSettings = AppSettings()) =
        HighDetailProcessor(engine, repo, ::folder, { settings }, tuning) { clock += 100; clock }

    /** A Quick scan with the simulated scan's keyframes on disk. */
    private suspend fun simulatedScan(repo: FakeScanRepository, id: String = "sim"): Scan {
        assertNotNull(SimulatedScan.run(FakeEngine(), tuning, folder(id)))
        val scan = Scan(
            id = id, name = "Sim_01", createdAt = Instant.fromEpochMilliseconds(0), quality = ResultQuality.QUICK,
            size = BoxSize(70.0, 70.0, 70.0), triangleCount = 12,
        )
        repo.add(scan, SampleMeshes.create(SampleShape.CUBE))
        return scan
    }

    // --- Progress ---

    @Test
    fun overallProgressFollowsTheStageWeights() {
        val t = ScanTuning.HighDetail(stageWeights = listOf(0.1, 0.1, 0.6, 0.2))
        assertEquals(0f, ProgressTracker.overall(t, ProcessingStage.ALIGNING_PHOTOS, 0f))
        assertEquals(0.1f, ProgressTracker.overall(t, ProcessingStage.DEPTH_MAPS, 0f), 1e-6f)
        assertEquals(0.5f, ProgressTracker.overall(t, ProcessingStage.BUILDING_FINE_MESH, 0.5f), 1e-6f)
        assertEquals(1f, ProgressTracker.overall(t, ProcessingStage.CLEANUP, 1f), 1e-6f)
        // Out-of-range fractions are clamped.
        assertEquals(0.8f, ProgressTracker.overall(t, ProcessingStage.BUILDING_FINE_MESH, 7f), 1e-6f)
    }

    @Test
    fun timeLeftWaitsForEnoughProgressThenExtrapolates() {
        val t = ScanTuning.HighDetail()
        assertNull(ProgressTracker.remainingSeconds(t, 0.02f, 60_000))
        assertNull(ProgressTracker.remainingSeconds(t, 0.5f, 1_000))
        assertEquals(60, ProgressTracker.remainingSeconds(t, 0.5f, 60_000))
        assertEquals(30, ProgressTracker.remainingSeconds(t, 0.8f, 120_000))
        assertNull(ProgressTracker.remainingSeconds(t, 1f, 120_000))
    }

    @Test
    fun trackerTimesStagesAndNeverGoesBackwards() {
        var now = 0L
        val tracker = ProgressTracker(ScanTuning.HighDetail(), now = { now })
        tracker.enter(ProcessingStage.ALIGNING_PHOTOS)
        now = 2_000
        tracker.enter(ProcessingStage.DEPTH_MAPS)
        now = 50_000
        tracker.update(10, 10)
        val p = tracker.enter(ProcessingStage.BUILDING_FINE_MESH)
        assertEquals(listOf(2_000L, 48_000L), p.stageDurationsMs)
        val before = p.overall
        assertTrue(tracker.update(0, 4, fraction = -1f).overall >= before)
        // Skipping a stage still records a duration for it.
        now = 60_000
        assertEquals(3, tracker.enter(ProcessingStage.CLEANUP).stageDurationsMs.size)
    }

    @Test
    fun galleryPercentIsWrittenInSteps() {
        var now = 0L
        val tracker = ProgressTracker(ScanTuning.HighDetail(stageWeights = listOf(0.25, 0.25, 0.25, 0.25), galleryPercentStep = 5), now = { now })
        tracker.enter(ProcessingStage.ALIGNING_PHOTOS)
        tracker.update(1, 25) // 1 %
        assertEquals(false, tracker.shouldWriteGalleryPercent())
        tracker.update(5, 25) // 5 %
        assertTrue(tracker.shouldWriteGalleryPercent())
        tracker.update(6, 25) // 6 %
        assertEquals(false, tracker.shouldWriteGalleryPercent())
    }

    // --- Processor ---

    @Test
    fun rebuildsFromKeyframesAndSwapsInTheResult() = runTest {
        val repo = FakeScanRepository()
        simulatedScan(repo)
        val engine = FakeEngine()
        val seen = ArrayList<ProcessingProgress>()

        val status = processor(engine, repo, AppSettings(watertight = true)).run("sim") { seen += it }

        assertEquals(ProcessingStatus.Succeeded, status)
        val volume = engine.volumes.single()
        assertEquals(36, volume.integrated)
        assertEquals(36, volume.carvedViews) // every simulated frame has a mask
        assertTrue(volume.hullExtracted)
        assertTrue(volume.closed)
        // The fine volume: placed box plus margins, voxel within the High detail range and cap.
        assertEquals(tuning.volumeSize(SimulatedScan.box), volume.box)
        assertEquals(tuning.highDetailVolumeVoxelMm(volume.box), volume.voxelMm, 1e-9)
        assertTrue(volume.voxelMm < tuning.liveVoxelMm(SimulatedScan.box))
        // A carved hull has no table; watertight follows Settings.
        assertEquals(0f, engine.cleanupParams!!.tableSearchMm)
        assertTrue(engine.cleanupParams!!.fillHoles)
        assertEquals(tuning.highDetail.targetTriangles, engine.decimateTarget)

        val scan = repo.scan("sim").first()!!
        assertEquals(ResultQuality.HIGH_DETAIL, scan.quality)
        assertNull(scan.processingPercent)
        // Progress went through every stage, in order, and only forwards.
        assertEquals(ProcessingStage.entries, seen.map { it.stage }.distinct())
        assertTrue(seen.zipWithNext().all { (a, b) -> b.overall >= a.overall })
        assertEquals(100, seen.last().percent)
        assertEquals(36, seen.last().photoCount)
    }

    @Test
    fun reappliesAnEarlierScaleCheck() = runTest {
        val repo = FakeScanRepository()
        simulatedScan(repo)
        folder("sim").recordScale(1.1)
        folder("sim").recordScale(1.1)
        assertEquals(1.21, folder("sim").readCaptureInfo()!!.scaleFactor, 1e-9)

        assertEquals(ProcessingStatus.Succeeded, processor(FakeEngine(), repo).run("sim") {})
        val mesh = repo.loadMesh("sim")!!
        assertEquals(20 * 1.21, mesh.bounds().size.widthMm, 1e-3)
    }

    @Test
    fun scansWithoutCaptureInfoFailAsOldPhotos() = runTest {
        val repo = FakeScanRepository()
        simulatedScan(repo)
        fs.delete(root / "sim" / ScanFolder.CAPTURE_FILE)

        val status = assertIs<ProcessingStatus.Failed>(processor(FakeEngine(), repo).run("sim") {})
        assertEquals(FailureReason.OLD_PHOTOS, status.reason)
        assertEquals(ProcessingStage.ALIGNING_PHOTOS, status.stage)
        val scan = repo.scan("sim").first()!!
        assertEquals(ResultQuality.QUICK, scan.quality)
        assertNull(scan.processingPercent)
    }

    @Test
    fun clearedPhotosFailAsNoPhotos() = runTest {
        val repo = FakeScanRepository()
        simulatedScan(repo)
        fs.deleteRecursively(root / "sim" / "keyframes")
        val status = assertIs<ProcessingStatus.Failed>(processor(FakeEngine(), repo).run("sim") {})
        assertEquals(FailureReason.NO_PHOTOS, status.reason)
    }

    @Test
    fun allocationFailureIsOutOfMemoryAtStageThree() = runTest {
        val repo = FakeScanRepository()
        simulatedScan(repo)
        val status = assertIs<ProcessingStatus.Failed>(processor(FakeEngine(allocate = false), repo).run("sim") {})
        assertEquals(FailureReason.OUT_OF_MEMORY, status.reason)
        assertEquals(ProcessingStage.BUILDING_FINE_MESH, status.stage)
        assertTrue("allocation" in status.technicalLog)
        assertNull(repo.scan("sim").first()!!.processingPercent)
    }

    @Test
    fun debugFailureStopsAtTheChosenStage() = runTest {
        val repo = FakeScanRepository()
        simulatedScan(repo)
        val status = assertIs<ProcessingStatus.Failed>(
            processor(FakeEngine(), repo).run("sim", debugFailAt = ProcessingStage.DEPTH_MAPS) {},
        )
        assertEquals(ProcessingStage.DEPTH_MAPS, status.stage)
        assertEquals(ResultQuality.QUICK, repo.scan("sim").first()!!.quality)
    }

    @Test
    fun cancellingKeepsTheQuickResult() = runTest {
        val repo = FakeScanRepository()
        simulatedScan(repo)
        val job = launch {
            processor(FakeEngine(), repo).run("sim") { p ->
                if (p.stage == ProcessingStage.BUILDING_FINE_MESH && p.itemsDone == 3) throw CancellationException("user")
            }
        }
        job.join()
        val scan = repo.scan("sim").first()!!
        assertEquals(ResultQuality.QUICK, scan.quality)
        assertNull(scan.processingPercent)
    }

    // --- Scan folder ---

    @Test
    fun readsKeyframesInTheAndroidCaptureFormat() {
        val dir = root / "phone"
        fs.createDirectories(dir / "keyframes")
        // As KeyframeWriter writes it (org.json): "scale" may be null, "format" is informational.
        fs.write(dir / "keyframes" / "kf_001.json") {
            writeUtf8(
                """{"timestampNs":5,"sharpness":120.5,"cameraToBox":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,300,1],""" +
                    """"image":{"width":640,"height":480,"fx":500,"fy":500,"cx":320,"cy":240},""" +
                    """"depth":{"file":"kf_001.depth","width":2,"height":1,"fx":2,"fy":2,"cx":1,"cy":0.5,"scale":null,""" +
                    """"format":"uint16le mm, then uint8 confidence"},""" +
                    """"mask":{"file":"kf_001.mask","width":2,"height":2,"fx":1,"fy":1,"cx":1,"cy":1}}""",
            )
        }
        fs.write(dir / "keyframes" / "kf_001.depth") { writeShortLe(400); writeShortLe(65_000); writeByte(255); writeByte(7) }
        fs.write(dir / "keyframes" / "kf_001.mask") { write(byteArrayOf(0, 1, 1, 0)) }
        fs.write(dir / "keyframes" / "kf_002.json") { writeUtf8("not json") }
        fs.write(dir / "keyframes" / "kf_002.jpg") { writeUtf8("jpeg") }

        val f = ScanFolder(fs, dir)
        val kf = f.keyframes().single()
        assertEquals("kf_001", kf.name)
        assertNull(kf.meta.depth!!.scale)
        val depth = assertNotNull(f.loadDepth(kf))
        assertEquals(400, depth.depthMm[0].toInt() and 0xFFFF)
        assertEquals(65_000, depth.depthMm[1].toInt() and 0xFFFF)
        assertEquals(7, depth.confidence!![1].toInt())
        assertEquals(2, f.loadMask(kf)!!.width)
        assertTrue(f.keyframeBytes() > 0)
    }

    @Test
    fun captureInfoRoundTripsAndRecordsScale() {
        val f = ScanFolder(fs, root / "a")
        assertNull(f.readCaptureInfo())
        f.recordScale(2.0) // no capture.json yet: nothing to update
        assertNull(f.readCaptureInfo())
        f.writeCaptureInfo(CaptureInfo.of(BoxSize(100.0, 80.0, 60.0), depthScale = 0.93))
        f.recordScale(1.05)
        val info = f.readCaptureInfo()!!
        assertEquals(BoxSize(100.0, 80.0, 60.0), info.box)
        assertEquals(0.93, info.depthScale)
        assertEquals(1.05, info.scaleFactor, 1e-12)
        fs.write(root / "a" / ScanFolder.CAPTURE_FILE) { writeUtf8("{broken") }
        assertNull(f.readCaptureInfo())
    }

    @Test
    fun badFileNamesInMetadataAreIgnored() {
        val dir = root / "evil"
        fs.createDirectories(dir / "keyframes")
        fs.write(dir / "keyframes" / "kf_001.json") {
            writeUtf8(
                """{"cameraToBox":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1],"image":{"width":1,"height":1,"fx":1,"fy":1,"cx":0,"cy":0},""" +
                    """"depth":{"file":"../../capture.json","width":1,"height":1,"fx":1,"fy":1,"cx":0,"cy":0}}""",
            )
        }
        val f = ScanFolder(fs, dir)
        assertNull(f.loadDepth(f.keyframes().single()))
        assertFailsWith<IllegalArgumentException> { ScanTuning.HighDetail(stageWeights = listOf(1.0)) }
    }
}
