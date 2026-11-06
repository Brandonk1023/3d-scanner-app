package app.facet.processing

import app.facet.platform.ProcessingProgress
import app.facet.platform.ProcessingStage
import app.facet.scan.ScanTuning
import kotlin.math.roundToInt

/**
 * Turns "stage S, k of N done" into the overall percentage, stage timings and "~N min left" for
 * the Processing screen, the notification and the gallery card. [now] is a millisecond clock.
 */
class ProgressTracker(
    private val tuning: ScanTuning.HighDetail,
    private val now: () -> Long,
) {
    private val startMs = now()
    private var stageStartMs = startMs
    private val durations = ArrayList<Long>()
    private var lastGalleryPercent = 0

    var progress = ProcessingProgress(overall = 0f)
        private set

    /** Moves on to [stage], timing the one(s) before it. */
    fun enter(stage: ProcessingStage): ProcessingProgress {
        val t = now()
        while (durations.size < stage.ordinal) {
            durations += t - stageStartMs
            stageStartMs = t
        }
        stageStartMs = t
        return set(stage, 0, 0, 0f)
    }

    /** [itemsDone] of [itemsTotal] in the current stage; [fraction] defaults to their ratio. */
    fun update(
        itemsDone: Int,
        itemsTotal: Int,
        fraction: Float = if (itemsTotal > 0) itemsDone.toFloat() / itemsTotal else 0f,
    ): ProcessingProgress = set(progress.stage, itemsDone, itemsTotal, fraction)

    fun photos(count: Int) {
        progress = progress.copy(photoCount = count)
    }

    /** True when the gallery percentage moved by at least [ScanTuning.HighDetail.galleryPercentStep]. */
    fun shouldWriteGalleryPercent(): Boolean {
        val p = progress.percent
        if (p - lastGalleryPercent < tuning.galleryPercentStep) return false
        lastGalleryPercent = p
        return true
    }

    private fun set(stage: ProcessingStage, done: Int, total: Int, fraction: Float): ProcessingProgress {
        // Never goes backwards, even if a stage's estimate does.
        val overall = maxOf(progress.overall, overall(tuning, stage, fraction))
        progress = progress.copy(
            stage = stage,
            overall = overall,
            remainingSeconds = remainingSeconds(tuning, overall, now() - startMs),
            stageDurationsMs = durations.toList(),
            itemsDone = done,
            itemsTotal = total,
        )
        return progress
    }

    companion object {
        /** 0..1 overall, from the weights of the stages before [stage] plus [fraction] of its own. */
        fun overall(tuning: ScanTuning.HighDetail, stage: ProcessingStage, fraction: Float): Float {
            val w = tuning.stageWeights
            val total = w.sum()
            val before = w.take(stage.ordinal).sum()
            val f = fraction.coerceIn(0f, 1f)
            return ((before + w[stage.ordinal] * f) / total).toFloat().coerceIn(0f, 1f)
        }

        /** Linear extrapolation of the time so far; null until enough has happened to guess. */
        fun remainingSeconds(tuning: ScanTuning.HighDetail, overall: Float, elapsedMs: Long): Int? {
            if (overall < tuning.etaMinProgress || elapsedMs < tuning.etaMinElapsedMs || overall >= 1f) return null
            val totalMs = elapsedMs / overall
            return ((totalMs - elapsedMs) / 1000.0).roundToInt().coerceAtLeast(0)
        }
    }
}
