package app.facet.android.ui.mesh

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import app.facet.android.ui.theme.Facet
import app.facet.geometry.Vec3
import app.facet.mesh.Mesh
import app.facet.mesh.bounds
import app.facet.mesh.raycast
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Lightweight software mesh viewer for Phase 1 (sample meshes, a few thousand triangles):
 * perspective orbit camera, flat shading, painter's-algorithm depth sort, drawVertices in one call.
 * Phase 4 replaces it with Filament for real scans (hundreds of thousands of triangles).
 */

/** Orbit camera state: yaw/pitch in radians around the model, zoom factor, screen-space pan in px. */
@Stable
class OrbitCamera(private val initialYaw: Float = DEFAULT_YAW, private val initialPitch: Float = DEFAULT_PITCH) {
    var yaw by mutableFloatStateOf(initialYaw)
    var pitch by mutableFloatStateOf(initialPitch)
    var zoom by mutableFloatStateOf(1f)
    var panX by mutableFloatStateOf(0f)
    var panY by mutableFloatStateOf(0f)

    fun reset() {
        yaw = initialYaw; pitch = initialPitch; zoom = 1f; panX = 0f; panY = 0f
    }

    companion object {
        const val DEFAULT_YAW = (-35 * PI / 180).toFloat()
        const val DEFAULT_PITCH = (22 * PI / 180).toFloat()
        private const val MIN_PITCH = (-10 * PI / 180).toFloat()
        private const val MAX_PITCH = (85 * PI / 180).toFloat()

        internal fun clampPitch(p: Float) = p.coerceIn(MIN_PITCH, MAX_PITCH)
    }
}

@Composable
fun rememberOrbitCamera(): OrbitCamera = remember { OrbitCamera() }

/** Labels drawn on the model's width and height edges (already formatted, e.g. "118.6"). */
data class DimensionLabels(val width: String, val height: String)

/**
 * Two-point measurement on the mesh (Scale check). Taps on the mesh call [onTap]; dragging a
 * placed point calls [onDrag] with its index. Points are mesh-space mm.
 */
class MeasureOverlay(
    val points: List<Vec3>,
    /** Distance label at the middle of the line, already formatted. */
    val label: String?,
    val onTap: (Vec3) -> Unit,
    val onDrag: (index: Int, point: Vec3) -> Unit,
)

@Composable
fun MeshView(
    mesh: Mesh,
    modifier: Modifier = Modifier,
    camera: OrbitCamera = rememberOrbitCamera(),
    wireframe: Boolean = false,
    interactive: Boolean = false,
    showGround: Boolean = true,
    showAxes: Boolean = false,
    dimensions: DimensionLabels? = null,
    measure: MeasureOverlay? = null,
) {
    val renderer = remember(mesh) { MeshRenderer(mesh) }
    val measureState = rememberUpdatedState(measure)
    val textMeasurer = rememberTextMeasurer()
    val colors = Facet.colors
    val style = MeshStyle(
        shadeDark = Color(0xFF3A4046),
        shadeLight = Color(0xFFDDE1E5),
        wire = colors.text.copy(alpha = 0.75f),
        grid = colors.textMuted.copy(alpha = 0.28f),
        shadow = Color.Black.copy(alpha = if (colors.isDark) 0.45f else 0.12f),
        dimension = colors.accentInk,
        pointFill = Color.White,
        labelBg = colors.bg,
        labelStyle = Facet.type.dataMono.copy(color = colors.accentInk),
        axisLabelStyle = Facet.type.dataMono.copy(color = colors.textMuted),
    )

    val gestures = if (!interactive) Modifier else Modifier.pointerInput(camera, renderer) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // Measuring: a press on a placed point drags it; a tap elsewhere on the mesh places one.
            val dragIndex = measureState.value?.let { renderer.pointNear(it.points, down.position, POINT_GRAB_DP.dp.toPx()) } ?: -1
            var travel = 0f
            var multiTouch = false
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) multiTouch = true
                if (pressed == 1 && dragIndex >= 0 && !multiTouch) {
                    val change = event.changes.first { it.pressed }
                    renderer.hit(change.position)?.let { measureState.value?.onDrag(dragIndex, it) }
                } else if (pressed == 1) {
                    val delta = event.changes.first { it.pressed }.positionChange()
                    travel += delta.getDistance()
                    val perPx = (PI / size.width).toFloat()
                    camera.yaw -= delta.x * perPx
                    camera.pitch = OrbitCamera.clampPitch(camera.pitch + delta.y * perPx)
                } else if (pressed >= 2) {
                    camera.zoom = (camera.zoom * event.calculateZoom()).coerceIn(0.5f, 5f)
                    val pan = event.calculatePan()
                    camera.panX += pan.x
                    camera.panY += pan.y
                }
                event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
            } while (event.changes.any { it.pressed })
            val m = measureState.value
            if (m != null && dragIndex < 0 && !multiTouch && travel < viewConfiguration.touchSlop) {
                renderer.hit(down.position)?.let(m.onTap)
            }
        }
    }

    Canvas(modifier.then(gestures)) {
        renderer.setView(size.width, size.height, camera.yaw, camera.pitch, camera.zoom, camera.panX, camera.panY)
        if (showGround) drawGround(renderer, style)
        drawIntoCanvas { renderer.drawMesh(it.nativeCanvas, style, wireframe) }
        if (dimensions != null) drawDimensions(renderer, dimensions, style, textMeasurer)
        if (showAxes) drawAxes(renderer, style, textMeasurer)
        measure?.let { drawMeasure(renderer, it, style, textMeasurer) }
    }
}

private const val POINT_GRAB_DP = 28

internal data class MeshStyle(
    val shadeDark: Color,
    val shadeLight: Color,
    val wire: Color,
    val grid: Color,
    val shadow: Color,
    val dimension: Color,
    val pointFill: Color,
    val labelBg: Color,
    val labelStyle: TextStyle,
    val axisLabelStyle: TextStyle,
)

/** Projection + drawing. Not thread-safe; one per MeshView. */
internal class MeshRenderer(private val mesh: Mesh) {
    private val bounds = mesh.bounds()
    val sizeX = bounds.size.widthMm.toFloat()
    val sizeY = bounds.size.depthMm.toFloat()
    val sizeZ = bounds.size.heightMm.toFloat()
    private val cx = bounds.center.x.toFloat()
    private val cy = bounds.center.y.toFloat()
    private val cz = bounds.center.z.toFloat()
    private val radius = max(1f, 0.5f * sqrt(sizeX * sizeX + sizeY * sizeY + sizeZ * sizeZ))

    private val triCount = mesh.triangleCount
    private val vCount = mesh.vertexCount
    private val faceNormals = FloatArray(triCount * 3).also { n ->
        val p = mesh.positions
        val idx = mesh.indices
        for (t in 0 until triCount) {
            val a = idx[3 * t] * 3; val b = idx[3 * t + 1] * 3; val c = idx[3 * t + 2] * 3
            val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
            val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-12f) { nx /= len; ny /= len; nz /= len }
            n[3 * t] = nx; n[3 * t + 1] = ny; n[3 * t + 2] = nz
        }
    }

    // Per-frame work buffers.
    private val sx = FloatArray(vCount)
    private val sy = FloatArray(vCount)
    private val sd = FloatArray(vCount)
    private val keys = LongArray(triCount)
    private val faceColor = IntArray(triCount)
    private val faceFront = BooleanArray(triCount)
    private val verts = FloatArray(triCount * 6)
    private val colors = IntArray(triCount * 3)
    private val lines = FloatArray(triCount * 12)
    private val fillPaint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var offscreen: Bitmap? = null

    // View parameters (set by setView).
    private var cosYaw = 1f; private var sinYaw = 0f
    private var cosPitch = 1f; private var sinPitch = 0f
    private var pxPerUnit = 1f; private var camDist = 1f
    private var originX = 0f; private var originY = 0f
    var width = 0f; private set
    var height = 0f; private set

    fun setView(w: Float, h: Float, yaw: Float, pitch: Float, zoom: Float, panX: Float, panY: Float) {
        width = w; height = h
        cosYaw = cos(yaw); sinYaw = sin(yaw); cosPitch = cos(pitch); sinPitch = sin(pitch)
        camDist = radius * 3.2f
        pxPerUnit = 0.40f * min(w, h) / radius * zoom
        originX = w / 2 + panX; originY = h / 2 + panY
    }

    /** Projects a model-space point (mm). Returns screen x, y and view depth via [out]. */
    fun project(x: Float, y: Float, z: Float, out: FloatArray) {
        val qx = x - cx; val qy = y - cy; val qz = z - cz
        val x1 = qx * cosYaw - qy * sinYaw
        val y1 = qx * sinYaw + qy * cosYaw
        val up = y1 * sinPitch + qz * cosPitch
        val depth = y1 * cosPitch - qz * sinPitch
        val f = pxPerUnit * camDist / (camDist + depth)
        out[0] = originX + x1 * f
        out[1] = originY - up * f
        out[2] = depth
    }

    /** Model-space point from view space (right, up, depth): the inverse of [project]'s rotation. */
    private fun viewToModel(right: Float, up: Float, depth: Float): Vec3 {
        val y1 = up * sinPitch + depth * cosPitch
        val qz = up * cosPitch - depth * sinPitch
        val qx = right * cosYaw + y1 * sinYaw
        val qy = -right * sinYaw + y1 * cosYaw
        return Vec3((qx + cx).toDouble(), (qy + cy).toDouble(), (qz + cz).toDouble())
    }

    /** The mesh point under a screen position, or null (ray from the eye through the pixel). */
    fun hit(screen: Offset): Vec3? {
        if (width <= 0f) return null
        val eye = viewToModel(0f, 0f, -camDist)
        // At depth 0 the projection scale is exactly pxPerUnit.
        val through = viewToModel((screen.x - originX) / pxPerUnit, -(screen.y - originY) / pxPerUnit, 0f)
        return mesh.raycast(eye, through - eye)
    }

    /** Index of the point drawn within [radiusPx] of [screen], nearest first; -1 if none. */
    fun pointNear(points: List<Vec3>, screen: Offset, radiusPx: Float): Int {
        val out = FloatArray(3)
        var best = -1
        var bestD = radiusPx
        points.forEachIndexed { i, p ->
            project(p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), out)
            val d = (Offset(out[0], out[1]) - screen).getDistance()
            if (d <= bestD) { bestD = d; best = i }
        }
        return best
    }

    /** Rotation only (for the axis triad): returns screen direction of a model axis. */
    fun direction(x: Float, y: Float, z: Float): Offset {
        val x1 = x * cosYaw - y * sinYaw
        val y1 = x * sinYaw + y * cosYaw
        return Offset(x1, -(y1 * sinPitch + z * cosPitch))
    }

    val groundZ: Float get() = cz - sizeZ / 2
    val minX get() = cx - sizeX / 2
    val maxX get() = cx + sizeX / 2
    val minY get() = cy - sizeY / 2
    val maxY get() = cy + sizeY / 2
    val maxZ get() = cz + sizeZ / 2
    val groundHalf get() = radius * 1.4f

    fun drawMesh(canvas: AndroidCanvas, style: MeshStyle, wireframe: Boolean) {
        val p = mesh.positions
        val out = FloatArray(3)
        for (v in 0 until vCount) {
            project(p[3 * v], p[3 * v + 1], p[3 * v + 2], out)
            sx[v] = out[0]; sy[v] = out[1]; sd[v] = out[2]
        }
        // Light from upper-left, slightly in front (view space: right, up, depth).
        val lr = -0.38f; val lu = 0.62f; val ld = -0.69f
        val dark = style.shadeDark; val light = style.shadeLight
        val idx = mesh.indices
        for (t in 0 until triCount) {
            val nx = faceNormals[3 * t]; val ny = faceNormals[3 * t + 1]; val nz = faceNormals[3 * t + 2]
            val x1 = nx * cosYaw - ny * sinYaw
            val y1 = nx * sinYaw + ny * cosYaw
            var nr = x1
            var nu = y1 * sinPitch + nz * cosPitch
            var nd = y1 * cosPitch - nz * sinPitch
            faceFront[t] = nd < 0f
            if (nd > 0f) { nr = -nr; nu = -nu; nd = -nd } // two-sided lighting
            val diffuse = max(0f, nr * lr + nu * lu + nd * ld)
            val k = 0.16f + 0.84f * diffuse
            faceColor[t] = lerpArgb(dark, light, k)
            val d = (sd[idx[3 * t]] + sd[idx[3 * t + 1]] + sd[idx[3 * t + 2]]) / 3f
            keys[t] = (sortableBits(d).toLong() shl 32) or t.toLong()
        }
        keys.sort()

        if (wireframe) {
            linePaint.color = style.wire.toArgb()
            linePaint.strokeWidth = 1.2f
            var n = 0
            for (i in triCount - 1 downTo 0) {
                val t = (keys[i] and 0xFFFFFFFFL).toInt()
                if (!faceFront[t]) continue
                val a = idx[3 * t]; val b = idx[3 * t + 1]; val c = idx[3 * t + 2]
                n = edge(n, a, b); n = edge(n, b, c); n = edge(n, c, a)
            }
            canvas.drawLines(lines, 0, n, linePaint)
            return
        }

        var vi = 0; var ci = 0
        for (i in triCount - 1 downTo 0) { // far → near
            val t = (keys[i] and 0xFFFFFFFFL).toInt()
            for (k in 0 until 3) {
                val v = idx[3 * t + k]
                verts[vi++] = sx[v]; verts[vi++] = sy[v]
                colors[ci++] = faceColor[t]
            }
        }
        // drawVertices is hardware-accelerated from API 29; older versions render offscreen.
        if (Build.VERSION.SDK_INT >= 29 || !canvas.isHardwareAccelerated) {
            canvas.drawVertices(AndroidCanvas.VertexMode.TRIANGLES, vi, verts, 0, null, 0, colors, 0, null, 0, 0, fillPaint)
        } else {
            val w = width.toInt().coerceAtLeast(1); val h = height.toInt().coerceAtLeast(1)
            val bmp = offscreen?.takeIf { it.width == w && it.height == h }
                ?: createBitmap(w, h).also { offscreen = it }
            bmp.eraseColor(android.graphics.Color.TRANSPARENT)
            AndroidCanvas(bmp).drawVertices(AndroidCanvas.VertexMode.TRIANGLES, vi, verts, 0, null, 0, colors, 0, null, 0, 0, fillPaint)
            canvas.drawBitmap(bmp, 0f, 0f, null)
        }
    }

    private fun edge(n: Int, a: Int, b: Int): Int {
        lines[n] = sx[a]; lines[n + 1] = sy[a]; lines[n + 2] = sx[b]; lines[n + 3] = sy[b]
        return n + 4
    }

    private companion object {
        /** Float → Int with the same ordering (for packing into sortable longs). */
        fun sortableBits(f: Float): Int {
            val i = f.toRawBits()
            return if (i < 0) i xor 0x7FFFFFFF else i
        }

        fun lerpArgb(a: Color, b: Color, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            return android.graphics.Color.argb(
                1f,
                a.red + (b.red - a.red) * k,
                a.green + (b.green - a.green) * k,
                a.blue + (b.blue - a.blue) * k,
            )
        }
    }
}

private fun DrawScope.drawGround(r: MeshRenderer, style: MeshStyle) {
    val out = FloatArray(3)
    fun pt(x: Float, y: Float): Offset { r.project(x, y, r.groundZ, out); return Offset(out[0], out[1]) }
    val g = r.groundHalf
    val cx = (r.minX + r.maxX) / 2; val cy = (r.minY + r.maxY) / 2
    val steps = 6
    for (i in 0..steps) {
        val f = -g + 2 * g * i / steps
        drawLine(style.grid, pt(cx + f, cy - g), pt(cx + f, cy + g), strokeWidth = 1.dp.toPx())
        drawLine(style.grid, pt(cx - g, cy + f), pt(cx + g, cy + f), strokeWidth = 1.dp.toPx())
    }
    // Soft contact shadow: an ellipse on the ground under the footprint.
    val rx = (r.maxX - r.minX) * 0.6f; val ry = (r.maxY - r.minY) * 0.6f
    val shadow = Path()
    for (i in 0..32) {
        val a = 2 * PI * i / 32
        val o = pt(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat())
        if (i == 0) shadow.moveTo(o.x, o.y) else shadow.lineTo(o.x, o.y)
    }
    shadow.close()
    drawPath(shadow, style.shadow)
}

private fun DrawScope.drawDimensions(r: MeshRenderer, labels: DimensionLabels, style: MeshStyle, tm: TextMeasurer) {
    val out = FloatArray(3)
    fun pt(x: Float, y: Float, z: Float): Pair<Offset, Float> { r.project(x, y, z, out); return Offset(out[0], out[1]) to out[2] }
    val z0 = r.groundZ
    val stroke = 1.5.dp.toPx()
    val tick = 6.dp.toPx()

    // Width: the nearer of the two bottom edges running along X.
    val (a1, d1) = pt(r.minX, r.minY, z0); val (b1, e1) = pt(r.maxX, r.minY, z0)
    val (a2, d2) = pt(r.minX, r.maxY, z0); val (b2, e2) = pt(r.maxX, r.maxY, z0)
    val (wa, wb) = if (d1 + e1 < d2 + e2) a1 to b1 else a2 to b2
    val drop = Offset(0f, 14.dp.toPx())
    dimensionLine(wa + drop, wb + drop, tick, stroke, style.dimension)
    label(tm, labels.width, (wa + wb) / 2f + drop + Offset(0f, 16.dp.toPx()), style)

    // Height: the vertical edge furthest right on screen.
    val corners = listOf(r.minX to r.minY, r.maxX to r.minY, r.minX to r.maxY, r.maxX to r.maxY)
    val (hx, hy) = corners.maxBy { (x, y) -> pt(x, y, z0).first.x }
    val bottom = pt(hx, hy, z0).first; val top = pt(hx, hy, r.maxZ).first
    val side = Offset(18.dp.toPx(), 0f)
    dimensionLine(bottom + side, top + side, tick, stroke, style.dimension)
    label(tm, labels.height, (bottom + top) / 2f + side + Offset(28.dp.toPx(), 0f), style)
}

private fun DrawScope.dimensionLine(a: Offset, b: Offset, tick: Float, stroke: Float, color: Color) {
    drawLine(color, a, b, strokeWidth = stroke)
    val d = b - a
    val len = d.getDistance().coerceAtLeast(1f)
    val n = Offset(-d.y / len, d.x / len) * tick
    drawLine(color, a - n, a + n, strokeWidth = stroke)
    drawLine(color, b - n, b + n, strokeWidth = stroke)
}

private fun DrawScope.label(tm: TextMeasurer, text: String, center: Offset, style: MeshStyle) {
    val layout = tm.measure(text, style.labelStyle)
    val pad = 4.dp.toPx()
    // Clamp into the viewport so labels near an edge stay readable.
    val margin = 6.dp.toPx() + pad
    val topLeft = Offset(
        (center.x - layout.size.width / 2f).coerceIn(margin, (size.width - layout.size.width - margin).coerceAtLeast(margin)),
        (center.y - layout.size.height / 2f).coerceIn(margin, (size.height - layout.size.height - margin).coerceAtLeast(margin)),
    )
    drawRoundRect(
        style.labelBg,
        topLeft = topLeft - Offset(pad, pad / 2),
        size = androidx.compose.ui.geometry.Size(layout.size.width + 2 * pad, layout.size.height + pad),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(pad),
    )
    drawText(layout, topLeft = topLeft)
}

private fun DrawScope.drawAxes(r: MeshRenderer, style: MeshStyle, tm: TextMeasurer) {
    val origin = Offset(24.dp.toPx(), size.height - 24.dp.toPx())
    val len = 22.dp.toPx()
    listOf(
        Triple(r.direction(1f, 0f, 0f), Color(0xFFFF6B5E), "X"),
        Triple(r.direction(0f, 1f, 0f), Color(0xFF4ADE80), "Y"),
        Triple(r.direction(0f, 0f, 1f), Color(0xFF6AA8FF), "Z"),
    ).forEach { (dir, color, name) ->
        val end = origin + dir * len
        drawLine(color, origin, end, strokeWidth = 2.dp.toPx())
        val layout = tm.measure(name, style.axisLabelStyle)
        drawText(layout, topLeft = origin + dir * (len + 8.dp.toPx()) - Offset(layout.size.width / 2f, layout.size.height / 2f))
    }
}

private fun DrawScope.drawMeasure(r: MeshRenderer, m: MeasureOverlay, style: MeshStyle, tm: TextMeasurer) {
    val out = FloatArray(3)
    val screen = m.points.map { p -> r.project(p.x.toFloat(), p.y.toFloat(), p.z.toFloat(), out); Offset(out[0], out[1]) }
    if (screen.size == 2) {
        drawLine(style.dimension, screen[0], screen[1], strokeWidth = 3.dp.toPx())
        m.label?.let { label(tm, it, (screen[0] + screen[1]) / 2f + Offset(0f, -18.dp.toPx()), style) }
    }
    val radius = 9.dp.toPx()
    for (p in screen) {
        drawCircle(style.pointFill, radius, p)
        drawCircle(style.dimension, radius, p, style = Stroke(2.5.dp.toPx()))
    }
}
