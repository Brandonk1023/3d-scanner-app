package app.facet.scan

import app.facet.geometry.BoxSize
import app.facet.geometry.Vec3
import app.facet.platform.StorageInfo
import app.facet.settings.InMemorySettingsRepository
import app.facet.ui.scan.PlacementStage
import app.facet.ui.scan.PlacementViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlacementTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val tuning = ScanTuning.Default.placement

    private fun close(expected: Double, actual: Double, tol: Double, what: String = "") =
        assertTrue(abs(expected - actual) <= tol, "$what expected $expected ± $tol, was $actual")

    @Test
    fun sideHandleMovesOneFaceOnly() {
        val box = PlacedBox(0.0, 0.0, 90.0, BoxSize(100.0, 60.0, 80.0))
        val farFaceBefore = box.faceCenter(BoxAxis.WIDTH, -1)
        val grown = box.resized(BoxAxis.WIDTH, side = +1, deltaMm = 40.0, tuning)
        close(140.0, grown.size.widthMm, 1e-9)
        val farFaceAfter = grown.faceCenter(BoxAxis.WIDTH, -1)
        close(farFaceBefore.x, farFaceAfter.x, 1e-9, "x")
        close(farFaceBefore.y, farFaceAfter.y, 1e-9, "y")
        // Yaw 90° → width runs along +Y, so the centre moves +20 mm in Y.
        close(20.0, grown.centerY, 1e-9)
    }

    @Test
    fun resizeClampsToLimitsAndHeightGrowsUp() {
        val box = PlacedBox(10.0, 0.0, 0.0, BoxSize(30.0, 30.0, 30.0))
        val shrunk = box.resized(BoxAxis.DEPTH, -1, -100.0, tuning)
        assertEquals(tuning.boxMinSideMm, shrunk.size.depthMm)
        // Only the applied 10 mm shrink moves the centre (half of it).
        close(5.0, shrunk.centerY, 1e-9)
        val taller = box.resized(BoxAxis.HEIGHT, 1, 50.0, tuning)
        assertEquals(80.0, taller.size.heightMm)
        assertEquals(box.centerX, taller.centerX)
        // One drag event is capped.
        val huge = box.resized(BoxAxis.HEIGHT, 1, 10_000.0, tuning)
        assertEquals(30.0 + tuning.maxDragStepMm, huge.size.heightMm)
    }

    @Test
    fun rotationWrapsAndCornersSitOnTable() {
        val box = PlacedBox(0.0, 0.0, 170.0, BoxSize(100.0, 50.0, 20.0)).rotated(20.0)
        close(-170.0, box.yawDeg, 1e-9)
        val corners = box.corners()
        assertEquals(8, corners.size)
        assertTrue(corners.take(4).all { it.z == 0.0 } && corners.drop(4).all { it.z == 20.0 })
        // Diagonal of the base is preserved by rotation.
        close(kotlin.math.hypot(100.0, 50.0), (corners[2] - corners[0]).length(), 1e-9)
    }

    @Test
    fun autoFitFindsTappedObjectAndIgnoresClutter() {
        val rnd = Random(7)
        val points = buildList {
            // Mug: cylinder r = 40 mm, h = 100 mm, centred at (50, 0).
            repeat(1500) {
                val a = rnd.nextDouble(0.0, 2 * PI)
                add(Vec3(50 + 40 * cos(a), 40 * sin(a), rnd.nextDouble(0.0, 100.0)))
            }
            // Table noise just above the plane.
            repeat(800) { add(Vec3(rnd.nextDouble(-300.0, 300.0), rnd.nextDouble(-300.0, 300.0), rnd.nextDouble(-5.0, 8.0))) }
            // A box of clutter 100 mm away (edge to edge), 60 mm tall.
            repeat(600) { add(Vec3(rnd.nextDouble(190.0, 240.0), rnd.nextDouble(-25.0, 25.0), rnd.nextDouble(0.0, 60.0))) }
        }
        // Tap on the front of the mug.
        val box = assertNotNull(BoxAutoFit.fit(points, seedX = 50.0, seedY = -40.0, tuning))
        close(50.0, box.centerX, 8.0, "centerX")
        close(0.0, box.centerY, 8.0, "centerY")
        // ~80 mm footprint + 2 × 10 mm margin (minus trimming); height ~100 + 10.
        assertTrue(box.size.widthMm in 85.0..110.0, "width ${box.size.widthMm}")
        assertTrue(box.size.depthMm in 85.0..110.0, "depth ${box.size.depthMm}")
        close(110.0, box.size.heightMm, 6.0, "height")
    }

    @Test
    fun autoFitCopesWithPlaneBelowRealTable() {
        // The AR plane sits 15 mm under the real tabletop, so every table point looks 15 mm tall.
        val rnd = Random(11)
        val lift = 15.0
        val points = buildList {
            repeat(4000) { add(Vec3(rnd.nextDouble(-300.0, 300.0), rnd.nextDouble(-300.0, 300.0), lift + rnd.nextDouble(-6.0, 6.0))) }
            // 60 × 60 × 50 mm block at the origin.
            repeat(1200) { add(Vec3(rnd.nextDouble(-30.0, 30.0), rnd.nextDouble(-30.0, 30.0), lift + rnd.nextDouble(0.0, 50.0))) }
        }
        val report = BoxAutoFit.fitWithReport(points, FitSeed.Point(0.0, -30.0), tuning)
        close(lift, report.tableOffsetMm, 4.0, "table offset")
        val box = assertNotNull(report.box)
        assertTrue(box.size.widthMm in 70.0..90.0, "width ${box.size.widthMm}")
        assertTrue(box.size.depthMm in 70.0..90.0, "depth ${box.size.depthMm}")
        // Reaches from the AR plane up past the block's top (15 + 50 + margin).
        close(lift + 50 + tuning.autoSizeMarginMm, box.size.heightMm, 6.0, "height")
    }

    @Test
    fun tapThroughGlassStillFindsIt() {
        // Camera 300 mm up, 700 mm back (−Y). A 70 mm glass, 150 mm tall, stands at the origin; the
        // tap ray passes through it and hits the table 200 mm behind, in the glass's depth shadow.
        val rnd = Random(5)
        val camera = Vec3(0.0, -700.0, 300.0)
        val behind = Vec3(0.0, 200.0, 0.0)
        val points = buildList {
            repeat(6000) {
                val x = rnd.nextDouble(-400.0, 400.0)
                val y = rnd.nextDouble(-400.0, 400.0)
                if (!(abs(x) < 60 && y in 0.0..350.0)) add(Vec3(x, y, rnd.nextDouble(-20.0, 20.0))) // occluded behind the glass
            }
            repeat(800) { // front half of the glass only
                val a = rnd.nextDouble(PI, 2 * PI)
                add(Vec3(35 * cos(a), 35 * sin(a), rnd.nextDouble(0.0, 150.0) + rnd.nextDouble(-10.0, 10.0)))
            }
        }
        val report = BoxAutoFit.fitWithReport(points, FitSeed.Ray(camera, behind - camera), tuning)
        val box = assertNotNull(report.box, "report $report")
        close(0.0, box.centerX, 15.0, "centerX")
        close(-15.0, box.centerY, 20.0, "centerY")
        assertTrue(box.size.heightMm in 140.0..175.0, "height ${box.size.heightMm}")
    }

    @Test
    fun remoteOnTiltedNoisyTable() {
        // Phone depth: table 30 mm under the AR plane, tilted 2°, ±8 mm noise; a 150 × 40 × 20 mm remote.
        val rnd = Random(9)
        val slope = kotlin.math.tan(2.0 * PI / 180)
        fun table(x: Double, y: Double) = -30.0 + slope * y
        fun noise() = (rnd.nextDouble() + rnd.nextDouble() + rnd.nextDouble() - 1.5) * 16.0
        val points = buildList {
            repeat(8000) {
                val x = rnd.nextDouble(-350.0, 350.0)
                val y = rnd.nextDouble(-350.0, 350.0)
                if (!(abs(x) < 75 && abs(y) < 20)) add(Vec3(x, y, table(x, y) + noise()))
            }
            repeat(600) {
                val x = rnd.nextDouble(-75.0, 75.0)
                val y = rnd.nextDouble(-20.0, 20.0)
                add(Vec3(x, y, table(x, y) + 20 + noise() / 2))
            }
        }
        val report = BoxAutoFit.fitWithReport(points, FitSeed.Point(0.0, 0.0), tuning)
        val box = assertNotNull(report.box, "report $report")
        val long = maxOf(box.size.widthMm, box.size.depthMm)
        val short = minOf(box.size.widthMm, box.size.depthMm)
        assertTrue(long in 140.0..190.0, "long side $long")
        assertTrue(short in 40.0..90.0, "short side $short")
    }

    @Test
    fun autoFitAlignsWithLongObject() {
        val rnd = Random(3)
        val yaw = 30.0 * PI / 180
        val points = List(2000) {
            val u = rnd.nextDouble(-100.0, 100.0)
            val v = rnd.nextDouble(-20.0, 20.0)
            Vec3(u * cos(yaw) - v * sin(yaw), u * sin(yaw) + v * cos(yaw), rnd.nextDouble(0.0, 40.0))
        } + List(3000) { Vec3(rnd.nextDouble(-300.0, 300.0), rnd.nextDouble(-300.0, 300.0), rnd.nextDouble(-4.0, 4.0)) }
        val box = assertNotNull(BoxAutoFit.fit(points, 0.0, 0.0, tuning))
        // Long side along 30° (or the opposite direction).
        val dir = normalizeDeg(box.yawDeg - 30.0)
        assertTrue(abs(dir) < 3 || abs(abs(dir) - 180) < 3, "yaw ${box.yawDeg}")
        assertTrue(box.size.widthMm in 200.0..225.0, "width ${box.size.widthMm}")
        assertTrue(box.size.depthMm in 50.0..65.0, "depth ${box.size.depthMm}")
    }

    @Test
    fun autoFitGivesUpWithoutAnObject() {
        val rnd = Random(1)
        val tableOnly = List(2000) { Vec3(rnd.nextDouble(-300.0, 300.0), rnd.nextDouble(-300.0, 300.0), rnd.nextDouble(-4.0, 6.0)) }
        assertNull(BoxAutoFit.fit(tableOnly, 0.0, 0.0, tuning))
        // Object exists, but the tap is half a metre away from it.
        val farObject = List(500) { Vec3(rnd.nextDouble(500.0, 540.0), rnd.nextDouble(0.0, 40.0), rnd.nextDouble(20.0, 60.0)) }
        assertNull(BoxAutoFit.fit(farObject, 0.0, 0.0, tuning))
    }

    @Test
    fun startScanChecksStorage() {
        var free = 100L * 1024 * 1024
        val vm = PlacementViewModel(InMemorySettingsRepository(), object : StorageInfo { override fun freeBytes() = free })
        vm.startScan() // no box yet: nothing happens
        assertNull(vm.state.value.started)

        vm.onBoxPlaced(PlacedBox.default(0.0, 0.0, tuning), fitted = false)
        assertEquals(PlacementStage.FITTING_BOX, vm.state.value.stage)
        vm.startScan()
        assertNotNull(vm.state.value.lowStorage)
        assertNull(vm.state.value.started)

        vm.dismissLowStorage()
        free = 4L * 1024 * 1024 * 1024
        vm.setScanType(ScanType.ONE_SIDE)
        vm.startScan()
        assertEquals(ScanType.ONE_SIDE, vm.state.value.started?.scanType)

        vm.clearBox()
        assertEquals(PlacementStage.FINDING_SURFACE, vm.state.value.stage)
        assertNull(vm.state.value.box)
    }
}
