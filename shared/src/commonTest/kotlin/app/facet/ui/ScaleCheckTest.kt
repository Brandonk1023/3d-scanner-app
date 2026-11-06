package app.facet.ui

import app.facet.data.FakeScanRepository
import app.facet.geometry.Vec3
import app.facet.mesh.SampleMeshes
import app.facet.mesh.SampleShape
import app.facet.mesh.bounds
import app.facet.mesh.raycast
import app.facet.mesh.scaled
import app.facet.settings.AppSettings
import app.facet.settings.InMemorySettingsRepository
import app.facet.settings.LengthUnit
import app.facet.ui.scale.ScaleCheckViewModel
import app.facet.ui.scale.parseLength
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScaleCheckTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // Cube_20 sample: 20 mm, resting on Z = 0, centred.
    private val a = Vec3(-10.0, -10.0, 0.0)
    private val b = Vec3(-10.0, -10.0, 20.0)

    private fun vm(repo: FakeScanRepository = FakeScanRepository(), units: LengthUnit = LengthUnit.MILLIMETRES) =
        ScaleCheckViewModel("cube-20", repo, InMemorySettingsRepository(AppSettings(units = units)))

    @Test
    fun raycastFindsTheNearestSurface() {
        val cube = SampleMeshes.create(SampleShape.CUBE)
        // Straight down onto the top face.
        val hit = assertNotNull(cube.raycast(Vec3(2.0, 3.0, 100.0), Vec3(0.0, 0.0, -1.0)))
        assertEquals(20.0, hit.z, 1e-4)
        assertEquals(2.0, hit.x, 1e-4)
        // From the side, the near face wins over the far one.
        val side = assertNotNull(cube.raycast(Vec3(-100.0, 0.0, 5.0), Vec3(1.0, 0.0, 0.0)))
        assertEquals(-10.0, side.x, 1e-4)
        // Pointing away, or past the cube: no hit.
        assertNull(cube.raycast(Vec3(0.0, 0.0, 100.0), Vec3(0.0, 0.0, 1.0)))
        assertNull(cube.raycast(Vec3(50.0, 0.0, 100.0), Vec3(0.0, 0.0, -1.0)))
    }

    @Test
    fun scalingKeepsTheModelOnTheTable() {
        val s = SampleMeshes.create(SampleShape.MUG).scaled(1.1).bounds()
        val o = SampleMeshes.create(SampleShape.MUG).bounds()
        assertEquals(o.size.heightMm * 1.1, s.size.heightMm, 1e-3)
        assertEquals(0.0, s.min.z, 1e-6)
    }

    @Test
    fun twoPointsAndALengthGiveTheCorrection() = runTest {
        val repo = FakeScanRepository()
        val vm = vm(repo)
        assertFalse(vm.state.value.loading)
        vm.tap(a)
        assertNull(vm.state.value.measuredMm)
        vm.tap(b)
        assertEquals(20.0, vm.state.value.measuredMm!!, 1e-9)
        vm.setInput("20.5")
        val s = vm.state.value
        assertEquals(1.025, s.factor!!, 1e-9)
        assertEquals(0.025, s.correction!!, 1e-9)
        assertEquals(20.5, s.newSize!!.heightMm, 1e-9)
        assertFalse(s.warnLarge)
        assertTrue(s.canApply)

        vm.apply()
        assertTrue(vm.state.value.applied)
        val scan = assertNotNull(repo.scan("cube-20").first())
        assertEquals("20.5 mm", scan.scaleReference)
        assertEquals(20.5, scan.size.heightMm, 1e-4)
        assertEquals(20.5, repo.loadMesh("cube-20")!!.bounds().size.widthMm, 1e-4)
    }

    @Test
    fun inchesAreConvertedAndRecordedInInches() = runTest {
        val repo = FakeScanRepository()
        val vm = vm(repo, LengthUnit.INCHES)
        vm.tap(a); vm.tap(b)
        vm.setInput("0.8") // 20.32 mm
        assertEquals(20.32, vm.state.value.realMm!!, 1e-9)
        vm.apply()
        assertEquals("0.80 in", repo.scan("cube-20").first()!!.scaleReference)
    }

    @Test
    fun largeTinyAndAbsurdCorrections() = runTest {
        val vm = vm()
        vm.tap(a); vm.tap(b)
        vm.setInput("23") // +15 %: allowed with a warning
        assertTrue(vm.state.value.warnLarge && vm.state.value.canApply)
        vm.setInput("40") // +100 %: refused
        assertTrue(vm.state.value.outOfRange)
        assertFalse(vm.state.value.canApply)
        // Points 2 mm apart are too close to measure from.
        vm.resetPoints()
        vm.tap(a); vm.tap(a + Vec3(0.0, 0.0, 2.0))
        assertTrue(vm.state.value.tooShort)
        assertNull(vm.state.value.factor)
    }

    @Test
    fun aThirdTapMovesTheNearerPointAndDragMovesOne() = runTest {
        val vm = vm()
        vm.tap(a); vm.tap(b)
        val c = Vec3(-10.0, -10.0, 18.0)
        vm.tap(c)
        assertEquals(listOf(a, c), vm.state.value.points)
        vm.movePoint(0, Vec3(-10.0, -10.0, 1.0))
        assertEquals(17.0, vm.state.value.measuredMm!!, 1e-9)
        vm.movePoint(5, a) // ignored
        assertEquals(2, vm.state.value.points.size)
    }

    @Test
    fun inputKeepsDigitsAndOneSeparator() = runTest {
        val vm = vm()
        vm.setInput("9a8,.5")
        assertEquals("98,5", vm.state.value.input)
        assertEquals(98.5, parseLength("98,5")!!, 1e-12)
        assertNull(parseLength("0"))
        assertNull(parseLength(""))
        assertNull(parseLength("."))
    }

    @Test
    fun applyNeedsAValidMeasurement() = runTest {
        val repo = FakeScanRepository()
        val vm = vm(repo)
        vm.setInput("20")
        vm.apply()
        assertFalse(vm.state.value.applied)
        assertNull(repo.scan("cube-20").first()!!.scaleReference)
    }

}
