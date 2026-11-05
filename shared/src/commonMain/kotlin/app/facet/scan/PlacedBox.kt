package app.facet.scan

import app.facet.geometry.BoxSize
import app.facet.geometry.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A box side that a handle drags: width (box X), depth (box Y) or height (Z). */
enum class BoxAxis { WIDTH, DEPTH, HEIGHT }

/**
 * The scan box resting on the table (docs/04 §1). Coordinates are in the **plane frame**: origin at
 * the placement anchor on the table, X/Y in the table plane, Z up, millimetres. The base sits on
 * Z = 0; [centerX]/[centerY] is the centre of the base and [yawDeg] turns the box about Z.
 */
data class PlacedBox(
    val centerX: Double,
    val centerY: Double,
    val yawDeg: Double,
    val size: BoxSize,
) {
    private val yawRad: Double get() = yawDeg * PI / 180.0

    /** Unit vector of the box's width side in the plane frame. */
    val axisX: Vec3 get() = Vec3(cos(yawRad), sin(yawRad), 0.0)

    /** Unit vector of the box's depth side in the plane frame. */
    val axisY: Vec3 get() = Vec3(-sin(yawRad), cos(yawRad), 0.0)

    val baseCenter: Vec3 get() = Vec3(centerX, centerY, 0.0)
    val center: Vec3 get() = Vec3(centerX, centerY, size.heightMm / 2)

    /** Box-local (u along width, v along depth, z up) → plane frame. */
    fun toPlane(u: Double, v: Double, z: Double): Vec3 = baseCenter + axisX * u + axisY * v + Vec3(0.0, 0.0, z)

    /** 8 corners: 0–3 base counter-clockwise from (−W/2, −D/2), 4–7 the same order on top. */
    fun corners(): List<Vec3> {
        val hw = size.widthMm / 2
        val hd = size.depthMm / 2
        val base = listOf(-hw to -hd, hw to -hd, hw to hd, -hw to hd)
        return base.map { (u, v) -> toPlane(u, v, 0.0) } + base.map { (u, v) -> toPlane(u, v, size.heightMm) }
    }

    fun sideLength(axis: BoxAxis): Double = when (axis) {
        BoxAxis.WIDTH -> size.widthMm
        BoxAxis.DEPTH -> size.depthMm
        BoxAxis.HEIGHT -> size.heightMm
    }

    /** Unit vector of [axis] in the plane frame. */
    fun direction(axis: BoxAxis): Vec3 = when (axis) {
        BoxAxis.WIDTH -> axisX
        BoxAxis.DEPTH -> axisY
        BoxAxis.HEIGHT -> Vec3(0.0, 0.0, 1.0)
    }

    /**
     * Centre of the face on the [side] (+1 / −1) end of [axis], at height [z] for the vertical faces.
     * The HEIGHT axis has only the top face (side ignored).
     */
    fun faceCenter(axis: BoxAxis, side: Int, z: Double = size.heightMm / 2): Vec3 = when (axis) {
        BoxAxis.HEIGHT -> Vec3(centerX, centerY, size.heightMm)
        else -> baseCenter + direction(axis) * (side * sideLength(axis) / 2) + Vec3(0.0, 0.0, z)
    }

    /**
     * Moves one face by [deltaMm] (positive = outward) and keeps the opposite face where it is:
     * the side handles of the Fit box screen. Height grows upwards from the table. Sides are clamped
     * to the placement limits.
     */
    fun resized(axis: BoxAxis, side: Int, deltaMm: Double, tuning: ScanTuning.Placement): PlacedBox {
        val step = deltaMm.coerceIn(-tuning.maxDragStepMm, tuning.maxDragStepMm)
        val old = sideLength(axis)
        val new = (old + step).coerceIn(tuning.boxMinSideMm, tuning.boxMaxSideMm)
        val applied = new - old
        if (applied == 0.0) return this
        return when (axis) {
            BoxAxis.HEIGHT -> copy(size = size.copy(heightMm = new))
            else -> {
                val shift = direction(axis) * (side.sign() * applied / 2)
                val newSize = if (axis == BoxAxis.WIDTH) size.copy(widthMm = new) else size.copy(depthMm = new)
                copy(centerX = centerX + shift.x, centerY = centerY + shift.y, size = newSize)
            }
        }
    }

    /** Slides the box along the table (the bottom handle). */
    fun moved(dxMm: Double, dyMm: Double): PlacedBox = copy(centerX = centerX + dxMm, centerY = centerY + dyMm)

    /** Turns the box about its vertical axis (two-finger rotate). Kept in (−180, 180]. */
    fun rotated(deltaDeg: Double): PlacedBox = copy(yawDeg = normalizeDeg(yawDeg + deltaDeg))

    /** The same box with every side clamped to the placement limits. */
    fun clamped(tuning: ScanTuning.Placement): PlacedBox = copy(
        size = BoxSize(
            size.widthMm.coerceIn(tuning.boxMinSideMm, tuning.boxMaxSideMm),
            size.depthMm.coerceIn(tuning.boxMinSideMm, tuning.boxMaxSideMm),
            size.heightMm.coerceIn(tuning.boxMinSideMm, tuning.boxMaxSideMm),
        ),
    )

    companion object {
        /** The box placed when auto-fit can't see the object: a cube centred on the tap. */
        fun default(x: Double, y: Double, tuning: ScanTuning.Placement): PlacedBox {
            val s = tuning.defaultBoxSideMm
            return PlacedBox(x, y, 0.0, BoxSize(s, s, s))
        }
    }
}

private fun Int.sign(): Int = if (this < 0) -1 else 1

internal fun normalizeDeg(deg: Double): Double {
    var d = deg % 360.0
    if (d <= -180.0) d += 360.0
    if (d > 180.0) d -= 360.0
    return if (abs(d) < 1e-12) 0.0 else d
}
