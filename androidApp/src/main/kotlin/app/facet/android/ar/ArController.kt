package app.facet.android.ar

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import androidx.core.content.ContextCompat
import app.facet.geometry.Vec3
import app.facet.scan.BoxAutoFit
import app.facet.scan.FitSeed
import app.facet.scan.PlacedBox
import app.facet.scan.ScanTuning
import app.facet.scan.TrackingState
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionPausedException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.ceil
import com.google.ar.core.TrackingState as ArTrackingState

/** Results the AR layer reports back to the placement screen. */
sealed interface ArEvent {
    /** A tap placed the box. [fitted] = sized from depth, false = default cube. */
    data class BoxPlaced(val box: PlacedBox, val fitted: Boolean) : ArEvent

    /** A tap is being measured (depth gathered over a few frames). BoxPlaced follows. */
    data object Measuring : ArEvent

    /** The tap didn't land on the table or on something standing on it. */
    data object PlaceMissed : ArEvent

    /** Fit to object finished; null = nothing recognisable near the box. */
    data class Fitted(val box: PlacedBox?) : ArEvent

    /** Capture: a depth map was fused into the volume (any thread → collected on main). */
    data class DepthFused(val meanConfidence: Float) : ArEvent
}

sealed interface ArStatus {
    data object Starting : ArStatus
    data object Running : ArStatus
    data class Failed(val reason: Reason) : ArStatus

    enum class Reason { CAMERA_UNAVAILABLE, AR_UNAVAILABLE }
}

/**
 * Owns the ARCore session for the scan route and renders the camera feed into a [GLSurfaceView].
 * Overlays (planes, reticle, box, handles) are drawn by Compose from the [frames] snapshots.
 *
 * Threading: lifecycle calls ([resume], [pause], [close]) on the main thread; everything that
 * touches a Frame or Anchor runs on the GL thread, so UI requests are queued as commands.
 */
class ArController(context: Context, private val tuning: ScanTuning = ScanTuning.Default) : GLSurfaceView.Renderer {
    private val appContext = context.applicationContext

    private val _status = MutableStateFlow<ArStatus>(ArStatus.Starting)
    val status: StateFlow<ArStatus> = _status

    private val _frames = MutableStateFlow<ArFrameSnapshot?>(null)
    val frames: StateFlow<ArFrameSnapshot?> = _frames

    private val _events = MutableSharedFlow<ArEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ArEvent> = _events

    /** Set by the UI: compute plane outlines and dots (Find surface only). */
    @Volatile var showPlanes: Boolean = true

    @Volatile private var session: Session? = null

    /** The running capture, if any (Phase 3). Set from the main thread, used on the GL thread. */
    @Volatile var capture: CaptureEngine? = null
        private set
    private var captureGlReady: CaptureEngine? = null
    private var config: Config? = null
    private val commands = ConcurrentLinkedQueue<Command>()

    // GL thread state
    private val background = CameraBackground()
    private var boundSession: Session? = null
    private var boundTexture = 0
    private var viewportWidth = 0
    private var viewportHeight = 0
    @Volatile private var geometryChanged = true
    @Volatile private var displayRotation = Surface.ROTATION_0
    private var anchor: Anchor? = null

    private sealed interface Command {
        data class Place(val x: Float, val y: Float) : Command
        data class Fit(val box: PlacedBox) : Command
        data class Torch(val on: Boolean) : Command
        data object ClearAnchor : Command
    }

    // ---- UI requests -------------------------------------------------------------------------

    /** Tap on the camera view at pixel ([x], [y]). Answers with BoxPlaced or PlaceMissed. */
    fun placeAt(x: Float, y: Float) { commands += Command.Place(x, y) }

    /** Re-run auto-size around the current box. Answers with Fitted. */
    fun fitAround(box: PlacedBox) { commands += Command.Fit(box) }

    fun setTorch(on: Boolean) { commands += Command.Torch(on) }

    fun clearAnchor() { commands += Command.ClearAnchor }

    /** Starts fusing into [engine] (the box must be anchored). */
    fun startCapture(engine: CaptureEngine) {
        showPlanes = false
        capture = engine
    }

    /** Stops drawing and fusing; the caller closes the engine. */
    fun stopCapture() {
        capture = null
    }

    /** For [CaptureEngine] construction: emits [ArEvent.DepthFused] from the fusion thread. */
    fun depthFusedSink(): (Float) -> Unit = { _events.tryEmit(ArEvent.DepthFused(it)) }

    // ---- Lifecycle (main thread) -------------------------------------------------------------

    fun resume(view: GLSurfaceView) {
        if (_status.value is ArStatus.Failed) return
        val s = session ?: createSession() ?: return
        displayRotation = ContextCompat.getDisplayOrDefault(appContext).rotation
        geometryChanged = true
        try {
            s.resume()
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "Camera not available", e)
            fail(ArStatus.Reason.CAMERA_UNAVAILABLE)
            return
        } catch (e: SecurityException) {
            Log.w(TAG, "Camera permission missing", e)
            fail(ArStatus.Reason.CAMERA_UNAVAILABLE)
            return
        }
        _status.value = ArStatus.Running
        view.onResume()
    }

    fun pause(view: GLSurfaceView) {
        view.onPause() // blocks until the GL thread stops drawing
        session?.pause()
    }

    /** After [pause]. Releases the camera and ARCore resources. */
    fun close() {
        session?.close()
        session = null
        config = null
    }

    private fun createSession(): Session? = try {
        Session(appContext).also { s ->
            val c = Config(s)
                .setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL)
                .setDepthMode(
                    if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED,
                )
                .setLightEstimationMode(Config.LightEstimationMode.AMBIENT_INTENSITY)
                .setFocusMode(Config.FocusMode.AUTO)
                .setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
                .setFlashMode(Config.FlashMode.OFF)
            s.configure(c)
            config = c
            session = s
        }
    } catch (e: Exception) {
        // UnavailableException subclasses (ARCore missing/outdated, device not compatible) or
        // anything else from the native layer: the device check normally prevents these.
        Log.w(TAG, "Couldn't create the AR session", e)
        fail(if (e is SecurityException) ArStatus.Reason.CAMERA_UNAVAILABLE else ArStatus.Reason.AR_UNAVAILABLE)
        null
    }

    private fun fail(reason: ArStatus.Reason) {
        _status.value = ArStatus.Failed(reason)
        session?.close()
        session = null
    }

    // ---- GL thread ---------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, eglConfig: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.create()
        captureGlReady = null // new GL context: the capture renderer must recreate its objects
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        geometryChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (boundSession !== s || boundTexture != background.textureId) {
            s.setCameraTextureName(background.textureId)
            boundSession = s
            boundTexture = background.textureId
        }
        if (geometryChanged && viewportWidth > 0) {
            s.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
            geometryChanged = false
        }
        val frame = try {
            s.update()
        } catch (_: SessionPausedException) {
            return
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "Camera lost", e)
            _status.value = ArStatus.Failed(ArStatus.Reason.CAMERA_UNAVAILABLE)
            return
        } catch (e: Exception) {
            Log.w(TAG, "Frame update failed", e)
            return
        }
        background.draw(frame)
        rememberPose(frame)
        runCommands(s, frame)
        gather(frame)
        logPoses(s, frame)
        val snap = snapshot(s, frame)
        drawCapture(frame, snap)
        _frames.value = snap
    }

    private fun runCommands(s: Session, frame: Frame) {
        while (true) {
            when (val c = commands.poll() ?: return) {
                is Command.Place -> place(s, frame, c.x, c.y)
                is Command.Fit -> startFit(frame, c.box)
                is Command.Torch -> setFlash(s, c.on)
                Command.ClearAnchor -> {
                    pending = null
                    anchor?.detach()
                    anchor = null
                }
            }
        }
    }

    private fun drawCapture(frame: Frame, snap: ArFrameSnapshot) {
        val c = capture ?: return
        if (captureGlReady !== c) {
            c.glCreate()
            captureGlReady = c
        }
        val a = snap.anchor ?: return
        if (frame.camera.trackingState == ArTrackingState.TRACKING) c.onFrame(frame, a, ::poseAt)
        c.draw(snap.view, snap.projection, a)
    }

    private fun setFlash(s: Session, on: Boolean) {
        val c = config ?: return
        try {
            val mode = if (on) Config.FlashMode.TORCH else Config.FlashMode.OFF
            if (c.flashMode == mode) return
            c.setFlashMode(mode)
            s.configure(c)
            Log.i(TAG, "torch -> $mode")
        } catch (e: Exception) {
            Log.w(TAG, "Torch change failed", e)
        }
    }

    /** Depth gathered over several frames for one fit (per-frame phone depth is too noisy). */
    private class PendingFit(val forPlace: Boolean, val seed: FitSeed) {
        val points = ArrayList<Vec3>()
        var frames = 0
        var lastDepthTimestamp = -1L
        var startTimestamp = 0L
    }

    private var pending: PendingFit? = null

    private fun place(s: Session, frame: Frame, x: Float, y: Float) {
        if (frame.camera.trackingState != ArTrackingState.TRACKING) {
            _events.tryEmit(ArEvent.PlaceMissed)
            return
        }
        // First hit on the table, on a depth point (the object itself) or on a feature point.
        val hit = frame.hitTest(x, y).firstNotNullOfOrNull { h ->
            when (val t = h.trackable) {
                is Plane -> h.hitPose.takeIf { t.isTable() && t.isPoseInPolygon(h.hitPose) }
                is DepthPoint, is Point -> h.hitPose
                else -> null
            }
        }
        val plane = hit?.let { tableUnder(s, it) }
        if (hit == null || plane == null) {
            _events.tryEmit(ArEvent.PlaceMissed)
            return
        }

        // Anchor on the table straight below the hit; the box frame takes the plane's orientation.
        val tableY = plane.centerPose.ty()
        val anchorPose = Pose(floatArrayOf(hit.tx(), tableY, hit.tz()), plane.centerPose.rotationQuaternion)
        anchor?.detach()
        val a = plane.createAnchor(anchorPose)
        anchor = a

        // Seed with the whole tap ray: on glass or dark objects the hit lands on the table behind them.
        val planeFrame = PlaneFrame(a.pose.matrix())
        val origin = planeFrame.fromWorld(frame.camera.pose.translation)
        val target = planeFrame.fromWorld(hit.translation)
        pending = PendingFit(forPlace = true, seed = FitSeed.Ray(origin, target - origin))
        _events.tryEmit(ArEvent.Measuring)
    }

    private fun startFit(frame: Frame, box: PlacedBox) {
        if (anchor?.trackingState != ArTrackingState.TRACKING || frame.camera.trackingState != ArTrackingState.TRACKING) {
            _events.tryEmit(ArEvent.Fitted(null))
            return
        }
        pending = PendingFit(forPlace = false, seed = FitSeed.Point(box.centerX, box.centerY))
    }

    /** Adds this frame's depth to the pending fit; runs the fit once enough frames are in. */
    private fun gather(frame: Frame) {
        val p = pending ?: return
        val a = anchor?.takeIf { it.trackingState == ArTrackingState.TRACKING }
        if (p.startTimestamp == 0L) p.startTimestamp = frame.timestamp
        if (a != null && frame.camera.trackingState == ArTrackingState.TRACKING) {
            val ts = addDepthPoints(frame, PlaneFrame(a.pose.matrix()), p.points, p.lastDepthTimestamp)
            if (ts != null) {
                p.lastDepthTimestamp = ts
                p.frames++
            }
        }
        val timedOut = frame.timestamp - p.startTimestamp > MAX_GATHER_NS
        if (p.frames < tuning.placement.autoSizeDepthFrames && !timedOut && a != null) return

        pending = null
        val box = if (a == null) null else fitAndLog(if (p.forPlace) "place" else "fit", p.points, p.seed, p.frames)
        _events.tryEmit(
            if (p.forPlace) ArEvent.BoxPlaced(box ?: PlacedBox.default(0.0, 0.0, tuning.placement), box != null)
            else ArEvent.Fitted(box),
        )
    }

    private var fitCount = 0

    /** Recent camera poses by frame timestamp: depth maps lag the camera and need the pose they were taken at. */
    private val poseHistory = ArrayDeque<Pair<Long, Pose>>()

    private fun rememberPose(frame: Frame) {
        if (frame.camera.trackingState != ArTrackingState.TRACKING) return
        poseHistory.addLast(frame.timestamp to frame.camera.pose)
        while (poseHistory.size > POSE_HISTORY) poseHistory.removeFirst()
    }

    private fun poseAt(timestamp: Long): Pose? =
        poseHistory.minByOrNull { kotlin.math.abs(it.first - timestamp) }
            ?.takeIf { kotlin.math.abs(it.first - timestamp) <= MAX_POSE_GAP_NS }?.second

    /** Runs auto-fit and logs why it did or didn't work; debug builds also dump the points for tuning. */
    private fun fitAndLog(kind: String, points: List<Vec3>, seed: FitSeed, frames: Int): PlacedBox? {
        val r = BoxAutoFit.fitWithReport(points, seed, tuning.placement)
        Log.i(
            TAG,
            "$kind #$fitCount: frames=$frames points=${r.inputPoints} table=${"%.1f".format(r.tableOffsetMm)}mm " +
                "cells=${r.cells} object=${r.objectCells} cluster=${r.clusterCells} seed=$seed " +
                "box=${r.box?.let { "%.0fx%.0fx%.0f @(%.0f,%.0f) yaw %.0f".format(it.size.widthMm, it.size.depthMm, it.size.heightMm, it.centerX, it.centerY, it.yawDeg) }}",
        )
        if (debuggable) {
            runCatching {
                appContext.cacheDir.resolve("fit-$fitCount.csv").bufferedWriter().use { w ->
                    w.write("# $kind $seed\n")
                    points.forEach { w.write("${"%.1f".format(it.x)},${"%.1f".format(it.y)},${"%.1f".format(it.z)}\n") }
                }
            }
        }
        fitCount++
        return r.box
    }

    private val debuggable = (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private var lastPoseLog = 0L

    /** Once a second in debug builds: camera, anchor and plane heights, to diagnose drift. */
    private fun logPoses(s: Session, frame: Frame) {
        if (!debuggable || frame.timestamp - lastPoseLog < 1_000_000_000L) return
        lastPoseLog = frame.timestamp
        val cam = frame.camera
        val a = anchor
        val planes = s.getAllTrackables(Plane::class.java).filter { it.isTable() }
            .joinToString(",") { "%.3f".format(it.centerPose.ty()) }
        Log.d(
            TAG,
            "pose cam=${cam.trackingState}/${cam.trackingFailureReason} " +
                "camPos=${cam.pose.translation.joinToString(",") { "%.3f".format(it) }} " +
                "anchor=${a?.trackingState} ${a?.pose?.translation?.joinToString(",") { "%.3f".format(it) }} " +
                "planesY=[$planes] flash=${config?.flashMode}",
        )
    }

    /** The highest tracked table plane at or below [seed] whose outline contains it (or the nearest below). */
    private fun tableUnder(s: Session, seed: Pose): Plane? {
        val below = s.getAllTrackables(Plane::class.java).filter { p ->
            p.isTable() && p.centerPose.ty() <= seed.ty() + 0.03f && seed.ty() - p.centerPose.ty() <= 2.5f
        }
        val containing = below.filter { p -> p.isPoseInPolygon(Pose.makeTranslation(seed.tx(), p.centerPose.ty(), seed.tz())) }
        return (containing.ifEmpty { below }).maxByOrNull { it.centerPose.ty() }
    }

    /**
     * Depth image → points in the box's plane frame (mm), appended to [out]. Returns the depth
     * timestamp, or null if there was no new depth map. ARCore depth is 16-bit millimetres,
     * aligned with the camera texture; unprojected with the texture intrinsics scaled to the depth
     * resolution, in the physical camera's frame (+X right, +Y up, −Z forward).
     */
    private fun addDepthPoints(frame: Frame, planeFrame: PlaneFrame, out: MutableList<Vec3>, lastTimestamp: Long): Long? {
        val image = try {
            frame.acquireDepthImage16Bits()
        } catch (_: NotYetAvailableException) {
            return null
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Depth not enabled", e)
            return null
        }
        image.use { img ->
            if (img.timestamp == lastTimestamp) return null // same depth map as last frame
            // The camera pose at the moment this depth map was taken, not the current one.
            val cameraPose = poseAt(img.timestamp) ?: run {
                Log.d(TAG, "no pose for depth ${(frame.timestamp - img.timestamp) / 1_000_000} ms old")
                return null
            }
            val intrinsics = frame.camera.textureIntrinsics
            val dims = intrinsics.imageDimensions
            val sx = img.width.toFloat() / dims[0]
            val sy = img.height.toFloat() / dims[1]
            val fx = intrinsics.focalLength[0] * sx
            val fy = intrinsics.focalLength[1] * sy
            val cx = intrinsics.principalPoint[0] * sx
            val cy = intrinsics.principalPoint[1] * sy
            val plane = img.planes[0]
            val buffer = plane.buffer.order(ByteOrder.nativeOrder())
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val maxDepth = tuning.placement.maxDepthMm.toInt()
            val step = if (img.width > 200) 2 else 1
            val cameraPoint = FloatArray(3)
            val world = FloatArray(3)
            for (v in 0 until img.height step step) {
                for (u in 0 until img.width step step) {
                    val mm = buffer.getShort(v * rowStride + u * pixelStride).toInt() and 0xFFFF
                    if (mm == 0 || mm > maxDepth) continue
                    val z = mm / 1000f
                    cameraPoint[0] = (u - cx) / fx * z
                    cameraPoint[1] = (cy - v) / fy * z
                    cameraPoint[2] = -z
                    cameraPose.transformPoint(cameraPoint, 0, world, 0)
                    out += planeFrame.fromWorld(world)
                }
            }
            return img.timestamp
        }
    }

    private fun snapshot(s: Session, frame: Frame): ArFrameSnapshot {
        val camera = frame.camera
        val view = FloatArray(16).also { camera.getViewMatrix(it, 0) }
        val projection = FloatArray(16).also { camera.getProjectionMatrix(it, 0, NEAR_M, FAR_M) }
        val tracking = camera.trackingState.toShared()
        val tables = if (tracking == TrackingState.TRACKING) {
            s.getAllTrackables(Plane::class.java).filter { it.isTable() }
        } else {
            emptyList()
        }
        return ArFrameSnapshot(
            view = view,
            projection = projection,
            tracking = tracking,
            failure = if (tracking == TrackingState.TRACKING) TrackingFailure.NONE else camera.trackingFailureReason.toShared(),
            hasSurface = tables.isNotEmpty(),
            planes = if (showPlanes) planeOverlays(tables, camera) else emptyList(),
            reticle = if (showPlanes && tables.isNotEmpty()) reticle(frame) else null,
            anchor = anchor?.takeIf { it.trackingState == ArTrackingState.TRACKING }?.pose?.matrix(),
            timestampNs = frame.timestamp,
            lightIntensity = frame.lightEstimate.takeIf { it.state == com.google.ar.core.LightEstimate.State.VALID }?.pixelIntensity,
        )
    }

    private fun reticle(frame: Frame): FloatArray? {
        if (viewportWidth == 0) return null
        val hit = frame.hitTest(viewportWidth / 2f, viewportHeight / 2f).firstOrNull { h ->
            val t = h.trackable
            t is Plane && t.isTable() && t.isPoseInPolygon(h.hitPose)
        } ?: return null
        return hit.hitPose.translation
    }

    /** Outline + a world-aligned dot grid (stable as the plane grows) for each table plane. */
    private fun planeOverlays(tables: List<Plane>, camera: Camera): List<PlaneOverlay> {
        val cam = camera.pose
        var budget = MAX_DOTS
        return tables.map { plane ->
            val center = plane.centerPose
            val poly = plane.polygon
            val local = FloatArray(3)
            val world = FloatArray(3)
            val outline = FloatArray(poly.limit() / 2 * 3)
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var minZ = Float.MAX_VALUE
            var maxZ = -Float.MAX_VALUE
            for (i in 0 until poly.limit() / 2) {
                local[0] = poly.get(i * 2)
                local[1] = 0f
                local[2] = poly.get(i * 2 + 1)
                center.transformPoint(local, 0, world, 0)
                world.copyInto(outline, i * 3)
                minX = minOf(minX, world[0]); maxX = maxOf(maxX, world[0])
                minZ = minOf(minZ, world[2]); maxZ = maxOf(maxZ, world[2])
            }
            val y = center.ty()
            val dots = ArrayList<Float>()
            var gx = ceil(minX / DOT_STEP_M)
            while (gx * DOT_STEP_M <= maxX && budget > 0) {
                var gz = ceil(minZ / DOT_STEP_M)
                while (gz * DOT_STEP_M <= maxZ && budget > 0) {
                    val px = gx * DOT_STEP_M
                    val pz = gz * DOT_STEP_M
                    val dx = px - cam.tx()
                    val dz = pz - cam.tz()
                    if (dx * dx + dz * dz < DOT_RANGE_M * DOT_RANGE_M && insideXZ(outline, px, pz)) {
                        dots += px; dots += y; dots += pz
                        budget--
                    }
                    gz += 1f
                }
                gx += 1f
            }
            PlaneOverlay(outline, dots.toFloatArray())
        }
    }

    companion object {
        private const val TAG = "FacetAr"
        const val NEAR_M = 0.05f
        const val FAR_M = 30f
        private const val DOT_STEP_M = 0.06f
        private const val DOT_RANGE_M = 3f
        private const val MAX_DOTS = 1500
        /** Give up gathering depth for a fit after this long (use what's in). */
        private const val MAX_GATHER_NS = 1_500_000_000L
        private const val POSE_HISTORY = 90
        /** A depth map whose pose we can't match within this is skipped. */
        private const val MAX_POSE_GAP_NS = 20_000_000L

        private fun Plane.isTable() =
            trackingState == ArTrackingState.TRACKING && subsumedBy == null && type == Plane.Type.HORIZONTAL_UPWARD_FACING

        private fun Pose.matrix() = FloatArray(16).also { toMatrix(it, 0) }

        private fun ArTrackingState.toShared() = when (this) {
            ArTrackingState.TRACKING -> TrackingState.TRACKING
            ArTrackingState.PAUSED -> TrackingState.LIMITED
            ArTrackingState.STOPPED -> TrackingState.NOT_TRACKING
        }

        private fun TrackingFailureReason.toShared() = when (this) {
            TrackingFailureReason.NONE -> TrackingFailure.NONE
            TrackingFailureReason.BAD_STATE -> TrackingFailure.BAD_STATE
            TrackingFailureReason.INSUFFICIENT_LIGHT -> TrackingFailure.INSUFFICIENT_LIGHT
            TrackingFailureReason.EXCESSIVE_MOTION -> TrackingFailure.EXCESSIVE_MOTION
            TrackingFailureReason.INSUFFICIENT_FEATURES -> TrackingFailure.INSUFFICIENT_FEATURES
            TrackingFailureReason.CAMERA_UNAVAILABLE -> TrackingFailure.CAMERA_UNAVAILABLE
        }

        /** Point-in-polygon on the XZ plane; [outline] is xyz triples. */
        private fun insideXZ(outline: FloatArray, x: Float, z: Float): Boolean {
            var inside = false
            val n = outline.size / 3
            var j = n - 1
            for (i in 0 until n) {
                val xi = outline[i * 3]; val zi = outline[i * 3 + 2]
                val xj = outline[j * 3]; val zj = outline[j * 3 + 2]
                if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) inside = !inside
                j = i
            }
            return inside
        }
    }
}
