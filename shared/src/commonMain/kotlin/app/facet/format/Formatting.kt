package app.facet.format

import app.facet.geometry.BoxSize
import app.facet.settings.LengthUnit
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

// Locale-neutral number formatting shared by both apps. Units are added by the UI (strings).

/** [value] rounded to [decimals] places, e.g. formatFixed(98.0, 1) = "98.0". */
fun formatFixed(value: Double, decimals: Int): String {
    require(decimals in 0..6)
    val factor = 10.0.pow(decimals)
    val scaled = (abs(value) * factor).roundToLong()
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    if (decimals == 0) return "$sign$scaled"
    val whole = scaled / factor.toLong()
    val frac = (scaled % factor.toLong()).toString().padStart(decimals, '0')
    return "$sign$whole.$frac"
}

/** A length for display: millimetres with 1 decimal, inches with 2. */
fun formatLength(mm: Double, unit: LengthUnit): String = when (unit) {
    LengthUnit.MILLIMETRES -> formatFixed(mm, 1)
    LengthUnit.INCHES -> formatFixed(unit.fromMm(mm), 2)
}

/** W, D, H as display strings in [unit]. */
fun formatDims(size: BoxSize, unit: LengthUnit): Triple<String, String, String> =
    Triple(formatLength(size.widthMm, unit), formatLength(size.depthMm, unit), formatLength(size.heightMm, unit))

/** Triangle counts: 640, 6.7k, 86k, 1.2M. */
fun formatCount(count: Int): String = when {
    count < 1_000 -> count.toString()
    count < 10_000 -> formatFixed(count / 1_000.0, 1) + "k"
    count < 1_000_000 -> formatFixed(count / 1_000.0, 0) + "k"
    else -> formatFixed(count / 1_000_000.0, 1) + "M"
}

/** File and storage sizes, SI units like Android's Formatter: 640 KB, 4.1 MB, 18.6 GB. */
fun formatBytes(bytes: Long): String {
    val b = bytes.coerceAtLeast(0).toDouble()
    return when {
        b < 1_000 -> "${bytes.coerceAtLeast(0)} B"
        b < 1_000_000 -> formatFixed(b / 1e3, 0) + " KB"
        b < 1_000_000_000 -> formatFixed(b / 1e6, 1) + " MB"
        else -> formatFixed(b / 1e9, 1) + " GB"
    }
}
