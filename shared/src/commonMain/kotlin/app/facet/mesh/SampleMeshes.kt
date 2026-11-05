package app.facet.mesh

import app.facet.geometry.BoxSize
import app.facet.geometry.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Axis-aligned bounds of a mesh, millimetres. */
data class Bounds(val min: Vec3, val max: Vec3) {
    val size: BoxSize get() = BoxSize(max.x - min.x, max.y - min.y, max.z - min.z)
    val center: Vec3 get() = (min + max) * 0.5
}

fun Mesh.bounds(): Bounds {
    require(vertexCount > 0) { "empty mesh" }
    var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
    for (i in 0 until vertexCount) {
        val x = positions[3 * i].toDouble(); val y = positions[3 * i + 1].toDouble(); val z = positions[3 * i + 2].toDouble()
        if (x < minX) minX = x; if (x > maxX) maxX = x
        if (y < minY) minY = y; if (y > maxY) maxY = y
        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
    }
    return Bounds(Vec3(minX, minY, minZ), Vec3(maxX, maxY, maxZ))
}

/**
 * Bundled sample meshes, generated in code: used by the Phase 1 fake gallery and the viewer
 * until real scans exist. Every mesh follows the [Mesh] convention (mm, Z-up, on Z = 0, centred on X/Y).
 */
enum class SampleShape { MUG, BRACKET, GEAR, CUBE, KNOB }

object SampleMeshes {
    fun create(shape: SampleShape): Mesh = when (shape) {
        SampleShape.MUG -> mug()
        SampleShape.BRACKET -> bracket()
        SampleShape.GEAR -> gear()
        SampleShape.CUBE -> MeshBuilder().apply { box(-10.0, -10.0, 0.0, 10.0, 10.0, 20.0, divisions = 4) }.build()
        SampleShape.KNOB -> knob()
    }

    private fun mug(): Mesh = MeshBuilder().apply {
        // Body: lathe of the wall cross-section (outside down, rim, inside up to the floor).
        val profile = buildList {
            add(0.0 to 0.0)
            add(46.0 to 0.0)
            for (i in 1..14) add(46.0 to 98.0 * i / 14)
            add(42.0 to 98.0)
            for (i in 1..13) add(42.0 to 98.0 - 92.0 * i / 13)
            add(0.0 to 6.0)
        }
        lathe(profile, segments = 96)
        // Handle: part of a torus in the XZ plane, ends buried in the wall.
        torusArc(cx = 40.0, cz = 50.0, major = 24.0, minor = 5.5, fromDeg = -75.0, toDeg = 75.0, arcSegments = 32, tubeSegments = 16)
    }.build()

    private fun bracket(): Mesh = MeshBuilder().apply {
        box(0.0, 0.0, 0.0, 42.0, 18.5, 4.0, divisions = 3)
        box(0.0, 0.0, 4.0, 4.0, 18.5, 30.2, divisions = 3)
    }.build()

    private fun knob(): Mesh = MeshBuilder().apply {
        lathe(listOf(0.0 to 0.0, 15.0 to 0.0, 15.0 to 7.0, 15.0 to 14.0, 12.0 to 18.0, 0.0 to 18.0), segments = 64)
    }.build()

    /** 32-tooth spur gear, 64 mm outside diameter, 12 mm thick, 8 mm bore. */
    private fun gear(): Mesh {
        val teeth = 32
        val outer = 32.0
        val root = 28.5
        val bore = 4.0
        val thickness = 12.0
        val outline = ArrayList<Pair<Double, Double>>(teeth * 4)
        val pitch = 2 * PI / teeth
        for (t in 0 until teeth) {
            val a = t * pitch
            // root → flank → tip → flank, as fractions of one tooth pitch
            listOf(0.0 to root, 0.2 to outer, 0.45 to outer, 0.65 to root).forEach { (f, r) ->
                outline += r * cos(a + f * pitch) to r * sin(a + f * pitch)
            }
        }
        val b = MeshBuilder()
        val n = outline.size
        fun ring(points: List<Pair<Double, Double>>, z: Double) = points.map { (x, y) -> b.vertex(x, y, z) }
        val borePts = outline.map { (x, y) -> val k = bore / kotlin.math.hypot(x, y); x * k to y * k }
        val oBot = ring(outline, 0.0); val oTop = ring(outline, thickness)
        val iBot = ring(borePts, 0.0); val iTop = ring(borePts, thickness)
        for (i in 0 until n) {
            val j = (i + 1) % n
            b.quad(oBot[i], oBot[j], oTop[j], oTop[i]) // outer wall
            b.quad(iBot[j], iBot[i], iTop[i], iTop[j]) // bore wall
            b.quad(oTop[i], oTop[j], iTop[j], iTop[i]) // top face
            b.quad(oBot[j], oBot[i], iBot[i], iBot[j]) // bottom face
        }
        return b.build()
    }
}

/** Small indexed-mesh builder. [build] re-centres on X/Y and drops the mesh onto Z = 0. */
internal class MeshBuilder {
    private val pos = ArrayList<Float>()
    private val idx = ArrayList<Int>()

    fun vertex(x: Double, y: Double, z: Double): Int {
        pos += x.toFloat(); pos += y.toFloat(); pos += z.toFloat()
        return pos.size / 3 - 1
    }

    fun tri(a: Int, b: Int, c: Int) { idx += a; idx += b; idx += c }

    fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }

    /** Surface of revolution around Z. [profile] is (radius, z); radius 0 closes with a fan. */
    fun lathe(profile: List<Pair<Double, Double>>, segments: Int) {
        val rings = profile.map { (r, z) ->
            if (r == 0.0) listOf(vertex(0.0, 0.0, z))
            else List(segments) { s -> val a = 2 * PI * s / segments; vertex(r * cos(a), r * sin(a), z) }
        }
        for (k in 0 until rings.size - 1) {
            val p = rings[k]; val q = rings[k + 1]
            for (s in 0 until segments) {
                val t = (s + 1) % segments
                when {
                    p.size == 1 && q.size == 1 -> Unit
                    p.size == 1 -> tri(p[0], q[t], q[s])
                    q.size == 1 -> tri(p[s], p[t], q[0])
                    else -> quad(p[s], p[t], q[t], q[s])
                }
            }
        }
    }

    /** Part of a torus lying in the XZ plane, centred at (cx, 0, cz). */
    fun torusArc(cx: Double, cz: Double, major: Double, minor: Double, fromDeg: Double, toDeg: Double, arcSegments: Int, tubeSegments: Int) {
        val rings = List(arcSegments + 1) { i ->
            val th = (fromDeg + (toDeg - fromDeg) * i / arcSegments) * PI / 180
            List(tubeSegments) { j ->
                val ph = 2 * PI * j / tubeSegments
                val r = major + minor * cos(ph)
                vertex(cx + r * cos(th), minor * sin(ph), cz + r * sin(th))
            }
        }
        for (i in 0 until arcSegments) for (j in 0 until tubeSegments) {
            val k = (j + 1) % tubeSegments
            quad(rings[i][j], rings[i + 1][j], rings[i + 1][k], rings[i][k])
        }
    }

    /** Axis-aligned box with each face split into [divisions]² quads (nicer wireframe). */
    fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, divisions: Int = 1) {
        val o = listOf(Vec3(x0, y0, z0), Vec3(x1, y1, z1))
        fun face(origin: Vec3, u: Vec3, v: Vec3) {
            val grid = List(divisions + 1) { i -> List(divisions + 1) { j ->
                val p = origin + u * (i.toDouble() / divisions) + v * (j.toDouble() / divisions)
                vertex(p.x, p.y, p.z)
            } }
            for (i in 0 until divisions) for (j in 0 until divisions)
                quad(grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1])
        }
        val (a, b) = o
        val dx = Vec3(b.x - a.x, 0.0, 0.0); val dy = Vec3(0.0, b.y - a.y, 0.0); val dz = Vec3(0.0, 0.0, b.z - a.z)
        face(a, dy, dx)                         // bottom
        face(Vec3(a.x, a.y, b.z), dx, dy)       // top
        face(a, dx, dz)                         // front
        face(Vec3(a.x, b.y, a.z), dz, dx)       // back
        face(a, dz, dy)                         // left
        face(Vec3(b.x, a.y, a.z), dy, dz)       // right
    }

    fun build(): Mesh {
        val p = pos.toFloatArray()
        val raw = Mesh(p, idx.toIntArray())
        val c = raw.bounds().center
        val minZ = raw.bounds().min.z
        for (i in 0 until raw.vertexCount) {
            p[3 * i] -= c.x.toFloat()
            p[3 * i + 1] -= c.y.toFloat()
            p[3 * i + 2] -= minZ.toFloat()
        }
        return Mesh(p, idx.toIntArray())
    }
}

/** The output convention (docs/04 §4): resting on Z = 0 and centred on X/Y. Empty meshes pass through. */
fun Mesh.restingCentred(): Mesh {
    if (vertexCount == 0) return this
    val b = bounds()
    val dx = b.center.x.toFloat()
    val dy = b.center.y.toFloat()
    val dz = b.min.z.toFloat()
    val p = positions.copyOf()
    for (i in 0 until vertexCount) {
        p[3 * i] -= dx
        p[3 * i + 1] -= dy
        p[3 * i + 2] -= dz
    }
    return Mesh(p, indices)
}
