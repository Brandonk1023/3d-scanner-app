package app.facet.scan

import app.facet.geometry.BoxSize
import app.facet.mesh.DepthFrame
import app.facet.mesh.Mesh
import app.facet.mesh.MeshEngine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Debug builds: a depth-only scan without a camera, so the emulator can exercise the real native
 * fusion and the final-mesh cleanup. A cylinder (70 mm across, 70 mm tall) stands on a table that
 * sits [TABLE_Z] mm above the box base (a slightly-off placed plane), next to an 8 mm crumb that
 * cleanup should drop. 36 noisy depth frames from above the table, like a real walk-around.
 *
 * Returns the raw mesh in the box frame, table included (cleanup's job), or null if fusion failed.
 */
object SimulatedScan {
    val box = BoxSize(120.0, 120.0, 90.0)
    const val TABLE_Z = 3.0
    const val RADIUS = 35.0
    const val HEIGHT = 70.0
    private const val CYL_X = -5.0
    private const val CRUMB_X = 45.0
    private const val CRUMB_Y = 40.0
    private const val CRUMB = 8.0

    private const val W = 160
    private const val H = 120
    private const val F = 140f
    private const val NOISE_MM = 2.0

    fun run(engine: MeshEngine, tuning: ScanTuning = ScanTuning.Default): Mesh? {
        val voxel = tuning.liveVoxelMm(box)
        val volume = engine.createVolume(box, voxel, tuning.truncationMm(voxel)) ?: return null
        try {
            val random = Random(7)
            for (elevation in doubleArrayOf(20.0, 45.0, 70.0)) {
                for (az in 0 until 360 step 30) {
                    val e = elevation * PI / 180
                    val a = az * PI / 180
                    val target = V(0.0, 0.0, TABLE_Z + HEIGHT / 2)
                    val eye = target + V(cos(e) * cos(a), cos(e) * sin(a), sin(e)) * 300.0
                    val (frame, cameraToBox) = render(eye, target, random)
                    volume.integrate(frame, cameraToBox, 0f)
                }
            }
            return volume.extract(1, tuning.reconstruction.meshMinWeight).takeIf { it.triangleCount > 0 }
        } finally {
            volume.close()
        }
    }

    private data class V(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
        operator fun times(s: Double) = V(x * s, y * s, z * s)
        fun cross(o: V) = V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        fun norm(): V = this * (1 / sqrt(x * x + y * y + z * z))
    }

    /** One depth frame (mm along the optical axis) and its camera → box matrix. */
    private fun render(eye: V, target: V, random: Random): Pair<DepthFrame, FloatArray> {
        val back = (eye - target).norm()
        val right = V(0.0, 0.0, 1.0).cross(back).norm()
        val up = back.cross(right)
        val m = floatArrayOf(
            right.x.toFloat(), right.y.toFloat(), right.z.toFloat(), 0f,
            up.x.toFloat(), up.y.toFloat(), up.z.toFloat(), 0f,
            back.x.toFloat(), back.y.toFloat(), back.z.toFloat(), 0f,
            eye.x.toFloat(), eye.y.toFloat(), eye.z.toFloat(), 1f,
        )
        val depth = ShortArray(W * H)
        val cx = W / 2.0
        val cy = H / 2.0
        for (v in 0 until H) {
            for (u in 0 until W) {
                val d = right * ((u + 0.5 - cx) / F) + up * (-(v + 0.5 - cy) / F) - back
                val t = hit(eye, d) ?: continue
                depth[v * W + u] = (t + random.nextDouble(-NOISE_MM, NOISE_MM)).roundToInt().toShort()
            }
        }
        return DepthFrame(W, H, depth, null, F, F, cx.toFloat(), cy.toFloat()) to m
    }

    /** Nearest hit along eye + t·d (t = depth, since d has forward component 1). */
    private fun hit(eye: V, d: V): Double? {
        var best = Double.MAX_VALUE
        fun plane(z: Double, inside: (Double, Double) -> Boolean) {
            if (d.z >= 0) return
            val t = (z - eye.z) / d.z
            if (t > 0 && t < best && inside(eye.x + d.x * t, eye.y + d.y * t)) best = t
        }
        plane(TABLE_Z) { x, y -> abs(x) < 400 && abs(y) < 400 }
        // Cylinder side wall and top.
        val ox = eye.x - CYL_X
        val a = d.x * d.x + d.y * d.y
        val b = 2 * (ox * d.x + eye.y * d.y)
        val c = ox * ox + eye.y * eye.y - RADIUS * RADIUS
        val disc = b * b - 4 * a * c
        if (a > 0 && disc >= 0) {
            val t = (-b - sqrt(disc)) / (2 * a)
            val z = eye.z + d.z * t
            if (t > 0 && t < best && z >= TABLE_Z && z <= TABLE_Z + HEIGHT) best = t
        }
        plane(TABLE_Z + HEIGHT) { x, y -> (x - CYL_X) * (x - CYL_X) + y * y <= RADIUS * RADIUS }
        // The crumb's top (its sides are too small to matter).
        plane(TABLE_Z + CRUMB) { x, y -> abs(x - CRUMB_X) <= CRUMB / 2 && abs(y - CRUMB_Y) <= CRUMB / 2 }
        return best.takeIf { it < Double.MAX_VALUE }
    }
}
