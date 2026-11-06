package app.facet.android.processing

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import app.facet.android.MainActivity
import app.facet.android.R
import app.facet.platform.ProcessingProgress
import app.facet.platform.ProcessingStage
import java.util.UUID

/** Where a notification tap leads (read by MainActivity). */
enum class DeepLinkTarget { VIEWER, PROCESSING }

data class DeepLink(val target: DeepLinkTarget, val scanId: String)

/**
 * High detail notifications (05-screens.md › Processing): the foreground-service progress
 * notification while it runs, then "Mug_01 is ready" (→ viewer) or "couldn't finish" (→ failure
 * screen). Posting needs POST_NOTIFICATIONS on Android 13+; without it the work still runs.
 */
class ProcessingNotifications(private val context: Context) {

    fun ensureChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.notif_channel_processing), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, context.getString(R.string.notif_channel_results), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** The ongoing notification; the work's foreground service shows it. */
    fun foregroundInfo(workId: UUID, scanId: String, scanName: String, progress: ProcessingProgress?): ForegroundInfo {
        ensureChannels()
        val percent = progress?.percent ?: 0
        val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_cube)
            .setContentTitle(context.getString(R.string.notif_processing, scanName, percent))
            .setContentText(context.getString(stageLabel(progress?.stage ?: ProcessingStage.ALIGNING_PHOTOS)))
            .setProgress(100, percent, progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(open(DeepLink(DeepLinkTarget.PROCESSING, scanId)))
            .addAction(0, context.getString(R.string.proc_cancel), WorkManager.getInstance(context).createCancelPendingIntent(workId))
            .build()
        val id = progressId(scanId)
        return when {
            // Android 15+: "media processing" fits turning saved photos into a model (6 h/day cap).
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> ForegroundInfo(id, notification)
        }
    }

    fun postReady(scanId: String, scanName: String) = post(
        resultId(scanId),
        NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_cube)
            .setContentTitle(context.getString(R.string.notif_ready, scanName))
            .setContentText(context.getString(R.string.notif_ready_body))
            .setAutoCancel(true)
            .setContentIntent(open(DeepLink(DeepLinkTarget.VIEWER, scanId))),
    )

    fun postFailed(scanId: String, scanName: String) = post(
        resultId(scanId),
        NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_cube)
            .setContentTitle(context.getString(R.string.fail_title))
            .setContentText(context.getString(R.string.notif_failed_body, scanName))
            .setAutoCancel(true)
            .setContentIntent(open(DeepLink(DeepLinkTarget.PROCESSING, scanId))),
    )

    fun cancelResult(scanId: String) = NotificationManagerCompat.from(context).cancel(resultId(scanId))

    private fun post(id: Int, builder: NotificationCompat.Builder) {
        ensureChannels()
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build())
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    private fun open(link: DeepLink): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .putExtra(EXTRA_TARGET, link.target.name)
            .putExtra(EXTRA_SCAN_ID, link.scanId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, (link.scanId + link.target).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val CHANNEL_PROGRESS = "processing"
        private const val CHANNEL_RESULTS = "results"
        private const val ACTION_OPEN = "app.facet.action.OPEN_SCAN"
        private const val EXTRA_TARGET = "app.facet.extra.TARGET"
        private const val EXTRA_SCAN_ID = "app.facet.extra.SCAN_ID"

        fun progressId(scanId: String) = scanId.hashCode() * 2
        fun resultId(scanId: String) = scanId.hashCode() * 2 + 1

        fun canPost(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        /** The notification tap that started or resumed MainActivity, if any. */
        fun deepLink(intent: Intent?): DeepLink? {
            if (intent?.action != ACTION_OPEN) return null
            val target = intent.getStringExtra(EXTRA_TARGET)?.let { t -> DeepLinkTarget.entries.firstOrNull { it.name == t } } ?: return null
            val id = intent.getStringExtra(EXTRA_SCAN_ID) ?: return null
            return DeepLink(target, id)
        }

        fun stageLabel(stage: ProcessingStage): Int = when (stage) {
            ProcessingStage.ALIGNING_PHOTOS -> R.string.stage_align
            ProcessingStage.DEPTH_MAPS -> R.string.stage_depth
            ProcessingStage.BUILDING_FINE_MESH -> R.string.stage_mesh
            ProcessingStage.CLEANUP -> R.string.stage_cleanup
        }
    }
}
