package app.facet.android.processing

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import app.facet.data.ScanRepository
import app.facet.platform.BackgroundProcessor
import app.facet.platform.ProcessingProgress
import app.facet.platform.ProcessingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * High detail on WorkManager: one unique work per scan ([HighDetailWorker]), expedited when quota
 * allows, with a foreground notification. WorkManager keeps the work across process death and
 * reboots; finished work's outcome stays readable for a while, which the failure screen uses.
 */
class AndroidBackgroundProcessor(
    private val context: Context,
    private val scans: ScanRepository,
) : BackgroundProcessor {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override suspend fun start(scanId: String) {
        val request = OneTimeWorkRequestBuilder<HighDetailWorker>()
            .setInputData(workDataOf(HighDetailWorker.KEY_SCAN_ID to scanId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        // KEEP: a run already queued or running continues; a finished one is replaced.
        workManager.enqueueUniqueWork(workName(scanId), ExistingWorkPolicy.KEEP, request).await()
    }

    override fun cancel(scanId: String) {
        workManager.cancelUniqueWork(workName(scanId))
    }

    override fun observe(scanId: String): Flow<ProcessingStatus> =
        workManager.getWorkInfosForUniqueWorkFlow(workName(scanId)).map { infos -> infos.lastOrNull()?.toStatus() ?: ProcessingStatus.Idle }

    /**
     * App start: a scan still marked "Processing" without queued or running work (the app was
     * force-stopped, or the work was cleared) goes back to its Quick result.
     */
    suspend fun reconcile() {
        scans.scans.first().filter { it.processingPercent != null }.forEach { scan ->
            val active = workManager.getWorkInfosForUniqueWorkFlow(workName(scan.id)).first().any { !it.state.isFinished }
            if (!active) scans.setProcessing(scan.id, null)
        }
    }

    private fun WorkInfo.toStatus(): ProcessingStatus = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ProcessingStatus.Running(ProcessingProgress())
        WorkInfo.State.RUNNING -> ProcessingStatus.Running(HighDetailWorker.progressFrom(progress) ?: ProcessingProgress())
        WorkInfo.State.SUCCEEDED -> ProcessingStatus.Succeeded
        WorkInfo.State.FAILED -> HighDetailWorker.failureFrom(outputData)
        WorkInfo.State.CANCELLED -> ProcessingStatus.Idle
    }

    companion object {
        private const val TAG = "high-detail"
        fun workName(scanId: String) = "high-detail-$scanId"
    }
}
