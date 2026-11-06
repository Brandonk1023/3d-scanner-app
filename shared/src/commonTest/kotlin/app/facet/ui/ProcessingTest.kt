package app.facet.ui

import app.facet.data.FakeScanRepository
import app.facet.format.formatBytes
import app.facet.format.formatBytesAbout
import app.facet.platform.BackgroundProcessor
import app.facet.platform.FailureReason
import app.facet.platform.ProcessingProgress
import app.facet.platform.ProcessingStage
import app.facet.platform.ProcessingStatus
import app.facet.platform.StorageInfo
import app.facet.scan.ScanTuning
import app.facet.ui.processing.ProcessingPhase
import app.facet.ui.processing.ProcessingViewModel
import app.facet.ui.processing.StageDetail
import app.facet.ui.processing.StageState
import app.facet.ui.processing.stageRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeProcessor : BackgroundProcessor {
    val started = ArrayList<String>()
    val cancelled = ArrayList<String>()
    val status = MutableStateFlow<ProcessingStatus>(ProcessingStatus.Idle)
    override suspend fun start(scanId: String) {
        started += scanId
        status.value = ProcessingStatus.Running(ProcessingProgress())
    }
    override fun cancel(scanId: String) {
        cancelled += scanId
    }
    override fun observe(scanId: String): Flow<ProcessingStatus> = status
}

private class FixedStorage(var free: Long) : StorageInfo {
    override fun freeBytes() = free
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingTest {
    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val gb = 1024L * 1024 * 1024
    private val repo = FakeScanRepository()
    private val processor = FakeProcessor()
    private val storage = FixedStorage(10 * gb)

    // A Quick sample with saved photos (sampleScans: "mug-01").
    private val id = "mug-01"

    private fun vm(start: Boolean) = ProcessingViewModel(id, start, repo, processor, storage, ScanTuning.Default)

    @Test
    fun stageRowsShowPhotosTimesAndCounts() {
        val rows = stageRows(
            ProcessingProgress(
                stage = ProcessingStage.BUILDING_FINE_MESH, overall = 0.62f, photoCount = 214,
                stageDurationsMs = listOf(3_000, 48_000), itemsDone = 3, itemsTotal = 4,
            ),
        )
        assertEquals(listOf(StageState.DONE, StageState.DONE, StageState.CURRENT, StageState.TODO), rows.map { it.state })
        assertEquals(StageDetail.Photos(214), rows[0].detail)
        assertEquals(StageDetail.Elapsed(48_000), rows[1].detail)
        assertEquals(StageDetail.Items(3, 4), rows[2].detail)
        assertNull(rows[3].detail)
        // A single-step stage shows no "1 of 1".
        assertNull(stageRows(ProcessingProgress(itemsDone = 1, itemsTotal = 1))[0].detail)
    }

    @Test
    fun startingChecksStorageThenQueuesTheWork() = runTest {
        val vm = vm(start = true)
        assertEquals(listOf(id), processor.started)
        assertEquals(ProcessingPhase.RUNNING, vm.state.value.phase)
        assertEquals(0, repo.scan(id).first()!!.processingPercent)
    }

    @Test
    fun lowStorageStopsBeforeStarting() = runTest {
        storage.free = gb
        val sample = repo.scan(id).first()!!
        val others = repo.usage.first().savedPhotoBytes - sample.photoBytes
        val vm = vm(start = true)
        val s = vm.state.value
        assertEquals(ProcessingPhase.LOW_STORAGE, s.phase)
        val low = assertNotNull(s.lowStorage)
        assertEquals(gb, low.freeBytes)
        assertEquals(ScanTuning.Default.storage.highDetailEstimateBytes, low.neededBytes)
        // Low-Storage.png: "High detail needs about 2 GB while it works" and the bar's "needs ~2.0 GB".
        assertEquals("2 GB", formatBytesAbout(low.neededBytes))
        assertEquals("2.0 GB", formatBytes(low.neededBytes))
        assertEquals(others, low.reclaimableBytes)
        assertTrue(processor.started.isEmpty())
        assertNull(repo.scan(id).first()!!.processingPercent)

        vm.useQuick() // Keep Quick result
        assertTrue(vm.state.value.openViewer)
    }

    @Test
    fun openingWithoutStartOnlyFollows() = runTest {
        repo.setProcessing(id, 40)
        processor.status.value = ProcessingStatus.Running(ProcessingProgress(stage = ProcessingStage.DEPTH_MAPS, overall = 0.4f))
        val vm = vm(start = false)
        assertTrue(processor.started.isEmpty())
        assertEquals(40, vm.state.value.progress.percent)
        // A start request for a scan that is already processing doesn't queue it twice.
        vm(start = true)
        assertTrue(processor.started.isEmpty())
    }

    @Test
    fun failureThenRetry() = runTest {
        val vm = vm(start = true)
        val failed = ProcessingStatus.Failed(FailureReason.OUT_OF_MEMORY, ProcessingStage.BUILDING_FINE_MESH, "log")
        processor.status.value = failed
        assertEquals(ProcessingPhase.FAILED, vm.state.value.phase)
        assertEquals(failed, vm.state.value.failure)

        vm.retry()
        assertEquals(2, processor.started.size)
        assertEquals(ProcessingPhase.RUNNING, vm.state.value.phase)
        assertNull(vm.state.value.failure)
    }

    @Test
    fun successOpensTheViewer() = runTest {
        val vm = vm(start = true)
        processor.status.value = ProcessingStatus.Succeeded
        assertTrue(vm.state.value.openViewer)
        vm.consumeOpenViewer()
        assertTrue(!vm.state.value.openViewer)
    }

    @Test
    fun cancelStopsTheWorkAndKeepsQuick() = runTest {
        val vm = vm(start = true)
        vm.cancel()
        assertEquals(listOf(id), processor.cancelled)
        assertNull(repo.scan(id).first()!!.processingPercent)
        assertTrue(vm.state.value.openViewer)
    }

    @Test
    fun missingScan() = runTest {
        val vm = ProcessingViewModel("nope", true, repo, processor, storage)
        assertTrue(vm.state.value.missing)
        assertTrue(processor.started.isEmpty())
    }
}
