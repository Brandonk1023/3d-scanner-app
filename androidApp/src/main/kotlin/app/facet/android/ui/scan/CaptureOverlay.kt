package app.facet.android.ui.scan

import android.opengl.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.facet.android.ar.ArFrameSnapshot
import app.facet.android.ar.PlaneFrame
import app.facet.android.ar.Projector
import app.facet.android.ui.theme.ArColors
import app.facet.geometry.Vec3
import app.facet.scan.CoverageBand
import app.facet.scan.CoverageState
import app.facet.scan.PlacedBox
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Fraction (0..1) of the box's on-screen bounding rectangle that is inside the view, computed in
 * normalised device coordinates from the 8 projected corners. Corners behind the camera count as
 * out of view.
 */
internal fun boxInViewFraction(snapshot: ArFrameSnapshot, box: PlacedBox, frame: PlaneFrame): Float {
    val vp = FloatArray(16).also { Matrix.multiplyMM(it, 0, snapshot.projection, 0, snapshot.view, 0) }
    var minX = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    var inFront = 0
    val clip = FloatArray(4)
    for (corner in box.corners()) {
        val w = frame.toWorld(corner)
        Matrix.multiplyMV(clip, 0, vp, 0, floatArrayOf(w[0], w[1], w[2], 1f), 0)
        if (clip[3] <= 1e-4f) continue
        inFront++
        val x = clip[0] / clip[3]
        val y = clip[1] / clip[3]
        minX = minOf(minX, x); maxX = maxOf(maxX, x)
        minY = minOf(minY, y); maxY = maxOf(maxY, y)
    }
    if (inFront == 0) return 0f
    val area = (maxX - minX) * (maxY - minY)
    if (area <= 1e-6f) return 0f
    val visible = (minOf(maxX, 1f) - maxOf(minX, -1f)).coerceAtLeast(0f) * (minOf(maxY, 1f) - maxOf(minY, -1f)).coerceAtLeast(0f)
    return visible / area * inFront / 8f
}

/** Camera position in the plane frame (mm). */
internal fun cameraInPlane(projector: Projector, frame: PlaneFrame): Vec3 = frame.fromWorld(projector.cameraPosition)

/** Capture: the box as a thin dashed white outline (A-Capture.png). */
internal fun DrawScope.drawCaptureBox(box: PlacedBox, frame: PlaneFrame, projector: Projector) {
    val corners = box.corners().map(frame::toWorld)
    val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
    val width = 1.5.dp.toPx()
    val segments = BOX_EDGES.mapNotNull { (a, b) -> projector.segment(corners[a], corners[b]) }
    segments.forEach { (a, b) -> drawLine(ArColors.halo, a, b, width + 2 * ArColors.haloExtra.toPx(), StrokeCap.Round) }
    segments.forEach { (a, b) -> drawLine(Color.White, a, b, width, StrokeCap.Round, dash) }
}

/**
 * Table ring for the band the camera is in (docs/04 §2): captured sectors thick and solid,
 * missing ones thin and dashed, both over a dark halo; a white triangle marks the user's heading.
 */
internal fun DrawScope.drawCoverageRing(
    box: PlacedBox,
    frame: PlaneFrame,
    projector: Projector,
    coverage: CoverageState,
    band: CoverageBand,
    headingDeg: Double?,
    sectors: Int,
) {
    val radius = RING_SCALE * hypot(box.size.widthMm, box.size.depthMm) / 2 + RING_CLEARANCE_MM
    fun at(deg: Double): Offset? {
        val a = deg * PI / 180
        return projector.project(frame.toWorld(Vec3(box.centerX + radius * cos(a), box.centerY + radius * sin(a), 0.0)))
    }
    val span = 360.0 / sectors
    val captured = Stroke(ArColors.ringCapturedWidth.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Round)
    val capturedHalo = Stroke(ArColors.ringCapturedWidth.toPx() + 2 * 2.dp.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Round)
    val missing = Stroke(
        ArColors.ringMissingWidth.toPx(),
        cap = StrokeCap.Butt,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
    )
    val missingHalo = Stroke(10.dp.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Round)

    val paths = (0 until sectors).mapNotNull { s ->
        if (!coverage.counted[band.ordinal][s]) return@mapNotNull null
        val start = s * span + SECTOR_GAP_DEG
        val end = (s + 1) * span - SECTOR_GAP_DEG
        val pts = (0..SEGMENT_STEPS).map { at(start + (end - start) * it / SEGMENT_STEPS) ?: return@mapNotNull null }
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
        }
        path to coverage.captured[band.ordinal][s]
    }
    paths.forEach { (p, done) -> drawPath(p, ArColors.halo, style = if (done) capturedHalo else missingHalo) }
    paths.forEach { (p, done) ->
        if (done) drawPath(p, ArColors.guide, style = captured) else drawPath(p, ArColors.missing, style = missing)
    }

    // Heading marker: a triangle on the ring pointing at the box.
    val heading = headingDeg ?: return
    val tip = at(heading) ?: return
    val outer = run {
        val a = heading * PI / 180
        val r = radius + MARKER_LENGTH_MM
        projector.project(frame.toWorld(Vec3(box.centerX + r * cos(a), box.centerY + r * sin(a), 0.0)))
    } ?: return
    val dir = (tip - outer).let { it / it.getDistance().coerceAtLeast(1f) }
    val side = Offset(-dir.y, dir.x) * 7.dp.toPx()
    val base = tip - dir * 14.dp.toPx()
    val triangle = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(base.x + side.x, base.y + side.y)
        lineTo(base.x - side.x, base.y - side.y)
        close()
    }
    drawPath(triangle, ArColors.halo, style = Stroke(2 * ArColors.haloExtra.toPx(), join = StrokeJoin.Round))
    drawPath(triangle, Color.White)
}

private const val RING_SCALE = 1.2
private const val RING_CLEARANCE_MM = 25.0
private const val SECTOR_GAP_DEG = 1.5
private const val SEGMENT_STEPS = 6
private const val MARKER_LENGTH_MM = 40.0

private val BOX_EDGES = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
