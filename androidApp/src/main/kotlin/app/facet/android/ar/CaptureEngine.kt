package app.facet.android.ar

import android.os.SystemClock
import android.util.Log
import app.facet.mesh.DepthFrame
import app.facet.mesh.Mesh
import app.facet.mesh.TsdfVolume
import app.facet.scan.DepthCalibration
import app.facet.scan.ObjectSegmenter
import app.facet.scan.PlacedBox
import app.facet.scan.ScanTuning
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.exceptions.NotYetAvailableException
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Live capture for one scan (docs/04 §2, §4): fuses ARCore raw depth (weighted by confidence)
 * into the box's TSDF volume on a worker thread, keeps a coarse preview mesh for the AR overlay,
 * and saves keyframes for High detail.
 *
 * GL thread: [onFrame], [draw]. Fusion and extraction: the single worker. The volume is only ever
 * touched by the worker.
 */
class CaptureEngine internal constructor(
    private val volume: TsdfVolume,
    val box: PlacedBox,
    /** Scan working folder (keyframes, saved volume). */
    val scanDir: File,
    private val tuning: ScanTuning,
    private val onFused: (meanConfidence: Float) -> Unit,
    /** Object outlines for silhouette carving; null = depth-only. */
    segmenterFactory: (() -> ObjectSegmenter)? = null,
) {
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "facet-fusion") }
    private val busy = AtomicBoolean(false)
    private val renderer = LiveMeshRenderer()
    private val keyframes = KeyframeWriter(
        File(scanDir, "keyframes"),
        tuning.keyframes,
        tuning.reconstruction,
        segmenterFactory,
        seedInBox = floatArrayOf(0f, 0f, (box.size.heightMm * tuning.reconstruction.carveSeedHeight).toFloat()),
    ) { mask, fx, fy, cx, cy, cameraToBox ->
        worker.execute {
            if (!closed) runCatching { volume.carve(mask, fx, fy, cx, cy, cameraToBox) }.onFailure { Log.w(TAG, "Carve failed", it) }
        }
    }
    private var lastDepthTimestamp = -1L
    private var lastPreviewMs = 0L
    private var transforms: BoxTransforms? = null
    private var transformsAnchor: FloatArray? = null
    private var latestDepth: KeyframeWriter.Depth? = null
    private var latestDepthTimestamp = -1L
    @Volatile private var closed = false

    /**
     * Depth scale from [DepthCalibration]: the running median of per-frame estimates, locked once
     * [ScanTuning.Reconstruction.calibrationLockFrames] are in (a wandering scale smears views).
     * Null until the table has been seen.
     */
    @Volatile private var depthScale: Double? = null
    private val scaleEstimates = ArrayList<Double>()

    // Stats for the periodic log (worker thread).
    private var statFrames = 0
    private var statFused = 0
    private var statUncalibrated = 0
    private var statMs = 0L
    private var statConfidence = 0.0
    private var statLastLog = 0L

    /** Set by the UI: fuse depth now (capturing, no sheet, tracking OK). */
    @Volatile var fusing: Boolean = false

    /** Set by the UI: show the live mesh. */
    @Volatile var meshVisible: Boolean = true

    /** Set by the UI: dim the mesh (tracking lost). */
    @Volatile var dimmed: Boolean = false

    val keyframeCount: Int get() = keyframes.savedCount
    val keyframeBytes: Long get() = keyframes.savedBytes

    internal fun glCreate() = renderer.create()

    private fun transformsFor(anchor: FloatArray): BoxTransforms {
        val t = transforms
        if (t != null && transformsAnchor.contentEquals(anchor)) return t
        return BoxTransforms(anchor, box).also {
            transforms = it
            transformsAnchor = anchor.copyOf()
        }
    }

    /** World m ← box mm for the current anchor pose. */
    internal fun worldFromBox(anchor: FloatArray): FloatArray = transformsFor(anchor).worldFromBox

    /** GL thread, once per tracked frame. [poseAt] finds the camera pose a depth map was taken at. */
    internal fun onFrame(frame: Frame, anchor: FloatArray, poseAt: (Long) -> Pose?) {
        if (closed) return
        val t = transformsFor(anchor)
        if (fusing && !busy.get()) submitDepth(frame, t, poseAt)
        if (fusing) maybeKeyframe(frame, t)
    }

    private fun submitDepth(frame: Frame, t: BoxTransforms, poseAt: (Long) -> Pose?) {
        val depthImage = try {
            frame.acquireRawDepthImage16Bits()
        } catch (_: NotYetAvailableException) {
            return
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Raw depth unavailable", e)
            return
        }
        val depth: KeyframeWriter.Depth
        val timestamp: Long
        depthImage.use { img ->
            timestamp = img.timestamp
            if (timestamp == lastDepthTimestamp) return
            val w = img.width
            val h = img.height
            val plane = img.planes[0]
            val src = plane.buffer.order(ByteOrder.nativeOrder())
            val values = ShortArray(w * h)
            for (row in 0 until h) {
                src.position(row * plane.rowStride)
                src.asShortBuffer().get(values, row * w, w)
            }
            val confidence = try {
                frame.acquireRawDepthConfidenceImage().use { c ->
                    val cp = c.planes[0]
                    val cb = cp.buffer
                    val out = ByteArray(w * h)
                    for (row in 0 until h) {
                        cb.position(row * cp.rowStride)
                        cb.get(out, row * w, w)
                    }
                    out
                }
            } catch (_: Exception) {
                null
            }
            val intr = frame.camera.textureIntrinsics
            val dims = intr.imageDimensions
            val sx = w.toFloat() / dims[0]
            val sy = h.toFloat() / dims[1]
            depth = KeyframeWriter.Depth(
                w, h, values, confidence,
                intr.focalLength[0] * sx, intr.focalLength[1] * sy,
                intr.principalPoint[0] * sx, intr.principalPoint[1] * sy,
            )
        }
        lastDepthTimestamp = timestamp
        latestDepth = depth
        latestDepthTimestamp = timestamp
        val pose = poseAt(timestamp) ?: return
        val cameraToBox = t.cameraToBox(pose)
        if (!busy.compareAndSet(false, true)) return
        worker.execute {
            try {
                if (closed) return@execute
                val start = SystemClock.elapsedRealtime()
                val raw = DepthFrame(depth.width, depth.height, depth.depth, depth.confidence, depth.fx, depth.fy, depth.cx, depth.cy)
                statFrames++
                // Calibrate depth against the AR table before fusing (it reads ~10% long on some phones).
                if (scaleEstimates.size < tuning.reconstruction.calibrationLockFrames) {
                    DepthCalibration.tableScale(raw, cameraToBox, box.size, tuning.reconstruction)?.let { estimate ->
                        scaleEstimates += estimate
                        depthScale = scaleEstimates.sorted()[scaleEstimates.size / 2]
                    }
                }
                val scale = depthScale
                if (scale == null) {
                    statUncalibrated++
                } else {
                    val r = volume.integrate(DepthCalibration.scaled(raw, scale), cameraToBox, tuning.reconstruction.fuseMinConfidence)
                    if (r.surfaceSamples > 0) {
                        statFused++
                        statConfidence += r.meanConfidence
                        onFused(r.meanConfidence)
                    }
                }
                val now = SystemClock.elapsedRealtime()
                statMs += now - start
                if (now - statLastLog >= STATS_INTERVAL_MS) {
                    Log.i(
                        TAG,
                        "fusion: depth=$statFrames fused=$statFused uncalibrated=$statUncalibrated " +
                            "scale=${depthScale?.let { "%.3f".format(it) }} conf=${if (statFused > 0) "%.2f".format(statConfidence / statFused) else "-"} " +
                            "avg=${if (statFrames > 0) statMs / statFrames else 0}ms keyframes=${keyframes.savedCount} masks=${keyframes.maskCount}",
                    )
                    statLastLog = now
                    statFrames = 0; statFused = 0; statUncalibrated = 0; statMs = 0; statConfidence = 0.0
                }
                if (now - lastPreviewMs >= (1000 / tuning.reconstruction.livePreviewMinHz).toLong()) {
                    lastPreviewMs = now
                    renderer.submit(clipTable(volume.extract(tuning.reconstruction.livePreviewStep, tuning.reconstruction.meshMinWeight)))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fusion failed", e)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun maybeKeyframe(frame: Frame, t: BoxTransforms) {
        val cameraToBox = t.cameraToBox(frame.camera.pose)
        if (!keyframes.wants(cameraToBox)) return
        val image = try {
            frame.acquireCameraImage()
        } catch (_: Exception) {
            return
        }
        image.use {
            val intr = frame.camera.imageIntrinsics
            val depth = latestDepth?.takeIf { kotlin.math.abs(latestDepthTimestamp - frame.timestamp) <= MAX_DEPTH_SKEW_NS }
            keyframes.submit(
                it, frame.timestamp, cameraToBox,
                intr.focalLength[0], intr.focalLength[1], intr.principalPoint[0], intr.principalPoint[1],
                depth, depthScale,
            )
        }
    }

    /** GL thread. */
    internal fun draw(view: FloatArray, projection: FloatArray, anchor: FloatArray) {
        if (!meshVisible || closed) return
        renderer.draw(view, projection, worldFromBox(anchor), if (dimmed) TRACKING_LOST_ALPHA else 1f)
    }

    /** The raw Quick result before cleanup (box frame, mm). [hasTable]: the depth-only mesh still includes the table. */
    class RawResult(val mesh: Mesh, val hasTable: Boolean)

    /**
     * The Quick result before cleanup: the silhouette-carved hull when enough photos were
     * segmented, else the depth-only mesh (which still has the table in it).
     */
    suspend fun extractFinal(): RawResult = onWorker {
        val r = tuning.reconstruction
        val views = volume.carvedViews
        Log.i(TAG, "final: carved views=$views (need ${r.carveMinViews})")
        if (views >= r.carveMinViews) {
            RawResult(volume.extractHull(r.carveMaxOutsideFraction, r.carveMinSeen), hasTable = false)
        } else {
            RawResult(volume.extract(1, r.meshMinWeight), hasTable = true)
        }
    }

    /**
     * Live preview only: drops triangles lying entirely within [ScanTuning.Cleanup.tableClipMm] of
     * the box base. The final mesh gets the full cleanup instead (ScanPipeline.finalMesh).
     */
    private fun clipTable(mesh: Mesh): Mesh {
        val limit = tuning.cleanup.tableClipMm
        val p = mesh.positions
        val kept = ArrayList<Int>(mesh.indices.size)
        val idx = mesh.indices
        for (t in 0 until mesh.triangleCount) {
            val a = idx[t * 3]
            val b = idx[t * 3 + 1]
            val c = idx[t * 3 + 2]
            if (p[a * 3 + 2] < limit && p[b * 3 + 2] < limit && p[c * 3 + 2] < limit) continue
            kept += a; kept += b; kept += c
        }
        return Mesh(p, kept.toIntArray())
    }

    /** Persists the volume (scan interrupted). */
    suspend fun saveVolume(): Boolean = onWorker { volume.save(File(scanDir, VOLUME_FILE).path) }

    /** Frees the volume and stops the threads. Safe to call twice. */
    fun close() {
        if (closed) return
        closed = true
        worker.execute { volume.close() }
        worker.shutdown()
        keyframes.close()
    }

    private suspend fun <T> onWorker(block: () -> T): T = suspendCancellableCoroutine { cont ->
        worker.execute { cont.resumeWith(runCatching(block)) }
    }

    companion object {
        private const val TAG = "FacetCapture"
        private const val STATS_INTERVAL_MS = 2_000L
        const val VOLUME_FILE = "volume.bin"
        private const val TRACKING_LOST_ALPHA = 0.4f
        private const val MAX_DEPTH_SKEW_NS = 50_000_000L
    }
}
