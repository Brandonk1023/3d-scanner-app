package app.facet.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.facet.android.R
import app.facet.data.ResultQuality
import app.facet.data.Scan
import app.facet.format.formatCount
import app.facet.format.formatDims
import app.facet.format.formatFixed
import app.facet.geometry.BoxSize
import app.facet.settings.LengthUnit

@Composable
fun unitLabel(unit: LengthUnit): String =
    stringResource(if (unit == LengthUnit.INCHES) R.string.unit_in else R.string.unit_mm)

/** "118.6 × 92.4 × 98.0 mm" */
@Composable
fun dimsText(size: BoxSize, unit: LengthUnit): String {
    val (w, d, h) = formatDims(size, unit)
    return stringResource(R.string.dims_format, w, d, h, unitLabel(unit))
}

/** "Quick result · 6.7k triangles" */
@Composable
fun resultSubtitle(scan: Scan): String = stringResource(
    if (scan.quality == ResultQuality.HIGH_DETAIL) R.string.result_high_tris else R.string.result_quick_tris,
    formatCount(scan.triangleCount),
)

/** Placement readout: whole millimetres ("146 × 120 × 176 mm") or inches to 0.1. */
@Composable
fun boxSizeText(size: BoxSize, unit: LengthUnit): String {
    val decimals = if (unit == LengthUnit.INCHES) 1 else 0
    fun f(mm: Double) = formatFixed(unit.fromMm(mm), decimals)
    return stringResource(R.string.dims_format, f(size.widthMm), f(size.depthMm), f(size.heightMm), unitLabel(unit))
}
