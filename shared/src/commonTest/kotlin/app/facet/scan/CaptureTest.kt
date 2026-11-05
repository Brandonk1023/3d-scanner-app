package app.facet.scan

import app.facet.geometry.BoxSize
import app.facet.geometry.Vec3
import app.facet.platform.HapticsPlayer
import app.facet.settings.DefaultResult
import app.facet.settings.InMemorySettingsRepository
import app.facet.ui.scan.CaptureFrame
import app.facet.ui.scan.CapturePhase
import app.facet.ui.scan.CaptureSheet
import app.facet.ui.scan.CaptureViewModel
import app.facet.ui.scan.ScanSetup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val tuning = ScanTuning.Default
    private val box = PlacedBox(0.0, 0.0, 0.0, BoxSize(100.0, 100.0, 100.0))

    private class RecordingHaptics : HapticsPlayer {
        val played = mutableListOf<Any>()
        override fun play(message: GuidanceMessage) { played += message }
        override fun play(feedback: ScanFeedback) { played += feedback }
        override fun cancel() = Unit
    }

    /** Camera at [elevationDeg] / [azimuthDeg], 400 mm from the box centre. */
    private fun cameraAt(elevationDeg: Double, azimuthDeg: Double, distance: Double = 400.0): Vec3 {
        val e = elevationDeg * PI / 180
        val a = azimuthDeg * PI / 180
        return box.center + Vec3(cos(e) * cos(a), cos(e) * sin(a), sin(e)) * distance
    }

    @Test
    fun cameraViewAngles() {
        val v = cameraView(box, cameraAt(40.0, 135.0))
        assertTrue(kotlin.math.abs(v.elevationDeg - 40) < 1e-6)
        assertTrue(kotlin.math.abs(v.azimuthDeg - 135) < 1e-6)
        assertTrue(kotlin.math.abs(v.distanceMm - 400) < 1e-6)
    }

    @Test
    fun coverageNeedsThreeFramesPerSectorAndReportsRingComplete() {
        val model = CoverageModel(tuning, ScanType.FULL_OBJECT, 0.0)
        repeat(2) { assertFalse(model.addAcceptedFrame(CoverageBand.LOW, 7.0).sectorCaptured) }
        assertTrue(model.addAcceptedFrame(CoverageBand.LOW, 7.0).sectorCaptured)
        assertEquals(1f / 24, model.bandFraction(CoverageBand.LOW))
        var completed: CoverageBand? = null
        for (sector in 1 until 24) repeat(3) {
            model.addAcceptedFrame(CoverageBand.LOW, sector * 15.0 + 7).bandCompleted?.let { completed = it }
        }
        assertEquals(CoverageBand.LOW, completed)
        assertEquals(1f, model.bandFraction(CoverageBand.LOW))
        assertEquals(1f / 3, model.state().overall)
        assertTrue(model.state().wedgeFilled(CoverageBand.LOW, 5))
    }

    @Test
    fun oneSideCountsOnlyTheFrontSpan() {
        val model = CoverageModel(tuning, ScanType.ONE_SIDE, startHeadingDeg = 90.0)
        val counted = model.state().counted[0].count { it }
        assertEquals(8, counted) // ±60° = 120° = 8 sectors of 15°
        // Capturing behind the object doesn't raise coverage.
        repeat(3) { model.addAcceptedFrame(CoverageBand.LOW, 270.0) }
        assertEquals(0f, model.state().overall)
    }

    private fun input(now: Long, distance: Double = 400.0, orbit: Double = 0.0, tracking: TrackingState = TrackingState.TRACKING, light: Float? = 0.5f) =
        GuidanceInput(
            nowMs = now, tracking = tracking, boxInView = 1f, distanceMm = distance,
            distanceRangeMm = 200.0..600.0, orbitSpeedDegPerSec = orbit, linearSpeedMmPerSec = 0.0,
            blurScore = null, lightIntensity = light, band = CoverageBand.LOW,
            coverage = CoverageState.empty(24),
        )

    @Test
    fun guidanceWaitsHoldsAndRespectsPriority() {
        val engine = GuidanceEngine(tuning)
        // Too fast: needs 0.5 s before showing.
        assertNull(engine.update(input(0, orbit = 60.0)).message)
        assertNull(engine.update(input(400, orbit = 60.0)).message)
        val shown = engine.update(input(500, orbit = 60.0))
        assertEquals(GuidanceMessage.MOVE_SLOWER, shown.message)
        assertEquals(listOf(GuidanceMessage.MOVE_SLOWER), shown.haptics)
        // Condition ends, but the banner stays for its 1.5 s minimum.
        assertEquals(GuidanceMessage.MOVE_SLOWER, engine.update(input(1_000)).message)
        assertNull(engine.update(input(2_100)).message)
        // Too close (priority 2) replaces Move slower (3) immediately once eligible.
        engine.update(input(3_000, orbit = 60.0))
        assertEquals(GuidanceMessage.MOVE_SLOWER, engine.update(input(3_500, orbit = 60.0)).message)
        engine.update(input(3_600, orbit = 60.0, distance = 100.0))
        assertEquals(GuidanceMessage.TOO_CLOSE, engine.update(input(4_100, orbit = 60.0, distance = 100.0)).message)
    }

    @Test
    fun hapticCooldownAndTrackingLostRepeat() {
        val engine = GuidanceEngine(tuning)
        engine.update(input(0, orbit = 60.0))
        assertEquals(1, engine.update(input(500, orbit = 60.0)).haptics.size)
        engine.update(input(2_100)) // hides
        engine.update(input(2_200, orbit = 60.0))
        val again = engine.update(input(2_700, orbit = 60.0))
        assertEquals(GuidanceMessage.MOVE_SLOWER, again.message)
        assertTrue(again.haptics.isEmpty(), "within the 4 s cooldown")

        val lost = GuidanceEngine(tuning)
        lost.update(input(0, tracking = TrackingState.NOT_TRACKING))
        val buzzes = (500L..6_500L step 100).sumOf { lost.update(input(it, tracking = TrackingState.NOT_TRACKING)).haptics.size }
        assertEquals(4, buzzes) // at 0.5, 2.5, 4.5, 6.5 s
    }

    @Test
    fun captureSessionCoverageFinishAndResult() = runTest {
        val settings = InMemorySettingsRepository()
        val haptics = RecordingHaptics()
        val vm = CaptureViewModel(ScanSetup(box, ScanType.FULL_OBJECT), settings, haptics)
        var t = 0L
        fun frame(camera: Vec3, tracking: TrackingState = TrackingState.TRACKING) {
            t += 33_000_000
            vm.onFrame(CaptureFrame(t, tracking, camera, 1f, 0.6f, anchorTracking = true))
        }
        // Finish does nothing below 15 %.
        vm.finish()
        assertNull(vm.state.value.sheet)

        // Walk the low ring at 15°/s (0.5° per 33 ms frame), fusing every frame.
        for (step in 0 until 720) {
            frame(cameraAt(15.0, step * 0.5 + 0.25))
            vm.onDepthFused(0.9f)
        }
        val s = vm.state.value
        assertEquals(1f, s.coverage.band(CoverageBand.LOW))
        assertTrue(ScanFeedback.RING_COMPLETE in haptics.played)
        assertEquals(CoverageBand.LOW, s.toast)
        assertTrue(s.elapsedMs > 2_000)

        // 33 % overall, mid/high missing → incomplete sheet, naming the weakest band.
        vm.finish()
        assertEquals(CaptureSheet.INCOMPLETE, vm.state.value.sheet)
        assertFalse(vm.state.value.fusing)
        vm.keepScanning()
        assertNull(vm.state.value.sheet)

        // Low-confidence depth doesn't count.
        val before = vm.state.value.coverage.overall
        repeat(10) { frame(cameraAt(40.0, 90.0)); vm.onDepthFused(0.2f) }
        assertEquals(before, vm.state.value.coverage.overall)

        vm.finish()
        vm.finishAnyway()
        assertEquals(CaptureSheet.CHOOSE_RESULT, vm.state.value.sheet)
        vm.chooseResult(ResultMode.QUICK, remember = true)
        assertEquals(ResultMode.QUICK, vm.state.value.finished)
        assertEquals(DefaultResult.QUICK, settings.settings.first().defaultResult)
    }

    @Test
    fun pauseAndInterruption() {
        val vm = CaptureViewModel(ScanSetup(box, ScanType.ONE_SIDE), InMemorySettingsRepository(), RecordingHaptics())
        var t = 0L
        fun frame(anchor: Boolean) {
            t += 33_000_000
            vm.onFrame(CaptureFrame(t, TrackingState.TRACKING, cameraAt(20.0, 0.0), 1f, 0.6f, anchor))
        }
        frame(true)
        vm.togglePause()
        assertEquals(CapturePhase.PAUSED, vm.state.value.phase)
        assertFalse(vm.state.value.fusing)
        val elapsed = vm.state.value.elapsedMs
        repeat(30) { frame(true) }
        assertEquals(elapsed, vm.state.value.elapsedMs, "timer stops while paused")
        vm.togglePause()

        vm.onInterrupted()
        assertEquals(CaptureSheet.INTERRUPTED, vm.state.value.sheet)
        frame(anchor = false)
        vm.resumeAfterInterruption()
        assertEquals(CapturePhase.INTERRUPTED, vm.state.value.phase, "can't resume until relocalised")
        frame(anchor = true)
        vm.resumeAfterInterruption()
        assertEquals(CapturePhase.CAPTURING, vm.state.value.phase)
        assertTrue(vm.state.value.fusing)

        vm.onInterrupted()
        vm.discard()
        assertTrue(vm.state.value.discarded)
    }
}

class DepthCalibrationTest {
    /** Depth map of the table plane Z = 0 seen from [camToBox], with every value × [bias]. */
    private fun tableDepth(camToBox: FloatArray, bias: Double): app.facet.mesh.DepthFrame {
        val w = 160
        val h = 90
        val fx = 108f
        val cx = 80f
        val cy = 45f
        val d = ShortArray(w * h)
        for (v in 0 until h) for (u in 0 until w) {
            val x = (u + 0.5f - cx) / fx
            val y = (cy - (v + 0.5f)) / fx
            val dz = camToBox[2] * x + camToBox[6] * y - camToBox[10]
            if (dz < 0) d[v * w + u] = (-camToBox[14] / dz * bias).toInt().toShort()
        }
        return app.facet.mesh.DepthFrame(w, h, d, null, fx, fx, cx, cy)
    }

    /** Camera 300 mm back and 300 mm up, looking 45° down at the box centre. */
    private val camToBox: FloatArray = run {
        val s = kotlin.math.sqrt(0.5).toFloat()
        // Columns: right = +X, up = (0, s, s), back = (0, −s, s); position (0, −300, 300).
        floatArrayOf(1f, 0f, 0f, 0f, 0f, s, s, 0f, 0f, -s, s, 0f, 0f, -300f, 300f, 1f)
    }

    @Test
    fun recoversDepthScaleFromTheTable() {
        val box = BoxSize(120.0, 60.0, 40.0)
        val frame = tableDepth(camToBox, bias = 1.10)
        val scale = DepthCalibration.tableScale(frame, camToBox, box, ScanTuning.Default.reconstruction)
        assertTrue(scale != null && kotlin.math.abs(scale - 1 / 1.10) < 0.01, "scale $scale")
        val fixed = DepthCalibration.scaled(frame, scale!!)
        val again = DepthCalibration.tableScale(fixed, camToBox, box, ScanTuning.Default.reconstruction)
        assertTrue(again != null && kotlin.math.abs(again - 1.0) < 0.01, "after correction $again")
    }

    @Test
    fun noTableNoScale() {
        val frame = app.facet.mesh.DepthFrame(160, 90, ShortArray(160 * 90), null, 108f, 108f, 80f, 45f)
        assertNull(DepthCalibration.tableScale(frame, camToBox, BoxSize(100.0, 100.0, 100.0), ScanTuning.Default.reconstruction))
    }
}
