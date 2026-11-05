package app.facet.scan

import app.facet.geometry.BoxSize
import app.facet.geometry.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * Auto-sizes the scan box from depth points around a tap (docs/04 §1 "Fit box", Fit to object).
 *
 * Phone depth is coarse and noisy (S24 Ultra: 160 × 90, ±2 cm per sample at arm's length), so
 * single points can't separate "object" from "table". Instead:
 *
 * 1. Fit the tabletop as the depth camera sees it: it sits 1–6 cm off the AR plane and tilted by a
 *    couple of degrees, which alone is ~1 cm of false height across 30 cm.
 * 2. Bin points into small horizontal cells; a cell is **object** when its median
 *    height is clearly above the table — robust to per-point noise.
 * 3. Flood-fill object cells from the one nearest the tap (small gaps allowed), so a separate object
 *    nearby isn't swallowed into the box.
 * 4. Orient the box along the object's main horizontal axis (PCA), trim outliers, add a margin.
 *
 * Input points are in the plane frame (Z up, mm, AR plane at Z = 0), ideally gathered over several
 * depth frames. Pure Kotlin: both platforms feed it from their own depth API.
 */
/**
 * Where the user pointed. [Ray] for a tap: the camera ray through the finger, because the tap's own
 * hit can pass through glass or dark objects and land on the table behind them. [Point] for Fit to
 * object: the current box centre on the table. Plane frame, mm.
 */
sealed interface FitSeed {
    data class Point(val x: Double, val y: Double) : FitSeed

    /** [direction] needn't be normalised. */
    data class Ray(val origin: Vec3, val direction: Vec3) : FitSeed
}

object BoxAutoFit {

    /** Why a fit did or didn't work, for on-device tuning logs. */
    data class Report(
        val inputPoints: Int,
        /** Table height seen by the depth camera at the seed, relative to the AR plane (mm). */
        val tableOffsetMm: Double,
        /** Spread of table depth samples around the fitted table (mm). */
        val tableNoiseMm: Double,
        /** Cells around the tap with enough samples / of those, cells that look like object. */
        val cells: Int,
        val objectCells: Int,
        /** Object cells connected to the tap. */
        val clusterCells: Int,
        val box: PlacedBox?,
    )

    fun fit(points: List<Vec3>, seedX: Double, seedY: Double, tuning: ScanTuning.Placement): PlacedBox? =
        fitWithReport(points, FitSeed.Point(seedX, seedY), tuning).box

    fun fitWithReport(points: List<Vec3>, seed: FitSeed, tuning: ScanTuning.Placement): Report {
        val radius = tuning.autoSizeClusterRadiusMm
        val inRadius = when (seed) {
            is FitSeed.Point -> points.filter { hypot(it.x - seed.x, it.y - seed.y) <= radius }
            is FitSeed.Ray -> {
                // Corridor along the ray's footprint on the table, from the camera to where it meets the table.
                val (a, b) = rayFootprint(seed)
                points.filter { segmentDistance2d(it.x, it.y, a, b) <= radius }
            }
        }
        val table = fitTable(inRadius, tuning)
        // An object must stand clearly out of the table's own depth noise.
        val minHeight = max(tuning.autoSizeMinPointHeightMm, NOISE_FACTOR * table.noise)

        val cellSize = tuning.autoSizeCellMm
        val cells = HashMap<Long, MutableList<Vec3>>()
        for (p in inRadius) {
            if (table.heightOf(p) > tuning.boxMaxSideMm) continue
            cells.getOrPut(cellKey(floor(p.x / cellSize).toInt(), floor(p.y / cellSize).toInt())) { mutableListOf() }.add(p)
        }
        val sampled = cells.filterValues { it.size >= tuning.autoSizeMinPointsPerCell }
        val objectCells = sampled.filterValues { pts ->
            pts.map(table::heightOf).sorted().quantile(CELL_HEIGHT_QUANTILE) >= minHeight
        }
        fun report(cluster: Int, box: PlacedBox?) =
            Report(points.size, table.at(seedX(seed), seedY(seed)), table.noise, sampled.size, objectCells.size, cluster, box)

        val heights = objectCells.mapValues { (k, pts) ->
            table.at((k.ix() + 0.5) * cellSize, (k.iy() + 0.5) * cellSize) + pts.map(table::heightOf).sorted().quantile(CELL_HEIGHT_QUANTILE)
        }
        val distance = startDistance(seed, heights, cellSize)
        val first = connectedCells(objectCells.keys, distance, tuning)
        if (first.isEmpty()) return report(0, null)

        // Phone depth smears object edges into low "streaks" along the line of sight, which can bridge
        // to the next object. Keep cells that reach a fair share of this object's height (no effect on
        // flat objects, where the cutoff stays at the noise floor).
        val above = heights.mapValues { (k, h) -> h - table.at((k.ix() + 0.5) * cellSize, (k.iy() + 0.5) * cellSize) }
        val peak = first.map(above::getValue).sorted().quantile(0.9)
        val cutoff = max(minHeight, SMEAR_FRACTION * peak)
        val cluster = connectedCells(objectCells.keys.filter { above.getValue(it) >= cutoff }.toSet(), distance, tuning)
            .ifEmpty { first }

        // Object points: the raised samples of the connected cells.
        val obj = cluster.flatMap { k -> objectCells.getValue(k).filter { table.heightOf(it) >= minHeight / 2 } }
        if (obj.size < tuning.autoSizeMinPoints) return report(cluster.size, null)

        // Main horizontal direction.
        val mx = obj.sumOf { it.x } / obj.size
        val my = obj.sumOf { it.y } / obj.size
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (p in obj) {
            val dx = p.x - mx
            val dy = p.y - my
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        val yaw = 0.5 * atan2(2 * sxy, sxx - syy)
        val c = cos(yaw)
        val s = sin(yaw)

        val us = obj.map { p -> p.x * c + p.y * s }.sorted()
        val vs = obj.map { p -> -p.x * s + p.y * c }.sorted()
        val zs = obj.map(table::heightOf).sorted()
        val t = tuning.autoSizeTrimFraction
        val uMin = us.quantile(t)
        val uMax = us.quantile(1 - t)
        val vMin = vs.quantile(t)
        val vMax = vs.quantile(1 - t)
        val top = zs.quantile(1 - t)

        val m = tuning.autoSizeMarginMm
        val uc = (uMin + uMax) / 2
        val vc = (vMin + vMax) / 2
        val cx = uc * c - vc * s
        val cy = uc * s + vc * c
        val box = PlacedBox(
            centerX = cx,
            centerY = cy,
            yawDeg = normalizeDeg(yaw * 180.0 / PI),
            // The box base stays on the AR plane (Z = 0), so it must also reach down to the real table.
            size = BoxSize(uMax - uMin + 2 * m, vMax - vMin + 2 * m, top + max(table.at(cx, cy), 0.0) + m),
        )
        return report(cluster.size, box.clamped(tuning))
    }

    /** The tabletop as seen by depth: z = a·x + b·y + c in the plane frame. */
    internal data class TablePlane(val a: Double, val b: Double, val c: Double, val noise: Double) {
        fun at(x: Double, y: Double): Double = a * x + b * y + c
        fun heightOf(p: Vec3): Double = p.z - at(p.x, p.y)
    }

    /**
     * Fits the tabletop: start from the dominant height band ([tableOffset]), then least-squares
     * plane fits on the points within [TABLE_INLIER_MM] of the current fit, three rounds.
     */
    internal fun fitTable(points: List<Vec3>, tuning: ScanTuning.Placement): TablePlane {
        var plane = TablePlane(0.0, 0.0, tableOffset(points, tuning), TABLE_INLIER_MM)
        repeat(3) {
            val inliers = points.filter { abs(plane.heightOf(it)) <= TABLE_INLIER_MM }
            if (inliers.size < tuning.autoSizeMinPoints) return plane
            plane = leastSquaresPlane(inliers) ?: return plane
        }
        val inliers = points.filter { abs(plane.heightOf(it)) <= TABLE_INLIER_MM }
        val noise = if (inliers.isEmpty()) TABLE_INLIER_MM else kotlin.math.sqrt(inliers.sumOf { plane.heightOf(it).let { h -> h * h } } / inliers.size)
        return plane.copy(noise = noise)
    }

    private fun leastSquaresPlane(pts: List<Vec3>): TablePlane? {
        // Centre the data for numerical stability, then solve the 3×3 normal equations (Cramer).
        val mx = pts.sumOf { it.x } / pts.size
        val my = pts.sumOf { it.y } / pts.size
        val mz = pts.sumOf { it.z } / pts.size
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0; var sxz = 0.0; var syz = 0.0
        for (p in pts) {
            val x = p.x - mx; val y = p.y - my; val z = p.z - mz
            sxx += x * x; sxy += x * y; syy += y * y; sxz += x * z; syz += y * z
        }
        val det = sxx * syy - sxy * sxy
        if (abs(det) < 1e-9) return null
        val a = (sxz * syy - syz * sxy) / det
        val b = (syz * sxx - sxz * sxy) / det
        // A real table is within a few degrees of the AR plane; anything steeper is a bad fit.
        if (abs(a) > MAX_TABLE_SLOPE || abs(b) > MAX_TABLE_SLOPE) return null
        return TablePlane(a, b, mz - a * mx - b * my, 0.0)
    }

    /**
     * Height of the tabletop relative to Z = 0: the lowest 4 mm height band within
     * ±[ScanTuning.Placement.autoSizeTableSearchMm] of the plane holding at least half as many points
     * as the densest band (so the top of a flat object isn't mistaken for the table), refined to the
     * mean of the points around it. 0 if there's no clear table.
     */
    internal fun tableOffset(points: List<Vec3>, tuning: ScanTuning.Placement): Double {
        val range = tuning.autoSizeTableSearchMm
        val bin = 4.0
        val counts = IntArray((2 * range / bin).toInt() + 1)
        for (p in points) {
            if (p.z < -range || p.z > range) continue
            counts[((p.z + range) / bin).toInt().coerceAtMost(counts.size - 1)]++
        }
        val peak = counts.max()
        if (peak < tuning.autoSizeMinPoints) return 0.0
        // Lowest strong band, then up to the top of its own peak (table noise spreads over a few bands).
        var best = counts.indices.first { counts[it] * 2 >= peak }
        while (best + 1 < counts.size && counts[best + 1] >= counts[best]) best++
        val center = -range + (best + 0.5) * bin
        val around = points.filter { abs(it.z - center) <= 2 * bin }
        return if (around.isEmpty()) center else around.sumOf { it.z } / around.size
    }

    /**
     * Object cells reachable from the one nearest the seed, stepping up to
     * [ScanTuning.Placement.autoSizeCellGap] cells at a time (so depth holes don't split an object).
     * Empty if no object cell is near the tap.
     */
    internal fun connectedCells(cells: Set<Long>, distance: (Long) -> Double, tuning: ScanTuning.Placement): List<Long> {
        if (cells.isEmpty()) return emptyList()
        val start = cells.minBy(distance)
        // A tap that lands far from anything is not a tap on an object.
        if (distance(start) > tuning.autoSizeMaxSeedGapMm + tuning.autoSizeCellMm) return emptyList()

        val reach = tuning.autoSizeCellGap
        val visited = hashSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty()) {
            val k = queue.removeFirst()
            for (dx in -reach..reach) for (dy in -reach..reach) {
                val n = cellKey(k.ix() + dx, k.iy() + dy)
                if (n in cells && visited.add(n)) queue += n
            }
        }
        return visited.toList()
    }

    /**
     * How far each object cell is from what the user pointed at: horizontal distance to the point,
     * or 3D distance from the cell (at its median height) to the tap ray.
     */
    private fun startDistance(seed: FitSeed, heights: Map<Long, Double>, size: Double): (Long) -> Double {
        if (seed is FitSeed.Point) return { k -> hypot((k.ix() + 0.5) * size - seed.x, (k.iy() + 0.5) * size - seed.y) }
        val ray = seed as FitSeed.Ray
        val d = ray.direction * (1.0 / ray.direction.length())
        return { k ->
            val p = Vec3((k.ix() + 0.5) * size, (k.iy() + 0.5) * size, heights.getValue(k)) - ray.origin
            val along = max(0.0, p.x * d.x + p.y * d.y + p.z * d.z)
            (p - d * along).length()
        }
    }

    /** The ray's footprint on the table: camera foot → where it meets Z = 0 (or 3 m out if it never does). */
    private fun rayFootprint(ray: FitSeed.Ray): Pair<Vec3, Vec3> {
        val d = ray.direction * (1.0 / ray.direction.length())
        val t = if (d.z < -1e-6 && ray.origin.z > 0) -ray.origin.z / d.z else MAX_RAY_MM
        return ray.origin to ray.origin + d * minOf(t, MAX_RAY_MM)
    }

    private fun segmentDistance2d(x: Double, y: Double, a: Vec3, b: Vec3): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 < 1e-9) 0.0 else (((x - a.x) * dx + (y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(x - (a.x + dx * t), y - (a.y + dy * t))
    }

    private const val MAX_RAY_MM = 3_000.0
    private const val TABLE_INLIER_MM = 20.0
    /** tan(≈6°). */
    private const val MAX_TABLE_SLOPE = 0.1
    /** Object cells must have a median height above this many table-noise sigmas. */
    private const val NOISE_FACTOR = 1.2
    /** Cells below this fraction of the object's peak height are edge smear, not object. */
    private const val SMEAR_FRACTION = 0.35

    private fun seedX(seed: FitSeed) = when (seed) {
        is FitSeed.Point -> seed.x
        is FitSeed.Ray -> rayFootprint(seed).second.x
    }

    private fun seedY(seed: FitSeed) = when (seed) {
        is FitSeed.Point -> seed.y
        is FitSeed.Ray -> rayFootprint(seed).second.y
    }

    /** Median: a cell is object when at least half its samples are raised (per-sample noise cancels out). */
    private const val CELL_HEIGHT_QUANTILE = 0.5

    private fun cellKey(ix: Int, iy: Int): Long = ix.toLong() shl 32 or (iy.toLong() and 0xffffffffL)
    private fun Long.ix(): Int = (this shr 32).toInt()
    private fun Long.iy(): Int = toInt()
}

/** Linear-interpolated quantile of an ascending list, q in 0..1. */
private fun List<Double>.quantile(q: Double): Double {
    if (size == 1) return this[0]
    val pos = q.coerceIn(0.0, 1.0) * (size - 1)
    val i = floor(pos).toInt()
    val f = pos - i
    return if (i + 1 < size) this[i] * (1 - f) + this[i + 1] * f else this[i]
}
