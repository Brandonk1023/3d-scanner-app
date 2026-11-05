package app.facet.ui

import app.facet.data.FakeScanRepository
import app.facet.export.ExportFormat
import app.facet.mesh.PipelineStatus
import app.facet.platform.FileExporter
import app.facet.platform.SavedFile
import app.facet.settings.InMemorySettingsRepository
import app.facet.ui.export.ExportAction
import app.facet.ui.export.ExportEvent
import app.facet.ui.export.ExportViewModel
import app.facet.ui.export.ExportWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExportTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val fs = FakeFileSystem()
    private val workDir = "/cache/exports".toPath()

    /** Writes [fileBytes] bytes and records the call, like the native writers. */
    private inner class FakeWriter(var status: PipelineStatus = PipelineStatus.OK, val fileBytes: Int = 1234) : ExportWriter {
        val calls = mutableListOf<Triple<ExportFormat, String, String>>()
        override fun write(mesh: app.facet.mesh.Mesh, format: ExportFormat, path: String, objectName: String): PipelineStatus {
            calls += Triple(format, path, objectName)
            if (status == PipelineStatus.OK) fs.write(path.toPath()) { write(ByteArray(fileBytes)) }
            return status
        }
    }

    private class FakeExporter : FileExporter {
        val saved = mutableListOf<Pair<String, String>>()
        val shared = mutableListOf<Pair<String, String>>()
        override suspend fun save(sourcePath: String, fileName: String, mimeType: String): SavedFile {
            saved += sourcePath to fileName
            return SavedFile("Download/Facet/$fileName", "content://downloads/1", mimeType)
        }
        override fun share(sourcePath: String, fileName: String, mimeType: String) { shared += sourcePath to fileName }
        override fun open(file: SavedFile) = true
    }

    private fun vm(writer: ExportWriter = FakeWriter(), exporter: FileExporter = FakeExporter(), settings: InMemorySettingsRepository = InMemorySettingsRepository()) =
        ExportViewModel("mug-01", FakeScanRepository(), settings, writer, exporter, fs, workDir, dispatcher)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() {
        Dispatchers.resetMain()
        fs.checkNoOpenFiles()
    }

    @Test
    fun remembersFormatAndBuildsFileName() = runTest {
        val settings = InMemorySettingsRepository()
        val vm = vm(settings = settings)
        assertEquals("Mug_01.3mf", vm.state.value.exportFileName)
        vm.setFormat(ExportFormat.STL)
        vm.setFileName("my mug?")
        assertEquals("my mug_.stl", vm.state.value.exportFileName)
        assertEquals(ExportFormat.STL, settings.settings.first().lastExportFormat)
        assertTrue(vm.state.value.sizes.getValue(ExportFormat.STL) > 0)
    }

    @Test
    fun threeMfSizeIsMeasuredFromTheRealFile() = runTest {
        val vm = vm(writer = FakeWriter(fileBytes = 4321))
        assertEquals(4321L, vm.state.value.sizes[ExportFormat.THREE_MF])
        // The probe file is cleaned up.
        assertTrue(fs.listOrNull(workDir).orEmpty().isEmpty())
    }

    @Test
    fun saveWritesTheFileAndReportsWhereItWent() = runTest {
        val writer = FakeWriter()
        val exporter = FakeExporter()
        val vm = vm(writer, exporter)
        vm.setFileName("Lid")
        vm.save()

        val (format, path, objectName) = writer.calls.last()
        assertEquals(ExportFormat.THREE_MF, format)
        assertEquals("/cache/exports/Lid.3mf", path)
        assertEquals("Lid", objectName)
        assertEquals(listOf(path to "Lid.3mf"), exporter.saved)
        val event = assertIs<ExportEvent.Saved>(vm.state.value.event)
        assertEquals("Download/Facet/Lid.3mf", event.file.location)
        assertNull(vm.state.value.busy)

        vm.consumeEvent()
        assertNull(vm.state.value.event)
    }

    @Test
    fun shareReplacesThePreviousExportFile() = runTest {
        val exporter = FakeExporter()
        val vm = vm(exporter = exporter)
        vm.setFormat(ExportFormat.STL)
        vm.share()
        vm.setFileName("Other")
        vm.share()
        assertEquals(listOf("Mug_01.stl", "Other.stl"), exporter.shared.map { it.second })
        assertEquals(listOf("Other.stl"), fs.list(workDir).map { it.name })
    }

    @Test
    fun writerFailureReportsAndNothingIsSaved() = runTest {
        val writer = FakeWriter(status = PipelineStatus.IO)
        val exporter = FakeExporter()
        val vm = vm(writer, exporter)
        vm.save()
        assertEquals(ExportEvent.Failed(ExportAction.SAVE), vm.state.value.event)
        assertTrue(exporter.saved.isEmpty())
    }

    @Test
    fun exporterFailureReports() = runTest {
        val vm = vm(exporter = object : FileExporter {
            override suspend fun save(sourcePath: String, fileName: String, mimeType: String): SavedFile = throw okio.IOException("disk full")
            override fun share(sourcePath: String, fileName: String, mimeType: String) = Unit
            override fun open(file: SavedFile) = false
        })
        vm.save()
        assertEquals(ExportEvent.Failed(ExportAction.SAVE), vm.state.value.event)
        assertNull(vm.state.value.busy)
    }
}
