package app.facet.scan

import app.facet.geometry.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot

/** Where the camera is relative to the box centre, in the plane frame (docs/04 §2 coverage model). */
data class CameraView(
    /** Degrees above the table plane, measured to the box centre. */
    val elevationDeg: Double,
    /** Heading around the box's vertical axis, 0..360, counter-clockwise from plane +X. */
    val azimuthDeg: Double,
    val distanceMm: Double,
)

fun cameraView(box: PlacedBox, camera: Vec3): CameraView {
    val d = camera - box.center
    val horizontal = hypot(d.x, d.y)
    return CameraView(
        elevationDeg = atan2(d.z, horizontal) * 180 / PI,
        azimuthDeg = normalize360(atan2(d.y, d.x) * 180 / PI),
        distanceMm = d.length(),
    )
}

internal fun normalize360(deg: Double): Double {
    val d = deg % 360.0
    return if (d < 0) d + 360.0 else d
}

/** Smallest angle between two headings, 0..180. */
internal fun angleBetween(a: Double, b: Double): Double {
    val d = abs(normalize360(a) - normalize360(b))
    return if (d > 180) 360 - d else d
}

/** Read-only view of coverage for the UI: rings, dial, panel. */
data class CoverageState(
    /** [band.ordinal][sector] captured. */
    val captured: List<List<Boolean>>,
    /** [band.ordinal][sector] counts toward coverage (One side limits the span). */
    val counted: List<List<Boolean>>,
    /** 0..1 per band, over counted sectors. */
    val bands: Map<CoverageBand, Float>,
    val overall: Float,
) {
    fun band(b: CoverageBand): Float = bands.getValue(b)

    /** Dial wedge [wedge] (of sectors/2) is filled when both of its sectors are captured. */
    fun wedgeFilled(band: CoverageBand, wedge: Int): Boolean =
        captured[band.ordinal][wedge * 2] && captured[band.ordinal][wedge * 2 + 1]

    companion object {
        fun empty(sectors: Int) = CoverageState(
            captured = List(CoverageBand.entries.size) { List(sectors) { false } },
            counted = List(CoverageBand.entries.size) { List(sectors) { true } },
            bands = CoverageBand.entries.associateWith { 0f },
            overall = 0f,
        )
    }
}

/**
 * 3 elevation bands × 24 azimuth sectors around the box (docs/04 §2). A sector is captured after
 * [ScanTuning.Coverage.acceptedFramesPerSector] accepted depth frames from it. One side scans only
 * count sectors within ± [ScanTuning.Coverage.oneSideHalfSpanDeg] of the start heading.
 */
class CoverageModel(
    private val tuning: ScanTuning,
    scanType: ScanType,
    startHeadingDeg: Double,
) {
    private val sectors = tuning.coverage.azimuthSectors
    private val span = tuning.coverage.sectorSpanDeg
    private val frames = Array(CoverageBand.entries.size) { IntArray(sectors) }
    private val counted: List<List<Boolean>> = List(CoverageBand.entries.size) {
        List(sectors) { s ->
            scanType == ScanType.FULL_OBJECT ||
                angleBetween((s + 0.5) * span, startHeadingDeg) <= tuning.coverage.oneSideHalfSpanDeg
        }
    }

    fun sectorFor(azimuthDeg: Double): Int = floor(normalize360(azimuthDeg) / span).toInt().coerceIn(0, sectors - 1)

    fun isCaptured(band: CoverageBand, sector: Int): Boolean =
        frames[band.ordinal][sector] >= tuning.coverage.acceptedFramesPerSector

    /** Events from one accepted frame. */
    data class Update(val sectorCaptured: Boolean, val bandCompleted: CoverageBand?)

    fun addAcceptedFrame(band: CoverageBand, azimuthDeg: Double): Update {
        val s = sectorFor(azimuthDeg)
        val wasCaptured = isCaptured(band, s)
        val wasComplete = bandFraction(band) >= 1f
        frames[band.ordinal][s]++
        val nowCaptured = !wasCaptured && isCaptured(band, s)
        val completed = nowCaptured && !wasComplete && bandFraction(band) >= 1f
        return Update(sectorCaptured = nowCaptured && counted[band.ordinal][s], bandCompleted = if (completed) band else null)
    }

    fun bandFraction(band: CoverageBand): Float {
        val c = counted[band.ordinal]
        val total = c.count { it }
        if (total == 0) return 0f
        return (0 until sectors).count { c[it] && isCaptured(band, it) }.toFloat() / total
    }

    fun state(): CoverageState {
        val bands = CoverageBand.entries.associateWith(::bandFraction)
        val total = counted.sumOf { b -> b.count { it } }
        val captured = CoverageBand.entries.sumOf { b -> (0 until sectors).count { counted[b.ordinal][it] && isCaptured(b, it) } }
        return CoverageState(
            captured = CoverageBand.entries.map { b -> List(sectors) { isCaptured(b, it) } },
            counted = counted,
            bands = bands,
            overall = if (total == 0) 0f else captured.toFloat() / total,
        )
    }
}
