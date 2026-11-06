package app.facet.mesh

import app.facet.geometry.Vec3
import kotlin.math.abs

/**
 * Uniform scale about the origin. Meshes rest on Z = 0 and are centred on X/Y, so the result
 * still does (docs/04 §5: applying scale is a uniform scale on the mesh).
 */
fun Mesh.scaled(factor: Double): Mesh {
    require(factor > 0) { "scale factor must be positive" }
    val f = factor.toFloat()
    return Mesh(FloatArray(positions.size) { positions[it] * f }, indices)
}

/**
 * The nearest point where the ray [origin] + t·[direction] (t > 0) hits the mesh, from either side,
 * or null. Brute force (Möller–Trumbore): fine for one tap or drag event on a few hundred thousand
 * triangles.
 */
fun Mesh.raycast(origin: Vec3, direction: Vec3): Vec3? {
    val p = positions
    val idx = indices
    val ox = origin.x; val oy = origin.y; val oz = origin.z
    val dx = direction.x; val dy = direction.y; val dz = direction.z
    var best = Double.MAX_VALUE
    for (t in 0 until triangleCount) {
        val a = idx[3 * t] * 3; val b = idx[3 * t + 1] * 3; val c = idx[3 * t + 2] * 3
        val ax = p[a].toDouble(); val ay = p[a + 1].toDouble(); val az = p[a + 2].toDouble()
        val e1x = p[b] - ax; val e1y = p[b + 1] - ay; val e1z = p[b + 2] - az
        val e2x = p[c] - ax; val e2y = p[c + 1] - ay; val e2z = p[c + 2] - az
        // h = d × e2
        val hx = dy * e2z - dz * e2y; val hy = dz * e2x - dx * e2z; val hz = dx * e2y - dy * e2x
        val det = e1x * hx + e1y * hy + e1z * hz
        if (abs(det) < 1e-12) continue
        val inv = 1.0 / det
        val sx = ox - ax; val sy = oy - ay; val sz = oz - az
        val u = (sx * hx + sy * hy + sz * hz) * inv
        if (u < 0.0 || u > 1.0) continue
        // q = s × e1
        val qx = sy * e1z - sz * e1y; val qy = sz * e1x - sx * e1z; val qz = sx * e1y - sy * e1x
        val v = (dx * qx + dy * qy + dz * qz) * inv
        if (v < 0.0 || u + v > 1.0) continue
        val dist = (e2x * qx + e2y * qy + e2z * qz) * inv
        if (dist > 1e-9 && dist < best) best = dist
    }
    return if (best < Double.MAX_VALUE) origin + direction * best else null
}
