package app.facet.android.ar

import android.opengl.Matrix
import androidx.compose.ui.geometry.Offset
import app.facet.geometry.Vec3
import app.facet.scan.TrackingState

/** Why ARCore isn't tracking (mirrors com.google.ar.core.TrackingFailureReason). */
enum class TrackingFailure { NONE, BAD_STATE, INSUFFICIENT_LIGHT, EXCESSIVE_MOTION, INSUFFICIENT_FEATURES, CAMERA_UNAVAILABLE }

/** A detected table plane, in world space (metres): outline and the dotted-grid points, xyz triples. */
class PlaneOverlay(val outline: FloatArray, val dots: FloatArray)

/**
 * One AR frame as the Compose overlay needs it. Produced on the GL thread, read on the main thread,
 * never mutated after publishing. Matrices are column-major (android.opengl.Matrix), world space is
 * ARCore's (metres, Y up).
 */
class ArFrameSnapshot(
    val view: FloatArray,
    val projection: FloatArray,
    val tracking: TrackingState,
    val failure: TrackingFailure,
    /** A horizontal table-like plane is being tracked. */
    val hasSurface: Boolean,
    /** Only filled while the placement screen asks for planes. */
    val planes: List<PlaneOverlay>,
    /** Where the screen centre hits a table plane (world xyz), for the reticle. */
    val reticle: FloatArray?,
    /** Model matrix of the box anchor; null when there's no box or the anchor isn't tracking. */
    val anchor: FloatArray?,
    val timestampNs: Long,
    /** ARCore ambient pixel intensity 0..1, null when not estimated. */
    val lightIntensity: Float?,
)

/**
 * The plane frame used by the shared box model (Z up, millimetres, origin at the box anchor)
 * ↔ ARCore world space (Y up, metres). Shared (x, y, z) = anchor-local (x, −z, y).
 */
class PlaneFrame(private val anchor: FloatArray) {
    private val inverse = FloatArray(16).also { Matrix.invertM(it, 0, anchor, 0) }
    private val tmpIn = FloatArray(4)
    private val tmpOut = FloatArray(4)

    fun toWorld(p: Vec3): FloatArray {
        val local = floatArrayOf((p.x / 1000).toFloat(), (p.z / 1000).toFloat(), (-p.y / 1000).toFloat(), 1f)
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, anchor, 0, local, 0)
        return out
    }

    fun fromWorld(x: Float, y: Float, z: Float): Vec3 {
        tmpIn[0] = x; tmpIn[1] = y; tmpIn[2] = z; tmpIn[3] = 1f
        Matrix.multiplyMV(tmpOut, 0, inverse, 0, tmpIn, 0)
        return Vec3(tmpOut[0] * 1000.0, -tmpOut[2] * 1000.0, tmpOut[1] * 1000.0)
    }

    fun fromWorld(p: FloatArray): Vec3 = fromWorld(p[0], p[1], p[2])
}

/**
 * Projects world points onto the overlay (same size as the camera view), with near-plane clipping
 * so lines and polygons that pass behind the camera don't flip across the screen.
 */
class Projector(snapshot: ArFrameSnapshot, private val width: Float, private val height: Float) {
    private val view = snapshot.view
    private val projection = snapshot.projection
    private val inverseViewProjection = FloatArray(16).also {
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, projection, 0, view, 0)
        Matrix.invertM(it, 0, vp, 0)
    }

    /** Camera position in world space. */
    val cameraPosition: FloatArray = FloatArray(4).also {
        val inv = FloatArray(16)
        Matrix.invertM(inv, 0, view, 0)
        Matrix.multiplyMV(it, 0, inv, 0, floatArrayOf(0f, 0f, 0f, 1f), 0)
    }

    private fun toView(x: Float, y: Float, z: Float): FloatArray {
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, view, 0, floatArrayOf(x, y, z, 1f), 0)
        return out
    }

    private fun viewToScreen(v: FloatArray): Offset {
        val c = FloatArray(4)
        Matrix.multiplyMV(c, 0, projection, 0, floatArrayOf(v[0], v[1], v[2], 1f), 0)
        return Offset((c[0] / c[3] + 1f) / 2f * width, (1f - c[1] / c[3]) / 2f * height)
    }

    fun project(x: Float, y: Float, z: Float): Offset? {
        val v = toView(x, y, z)
        return if (v[2] > CLIP_Z) null else viewToScreen(v)
    }

    fun project(p: FloatArray): Offset? = project(p[0], p[1], p[2])

    /** The visible part of segment a–b, or null if it's entirely behind the camera. */
    fun segment(a: FloatArray, b: FloatArray): Pair<Offset, Offset>? {
        var va = toView(a[0], a[1], a[2])
        var vb = toView(b[0], b[1], b[2])
        val aIn = va[2] <= CLIP_Z
        val bIn = vb[2] <= CLIP_Z
        if (!aIn && !bIn) return null
        if (!aIn) va = clipPoint(vb, va)
        if (!bIn) vb = clipPoint(va, vb)
        return viewToScreen(va) to viewToScreen(vb)
    }

    /** Polygon clipped to the near plane (Sutherland–Hodgman), as screen points. */
    fun polygon(points: List<FloatArray>): List<Offset> {
        val v = points.map { toView(it[0], it[1], it[2]) }
        val out = ArrayList<FloatArray>(v.size + 2)
        for (i in v.indices) {
            val cur = v[i]
            val prev = v[(i + v.size - 1) % v.size]
            val curIn = cur[2] <= CLIP_Z
            val prevIn = prev[2] <= CLIP_Z
            if (curIn) {
                if (!prevIn) out += clipPoint(cur, prev)
                out += cur
            } else if (prevIn) {
                out += clipPoint(prev, cur)
            }
        }
        return out.map(::viewToScreen)
    }

    /** Point on segment inside→outside where it crosses the clip plane (view space). */
    private fun clipPoint(inside: FloatArray, outside: FloatArray): FloatArray {
        val t = (CLIP_Z - inside[2]) / (outside[2] - inside[2])
        return floatArrayOf(
            inside[0] + (outside[0] - inside[0]) * t,
            inside[1] + (outside[1] - inside[1]) * t,
            CLIP_Z,
            1f,
        )
    }

    /** World-space points where the ray through [screen] meets the near and far planes. */
    fun ray(screen: Offset): Pair<FloatArray, FloatArray> {
        val nx = screen.x / width * 2f - 1f
        val ny = 1f - screen.y / height * 2f
        fun unproject(z: Float): FloatArray {
            val out = FloatArray(4)
            Matrix.multiplyMV(out, 0, inverseViewProjection, 0, floatArrayOf(nx, ny, z, 1f), 0)
            return floatArrayOf(out[0] / out[3], out[1] / out[3], out[2] / out[3])
        }
        return unproject(-1f) to unproject(1f)
    }

    /** Where the ray through [screen] meets the table (Z = 0 in [frame]), in plane-frame mm. */
    fun hitTable(screen: Offset, frame: PlaneFrame): Vec3? {
        val (near, far) = ray(screen)
        val a = frame.fromWorld(near)
        val b = frame.fromWorld(far)
        if (a.z <= 0 || b.z >= a.z) return null // camera below the table or looking up
        val t = a.z / (a.z - b.z)
        return a + (b - a) * t
    }

    private companion object {
        /** Slightly in front of the projection's near plane. */
        const val CLIP_Z = -(ArController.NEAR_M + 0.001f)
    }
}
