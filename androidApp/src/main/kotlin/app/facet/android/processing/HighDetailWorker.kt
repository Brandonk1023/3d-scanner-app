package app.facet.android.processing

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.facet.android.appContainer
import app.facet.platform.FailureReason
import app.facet.platform.ProcessingProgress
import app.facet.platform.ProcessingStage
import app.facet.platform.ProcessingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Runs [app.facet.processing.HighDetailProcessor] for one scan as expedited work with a foreground
 * notification, so it keeps going after the user leaves the app. Progress goes out as WorkManager
 * progress data (Processing screen) and the notification; the outcome as output data.
 */
class HighDetailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifications = ProcessingNotifications(context)
    private val scanId: String? get() = inputData.getString(KEY_SCAN_ID)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val id = scanId.orEmpty()
        val name = applicationContext.appContainer.scans.scan(id).first()?.name ?: id
        return notifications.foregroundInfo(this.id, id, name, null)
    }

    override suspend fun doWork(): Result {
        val scanId = scanId ?: return Result.failure()
        val container = applicationContext.appContainer
        val scan = container.scans.scan(scanId).first()
            ?: return Result.failure(failure(FailureReason.UNKNOWN, null, "scan $scanId no longer exists"))
        notifications.cancelResult(scanId)
        foreground(notifications.foregroundInfo(id, scanId, scan.name, ProcessingProgress()))

        val failAt = if (container.debug.consumeFailNext()) ProcessingStage.BUILDING_FINE_MESH else null
        var lastSent = 0L
        var lastStage: ProcessingStage? = null
        val status = withContext(Dispatchers.Default) {
            container.highDetail.run(scanId, failAt) { p ->
                // WorkManager progress and the notification don't need every photo.
                val now = SystemClock.elapsedRealtime()
                if (p.stage != lastStage || now - lastSent >= PROGRESS_INTERVAL_MS || p.percent == 100) {
                    lastSent = now
                    lastStage = p.stage
                    setProgress(p.toData())
                    foreground(notifications.foregroundInfo(id, scanId, scan.name, p))
                }
            }
        }
        val name = container.scans.scan(scanId).first()?.name ?: scan.name
        return when (status) {
            is ProcessingStatus.Failed -> {
                Log.w(TAG, status.technicalLog)
                if (container.visibleProcessingScan != scanId) notifications.postFailed(scanId, name)
                Result.failure(failure(status.reason, status.stage, status.technicalLog))
            }
            else -> {
                if (container.visibleProcessingScan != scanId) notifications.postReady(scanId, name)
                Result.success()
            }
        }
    }

    /** Foreground can be refused (background start limits); the work still runs, just less protected. */
    private suspend fun foreground(info: ForegroundInfo) {
        try {
            setForeground(info)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Couldn't run in the foreground", e)
        }
    }

    companion object {
        private const val TAG = "FacetHighDetail"
        const val KEY_SCAN_ID = "scanId"
        private const val PROGRESS_INTERVAL_MS = 300L

        private const val KEY_STAGE = "stage"
        private const val KEY_OVERALL = "overall"
        private const val KEY_REMAINING = "remaining"
        private const val KEY_PHOTOS = "photos"
        private const val KEY_DURATIONS = "durations"
        private const val KEY_DONE = "done"
        private const val KEY_TOTAL = "total"
        private const val KEY_REASON = "reason"
        private const val KEY_LOG = "log"

        /** WorkManager's Data is capped at 10 KB. */
        private const val MAX_LOG_CHARS = 8_000

        fun ProcessingProgress.toData(): Data = workDataOf(
            KEY_STAGE to stage.ordinal,
            KEY_OVERALL to overall,
            KEY_REMAINING to (remainingSeconds ?: -1),
            KEY_PHOTOS to (photoCount ?: -1),
            KEY_DURATIONS to stageDurationsMs.toLongArray(),
            KEY_DONE to itemsDone,
            KEY_TOTAL to itemsTotal,
        )

        /** Null when [data] carries no progress yet (just started). */
        fun progressFrom(data: Data): ProcessingProgress? {
            if (!data.keyValueMap.containsKey(KEY_STAGE)) return null
            return ProcessingProgress(
                stage = ProcessingStage.entries.getOrElse(data.getInt(KEY_STAGE, 0)) { ProcessingStage.ALIGNING_PHOTOS },
                overall = data.getFloat(KEY_OVERALL, 0f),
                remainingSeconds = data.getInt(KEY_REMAINING, -1).takeIf { it >= 0 },
                photoCount = data.getInt(KEY_PHOTOS, -1).takeIf { it >= 0 },
                stageDurationsMs = data.getLongArray(KEY_DURATIONS)?.toList().orEmpty(),
                itemsDone = data.getInt(KEY_DONE, 0),
                itemsTotal = data.getInt(KEY_TOTAL, 0),
            )
        }

        fun failure(reason: FailureReason, stage: ProcessingStage?, log: String): Data = workDataOf(
            KEY_REASON to reason.name,
            KEY_STAGE to (stage?.ordinal ?: -1),
            KEY_LOG to log.takeLast(MAX_LOG_CHARS),
        )

        fun failureFrom(data: Data): ProcessingStatus.Failed = ProcessingStatus.Failed(
            reason = data.getString(KEY_REASON)?.let { r -> FailureReason.entries.firstOrNull { it.name == r } } ?: FailureReason.UNKNOWN,
            stage = ProcessingStage.entries.getOrNull(data.getInt(KEY_STAGE, -1)),
            technicalLog = data.getString(KEY_LOG).orEmpty(),
        )
    }
}
