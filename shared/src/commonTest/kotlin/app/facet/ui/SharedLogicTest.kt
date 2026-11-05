package app.facet.ui

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.facet.data.FakeScanRepository
import app.facet.export.ExportFormat
import app.facet.export.sanitizeFileName
import app.facet.format.formatBytes
import app.facet.format.formatCount
import app.facet.format.formatFixed
import app.facet.format.formatLength
import app.facet.geometry.BoxSize
import app.facet.mesh.Mesh
import app.facet.mesh.SampleMeshes
import app.facet.mesh.SampleShape
import app.facet.mesh.Smoothing
import app.facet.mesh.bounds
import app.facet.platform.ArServicesStatus
import app.facet.platform.DepthStatus
import app.facet.platform.DeviceCapabilities
import app.facet.platform.DeviceCheckResult
import app.facet.platform.DeviceSupport
import app.facet.settings.AppSettings
import app.facet.settings.InMemorySettingsRepository
import app.facet.settings.LengthUnit
import app.facet.settings.ThemeMode
import app.facet.settings.toAppSettings
import app.facet.settings.write
import app.facet.ui.device.DeviceCheckUiState
import app.facet.ui.device.DeviceCheckViewModel
import app.facet.ui.export.ExportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharedLogicTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun formatting() {
        assertEquals("98.0", formatFixed(98.0, 1))
        assertEquals("118.6", formatFixed(118.6, 1))
        assertEquals("0.05", formatFixed(0.049999, 2))
        assertEquals("-1.5", formatFixed(-1.5, 1))
        assertEquals("0.0", formatFixed(-0.01, 1))
        assertEquals("4.67", formatLength(118.6, LengthUnit.INCHES))
        assertEquals("640", formatCount(640))
        assertEquals("6.7k", formatCount(6_712))
        assertEquals("86k", formatCount(86_200))
        assertEquals("1.2M", formatCount(1_210_000))
        assertEquals("4.1 MB", formatBytes(4_100_000))
        assertEquals("18.6 GB", formatBytes(18_600_000_000))
        assertEquals("640 KB", formatBytes(640_000))
    }

    @Test
    fun fileNamesAreSanitised() {
        assertEquals("Mug_01", sanitizeFileName("Mug_01"))
        assertEquals("a_b_c", sanitizeFileName(" a/b:c "))
        assertEquals("scan", sanitizeFileName("  ...  "))
    }

    @Test
    fun stlSizeIsExact() {
        val mesh = Mesh(FloatArray(9), intArrayOf(0, 1, 2))
        assertEquals(134L, ExportFormat.STL.estimatedBytes(mesh))
    }

    @Test
    fun sampleMeshesAreValidAndGrounded() {
        SampleShape.entries.forEach { shape ->
            val mesh = SampleMeshes.create(shape)
            assertTrue(mesh.triangleCount > 0, "$shape has triangles")
            assertTrue(mesh.indices.all { it in 0 until mesh.vertexCount }, "$shape indices in range")
            val b = mesh.bounds()
            assertTrue(abs(b.min.z) < 1e-4, "$shape rests on Z = 0")
            assertTrue(abs(b.center.x) < 1e-3 && abs(b.center.y) < 1e-3, "$shape centred on X/Y")
        }
        val cube = SampleMeshes.create(SampleShape.CUBE).bounds().size
        assertEquals(BoxSize(20.0, 20.0, 20.0), cube)
        val mug = SampleMeshes.create(SampleShape.MUG).bounds().size
        assertEquals(98.0, mug.heightMm, 1e-4)
        assertEquals(92.0, mug.depthMm, 0.1)
    }

    @Test
    fun settingsPreferencesRoundTrip() {
        val custom = AppSettings(
            themeMode = ThemeMode.DARK, units = LengthUnit.INCHES, watertight = false,
            smoothing = Smoothing.HIGH, onboardingDone = true, lastExportFormat = ExportFormat.STL,
        )
        val prefs = mutablePreferencesOf().apply { write(custom) }
        assertEquals(custom, prefs.toAppSettings())
        // Unknown stored values fall back to defaults instead of crashing.
        val junk = mutablePreferencesOf(stringPreferencesKey("theme_mode") to "PURPLE")
        assertEquals(AppSettings(), junk.toAppSettings())
    }

    @Test
    fun deviceSupportMapping() {
        fun r(a: ArServicesStatus, d: DepthStatus) = DeviceCheckResult(a, d, "Test").support
        assertEquals(DeviceSupport.SUPPORTED, r(ArServicesStatus.READY, DepthStatus.SUPPORTED))
        assertEquals(DeviceSupport.UNSUPPORTED, r(ArServicesStatus.READY, DepthStatus.UNSUPPORTED))
        assertEquals(DeviceSupport.UNSUPPORTED, r(ArServicesStatus.UNSUPPORTED, DepthStatus.UNKNOWN))
        assertEquals(DeviceSupport.NEEDS_INSTALL_OR_UPDATE, r(ArServicesStatus.NEEDS_UPDATE, DepthStatus.UNKNOWN))
        assertEquals(DeviceSupport.NEEDS_INSTALL_OR_UPDATE, r(ArServicesStatus.NEEDS_INSTALL, DepthStatus.UNKNOWN))
        assertEquals(DeviceSupport.CHECK_FAILED, r(ArServicesStatus.UNKNOWN, DepthStatus.UNKNOWN))
        assertEquals(DeviceSupport.CHECK_FAILED, r(ArServicesStatus.READY, DepthStatus.UNKNOWN))
    }

    @Test
    fun deviceCheckFailureBecomesCheckFailed() = runTest {
        val vm = DeviceCheckViewModel(object : DeviceCapabilities {
            override suspend fun check(): DeviceCheckResult = error("ARCore exploded")
        })
        val state = assertIs<DeviceCheckUiState.Done>(vm.state.value)
        assertEquals(DeviceSupport.CHECK_FAILED, state.result.support)
    }

    @Test
    fun exportRemembersFormatAndBuildsFileName() = runTest {
        val settings = InMemorySettingsRepository()
        val vm = ExportViewModel("mug-01", FakeScanRepository(), settings)
        assertEquals("Mug_01.3mf", vm.state.value.exportFileName)
        vm.setFormat(ExportFormat.STL)
        vm.setFileName("my mug?")
        assertEquals("my mug_.stl", vm.state.value.exportFileName)
        assertEquals(ExportFormat.STL, settings.settings.first().lastExportFormat)
        assertTrue(vm.state.value.sizes.getValue(ExportFormat.STL) > 0)
    }
}
