package app.facet.geometry

import kotlin.math.sqrt

/** All lengths are millimetres. Inches exist only in the UI layer. */

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec3(x * s, y * s, z * s)
    fun length(): Double = sqrt(x * x + y * y + z * z)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

/** Unit quaternion (x, y, z, w). */
data class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {
    companion object {
        val IDENTITY = Quat(0.0, 0.0, 0.0, 1.0)
    }
}

/** Rigid transform in world space. [position] is in millimetres. */
data class Pose(val position: Vec3, val rotation: Quat) {
    companion object {
        val IDENTITY = Pose(Vec3.ZERO, Quat.IDENTITY)
    }
}

/** Bounding-box size W × D × H in millimetres. */
data class BoxSize(val widthMm: Double, val depthMm: Double, val heightMm: Double) {
    /** Box diagonal D, used by distance guidance and voxel sizing (docs/04 §2, §4). */
    val diagonalMm: Double get() = sqrt(widthMm * widthMm + depthMm * depthMm + heightMm * heightMm)
}
