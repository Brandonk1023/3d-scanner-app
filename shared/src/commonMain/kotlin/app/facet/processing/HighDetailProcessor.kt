package app.facet.processing

import app.facet.data.Keyframe
import app.facet.data.ScanFolder
import app.facet.data.ScanRepository
import app.facet.mesh.DepthFrame
import app.facet.mesh.Mesh
import app.facet.mesh.MeshEngine
import app.facet.mesh.ScanPipeline
import app.facet.mesh.TsdfVolume
import app.facet.mesh.scaled
import app.facet.platform.FailureReason
import app.facet.platform.ProcessingProgress
import app.facet.platform.ProcessingStage
import app.facet.platform.ProcessingStatus
import app.facet.scan.DepthCalibration
import app.facet.scan.ScanTuning
import app.facet.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.IOException

/**
 * High detail (docs/04 §4): rebuilds a scan from its saved keyframes at a finer resolution, in the
 * four stages the Processing screen shows, then swaps the result into the gallery.
 *
 * **Placeholder method.** The real fine-mesh method is a research spike. For now:
 * 1. Aligning photos: read and check the keyframe poses (no refinement yet).
 * 2. Depth maps: load each saved depth map and apply the scan's depth calibration.
 * 3. Building fine mesh: fuse them into a TSDF at [ScanTuning.highDetailVolumeVoxelMm] and carve
 *    with the saved object masks (visual hull when there are enough, else the depth surface).
 * 4. Cleanup & watertight: the Quick result's cleanup with the current Settings, then the scale
 *    correction the scan already had.
 *
 * Blocking native work: call [run] from a background dispatcher. Cancelling the coroutine stops at
 * the next photo and leaves the Quick result in place.
 */
class HighDetailProcessor(
    private val engine: MeshEngine,
    private val repository: ScanRepository,
    private val folder: (scanId: String) -> ScanFolder,
    private val settings: suspend () -> AppSettings,
    private val tuning: ScanTuning = ScanTuning.Default,
    /** Millisecond clock for stage timings and the time-left estimate. */
    private val now: () -> Long,
) {
    /**
     * Runs the pass for [scanId]. Returns [ProcessingStatus.Succeeded] or [ProcessingStatus.Failed];
     * either way the scan's "Processing NN%" is cleared. [debugFailAt] (debug builds) fails on
     * purpose when that stage starts, to check the failure screen.
     */
    suspend fun run(
        scanId: String,
        debugFailAt: ProcessingStage? = null,
        onProgress: suspend (ProcessingProgress) -> Unit,
    ): ProcessingStatus {
        val log = StringBuilder("High detail · scan $scanId\n")
        val tracker = ProgressTracker(tuning.highDetail, now)
        var stage: ProcessingStage? = null
        suspend fun enter(s: ProcessingStage) {
            stage = s
            log.append("stage ${s.ordinal + 1}/4 ${s.name} at ${tracker.progress.percent}%\n")
            report(scanId, tracker, tracker.enter(s), onProgress)
            if (s == debugFailAt) throw StageFailure(FailureReason.OUT_OF_MEMORY, "simulated failure (debug setting)")
        }
        return try {
            repository.setProcessing(scanId, 0)
            val mesh = build(scanId, tracker, log, { enter(it) }, onProgress)
            repository.completeHighDetail(scanId, mesh)
            ProcessingStatus.Succeeded
        } catch (e: CancellationException) {
            withContext(NonCancellable) { repository.setProcessing(scanId, null) }
            throw e
        } catch (e: Throwable) {
            val reason = when {
                e is StageFailure -> e.reason
                e is IOException -> FailureReason.STORAGE
                e::class.simpleName?.contains("OutOfMemory") == true -> FailureReason.OUT_OF_MEMORY
                else -> FailureReason.UNKNOWN
            }
            log.append("failed: ${e.message ?: e::class.simpleName}\n")
            if (e !is StageFailure) log.append(e.stackTraceToString())
            repository.setProcessing(scanId, null)
            ProcessingStatus.Failed(reason, stage, log.toString())
        }
    }

    private suspend fun build(
        scanId: String,
        tracker: ProgressTracker,
        log: StringBuilder,
        enter: suspend (ProcessingStage) -> Unit,
        onProgress: suspend (ProcessingProgress) -> Unit,
    ): Mesh {
        val files = folder(scanId)

        // 1 · Aligning photos
        enter(ProcessingStage.ALIGNING_PHOTOS)
        val info = files.readCaptureInfo() ?: throw StageFailure(FailureReason.OLD_PHOTOS, "no capture.json")
        val keyframes = files.keyframes().filter { it.meta.depth != null }
        log.append("box ${info.box}, depthScale ${info.depthScale}, scaleFactor ${info.scaleFactor}, keyframes ${keyframes.size}\n")
        if (keyframes.isEmpty()) throw StageFailure(FailureReason.NO_PHOTOS, "no keyframes with depth")
        tracker.photos(keyframes.size)
        report(scanId, tracker, tracker.update(1, 1), onProgress)

        // 2 · Depth maps
        enter(ProcessingStage.DEPTH_MAPS)
        val frames = ArrayList<Pair<Keyframe, DepthFrame>>(keyframes.size)
        keyframes.forEachIndexed { i, kf ->
            currentCoroutineContext().ensureActive()
            files.loadDepth(kf)?.let { raw ->
                val scale = info.depthScale ?: kf.meta.depth?.scale
                frames += kf to if (scale != null) DepthCalibration.scaled(raw, scale) else raw
            }
            report(scanId, tracker, tracker.update(i + 1, keyframes.size), onProgress)
        }
        log.append("depth maps loaded ${frames.size}\n")
        if (frames.isEmpty()) throw StageFailure(FailureReason.NO_PHOTOS, "no readable depth maps")

        // 3 · Building fine mesh
        enter(ProcessingStage.BUILDING_FINE_MESH)
        val r = tuning.reconstruction
        val volumeSize = tuning.volumeSize(info.box)
        val voxel = tuning.highDetailVolumeVoxelMm(volumeSize)
        log.append("volume $volumeSize voxel ${voxel}mm\n")
        val volume = engine.createVolume(volumeSize, voxel, tuning.truncationMm(voxel))
            ?: throw StageFailure(FailureReason.OUT_OF_MEMORY, "fine volume allocation failed")
        val raw: Mesh
        val carved: Boolean
        try {
            val withMasks = frames.count { it.first.meta.mask != null }
            val carve = withMasks >= r.carveMinViews
            frames.forEachIndexed { i, (kf, depth) ->
                currentCoroutineContext().ensureActive()
                volume.integrate(depth, kf.meta.cameraToBox.toFloatArray(), r.fuseMinConfidence)
                if (carve) carve(volume, files, kf)
                report(scanId, tracker, tracker.update(i + 1, frames.size, FUSE_SHARE * (i + 1) / frames.size), onProgress)
            }
            carved = carve && volume.carvedViews >= r.carveMinViews
            raw = if (carved) volume.extractHull(r.carveMaxOutsideFraction, r.carveMinSeen) else volume.extract(1, r.meshMinWeight)
            log.append("carved views ${volume.carvedViews}, raw triangles ${raw.triangleCount}\n")
        } finally {
            volume.close()
        }
        if (raw.triangleCount == 0) throw StageFailure(FailureReason.EMPTY_RESULT, "fusion produced no surface")
        report(scanId, tracker, tracker.update(frames.size, frames.size, 1f), onProgress)

        // 4 · Cleanup & watertight
        enter(ProcessingStage.CLEANUP)
        val s = settings()
        val cleanup = tuning.cleanup.params(s.watertight, s.smoothing, hasTable = !carved)
        val out = ScanPipeline(engine).finalMesh(raw, cleanup, ScanPipeline.Options(targetTriangles = tuning.highDetail.targetTriangles))
        val mesh = if (info.scaleFactor != 1.0) out.mesh.scaled(info.scaleFactor) else out.mesh
        log.append("final triangles ${mesh.triangleCount}, repaired ${out.repaired} (${out.repairStatus})\n")
        if (mesh.triangleCount == 0) throw StageFailure(FailureReason.EMPTY_RESULT, "cleanup left nothing")
        report(scanId, tracker, tracker.update(1, 1), onProgress)
        return mesh
    }

    private fun carve(volume: TsdfVolume, files: ScanFolder, kf: Keyframe) {
        val m = kf.meta.mask ?: return
        val mask = files.loadMask(kf) ?: return
        volume.carve(mask, m.fx, m.fy, m.cx, m.cy, kf.meta.cameraToBox.toFloatArray())
    }

    private suspend fun report(
        scanId: String,
        tracker: ProgressTracker,
        progress: ProcessingProgress,
        onProgress: suspend (ProcessingProgress) -> Unit,
    ) {
        onProgress(progress)
        if (tracker.shouldWriteGalleryPercent()) repository.setProcessing(scanId, progress.percent)
    }

    private class StageFailure(val reason: FailureReason, message: String) : Exception(message)

    private companion object {
        /** Fusing the photos is most of stage 3; extraction is the rest. */
        const val FUSE_SHARE = 0.85f
    }
}
