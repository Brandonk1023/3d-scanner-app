package app.facet.android.ui.scan

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import app.facet.android.ar.ArFrameSnapshot
import app.facet.android.ar.PlaneFrame
import app.facet.android.ar.Projector
import app.facet.android.ui.theme.ArColors
import app.facet.geometry.Vec3
import app.facet.scan.BoxAxis
import app.facet.scan.PlacedBox
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The four Fit box handles (docs/04 §1): top = height, sides = width or depth, bottom = move. */
internal enum class HandleKind { TOP, SIDE_PLUS, SIDE_MINUS, MOVE }

/**
 * Handle placement for one frame. [sideAxis] is the box side that runs left–right on screen right
 * now (so the side handles always sit left and right); [frontSide] is the end of the other side
 * that faces the camera (where the move handle goes).
 */
internal data class HandleLayout(val sideAxis: BoxAxis, val frontSide: Int, val screen: Map<HandleKind, Offset>)

internal fun otherSide(axis: BoxAxis) = if (axis == BoxAxis.WIDTH) BoxAxis.DEPTH else BoxAxis.WIDTH

/** Plane-frame point a handle sits on. */
internal fun handlePoint(box: PlacedBox, kind: HandleKind, sideAxis: BoxAxis, frontSide: Int): Vec3 = when (kind) {
    HandleKind.TOP -> box.faceCenter(BoxAxis.HEIGHT, 1)
    HandleKind.SIDE_PLUS -> box.faceCenter(sideAxis, 1)
    HandleKind.SIDE_MINUS -> box.faceCenter(sideAxis, -1)
    HandleKind.MOVE -> box.faceCenter(otherSide(sideAxis), frontSide, z = 0.0)
}

/**
 * Works out where the handles go. While a handle is being dragged pass [lockedAxis] so the side
 * handles don't swap axes under the finger.
 */
internal fun handleLayout(box: PlacedBox, frame: PlaneFrame, projector: Projector, lockedAxis: BoxAxis?): HandleLayout? {
    val center = projector.project(frame.toWorld(box.center)) ?: return null
    val sideAxis = lockedAxis ?: run {
        // The axis whose on-screen direction is more horizontal faces left/right.
        fun horizontalness(axis: BoxAxis): Float {
            val p = projector.project(frame.toWorld(box.center + box.direction(axis) * 50.0)) ?: return 0f
            val d = p - center
            val len = hypot(d.x, d.y)
            return if (len < 1e-3f) 0f else abs(d.x) / len
        }
        if (horizontalness(BoxAxis.WIDTH) >= horizontalness(BoxAxis.DEPTH)) BoxAxis.WIDTH else BoxAxis.DEPTH
    }
    val camera = frame.fromWorld(projector.cameraPosition)
    val toCamera = camera - box.baseCenter
    val front = otherSide(sideAxis)
    val dir = box.direction(front)
    val frontSide = if (toCamera.x * dir.x + toCamera.y * dir.y >= 0) 1 else -1

    val screen = HandleKind.entries.mapNotNull { kind ->
        projector.project(frame.toWorld(handlePoint(box, kind, sideAxis, frontSide)))?.let { kind to it }
    }.toMap()
    return HandleLayout(sideAxis, frontSide, screen)
}

/** Find surface: dotted table planes with a faint lime tint and dashed edge, plus the reticle. */
internal fun DrawScope.drawSurfaces(snapshot: ArFrameSnapshot, projector: Projector) {
    val dotSize = 3.5.dp.toPx()
    val edge = Stroke(
        width = 1.5.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())),
    )
    for (plane in snapshot.planes) {
        val outline = (0 until plane.outline.size / 3).map { i -> plane.outline.copyOfRange(i * 3, i * 3 + 3) }
        val screen = projector.polygon(outline)
        if (screen.size >= 3) {
            val path = Path().apply {
                moveTo(screen[0].x, screen[0].y)
                for (i in 1 until screen.size) lineTo(screen[i].x, screen[i].y)
                close()
            }
            drawPath(path, ArColors.guide.copy(alpha = 0.12f))
            drawPath(path, ArColors.guide.copy(alpha = 0.9f), style = edge)
        }
        val dots = ArrayList<Offset>(plane.dots.size / 3)
        for (i in 0 until plane.dots.size / 3) {
            projector.project(plane.dots[i * 3], plane.dots[i * 3 + 1], plane.dots[i * 3 + 2])?.let(dots::add)
        }
        drawPoints(dots, PointMode.Points, Color.White.copy(alpha = 0.8f), strokeWidth = dotSize, cap = StrokeCap.Round)
    }

    val r = snapshot.reticle ?: return
    // A 5 cm ring lying on the table, so it reads as "on the surface" in perspective.
    val ring = (0 until 48).mapNotNull { i ->
        val a = 2 * PI * i / 48
        projector.project(r[0] + (RETICLE_RADIUS_M * cos(a)).toFloat(), r[1], r[2] + (RETICLE_RADIUS_M * sin(a)).toFloat())
    }
    if (ring.size == 48) {
        val path = Path().apply {
            moveTo(ring[0].x, ring[0].y)
            ring.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, ArColors.halo, style = Stroke(3.dp.toPx() + 2 * ArColors.haloExtra.toPx()))
        drawPath(path, Color.White, style = Stroke(3.dp.toPx()))
    }
    projector.project(r)?.let { c ->
        drawCircle(ArColors.halo, radius = 6.dp.toPx(), center = c)
        drawCircle(Color.White, radius = 4.dp.toPx(), center = c)
    }
}

/** Fit box: tinted faces toward the camera, then every edge in accent over a dark halo. */
internal fun DrawScope.drawBox(box: PlacedBox, frame: PlaneFrame, projector: Projector) {
    val local = box.corners()
    val corners = local.map(frame::toWorld)
    val camera = frame.fromWorld(projector.cameraPosition)
    val boxCenter = box.center

    for (face in FACES) {
        val faceCenter = face.map { local[it] }.reduce { a, b -> a + b } * 0.25
        val outward = faceCenter - boxCenter
        val toCamera = camera - faceCenter
        if (outward.x * toCamera.x + outward.y * toCamera.y + outward.z * toCamera.z <= 0) continue
        val pts = projector.polygon(face.map { corners[it] })
        if (pts.size < 3) continue
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, Color.Black.copy(alpha = 0.16f))
        drawPath(path, ArColors.guide.copy(alpha = 0.10f))
    }

    val segments = EDGES.mapNotNull { (a, b) -> projector.segment(corners[a], corners[b]) }
    val width = 2.5.dp.toPx()
    val halo = width + 2 * ArColors.haloExtra.toPx()
    segments.forEach { (a, b) -> drawLine(ArColors.halo, a, b, halo, StrokeCap.Round) }
    segments.forEach { (a, b) -> drawLine(ArColors.guide, a, b, width, StrokeCap.Round) }
}

private const val RETICLE_RADIUS_M = 0.05

/** Corner indices from [PlacedBox.corners]: base 0–3, top 4–7. */
private val FACES = listOf(
    listOf(0, 1, 2, 3), listOf(4, 5, 6, 7),
    listOf(0, 1, 5, 4), listOf(1, 2, 6, 5), listOf(2, 3, 7, 6), listOf(3, 0, 4, 7),
)
private val EDGES = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
