package app.facet.scan

import app.facet.mesh.ObjectMask

/**
 * Finds the object's outline in a keyframe photo for silhouette carving. Android: MediaPipe
 * Interactive Segmenter; iOS: Apple Vision subject masks (Phase 9). Seeded with the scan box's
 * centre projected into the photo, so the user never taps.
 */
interface ObjectSegmenter {
    /**
     * Mask of the object at ([seedX], [seedY]) (normalised 0..1, origin top-left) in [jpeg], or
     * null if nothing sensible was found.
     */
    fun segment(jpeg: ByteArray, seedX: Float, seedY: Float): ObjectMask?

    fun close()
}

/** Rejects masks that can't be the object: too small, too big, or not containing the seed. */
fun ObjectMask.plausible(seedX: Float, seedY: Float, tuning: ScanTuning.Reconstruction): Boolean {
    var count = 0
    for (i in 0 until width * height) if (pixels[i].toInt() != 0) count++
    val area = count.toFloat() / (width * height)
    if (area < tuning.carveMinMaskArea || area > tuning.carveMaxMaskArea) return false
    val sx = (seedX * width).toInt().coerceIn(0, width - 1)
    val sy = (seedY * height).toInt().coerceIn(0, height - 1)
    return pixels[sy * width + sx].toInt() != 0
}
