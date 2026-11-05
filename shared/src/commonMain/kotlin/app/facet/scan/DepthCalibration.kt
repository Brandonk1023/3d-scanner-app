package app.facet.scan

import app.facet.geometry.BoxSize
import app.facet.mesh.DepthFrame

/**
 * Phone depth disagrees with AR tracking: on the S24 Ultra ARCore raw depth reads ~10% long at
 * every distance compared with ARCore's own table plane and camera poses, which sinks the table
 * and object 2–6 cm below the scan box. The table is known exactly (Z = 0 in the box frame), so
 * each depth map is calibrated against it before fusion.
 */
object DepthCalibration {

    /**
     * Factor to multiply this frame's depth by so the table lands on Z = 0: the median of
     * (distance along the ray to the table ÷ measured depth) over confident pixels whose ray meets
     * the table outside the box footprint. Null when too little table is visible.
     *
     * [cameraToBox]: column-major 4×4, camera-frame mm (+X right, +Y up, −Z forward) → box frame.
     */
    fun tableScale(frame: DepthFrame, cameraToBox: FloatArray, box: BoxSize, tuning: ScanTuning.Reconstruction): Double? {
        val m = cameraToBox
        val tz = m[14]
        if (tz <= 0f) return null // camera at or below the table
        val ex = box.widthMm / 2 + tuning.calibrationFootprintMarginMm
        val ey = box.depthMm / 2 + tuning.calibrationFootprintMarginMm
        val minConfidence = (tuning.calibrationMinConfidence * 255).toInt()
        val minSin = kotlin.math.sin(tuning.calibrationMinIncidenceDeg * kotlin.math.PI / 180)
        val ratios = ArrayList<Double>()
        val step = tuning.calibrationPixelStep
        for (v in 0 until frame.height step step) {
            for (u in 0 until frame.width step step) {
                val i = v * frame.width + u
                val measured = frame.depthMm[i].toInt() and 0xFFFF
                if (measured == 0) continue
                if (frame.confidence != null && (frame.confidence[i].toInt() and 0xFF) < minConfidence) continue
                // Camera ray with unit forward component: (x, y, −1).
                val x = (u + 0.5f - frame.cx) / frame.fx
                val y = (frame.cy - (v + 0.5f)) / frame.fy
                val dz = m[2] * x + m[6] * y - m[10]
                if (dz > -1e-3f) continue // ray doesn't go down to the table
                // Grazing rays hit the table at a shallow angle: tiny depth errors, big misses.
                if (-dz / kotlin.math.sqrt(x * x + y * y + 1f) < minSin) continue
                val t = -tz / dz // forward distance where the ray meets Z = 0
                if (t > tuning.calibrationMaxDistanceMm) continue
                val px = m[12] + (m[0] * x + m[4] * y - m[8]) * t
                val py = m[13] + (m[1] * x + m[5] * y - m[9]) * t
                if (kotlin.math.abs(px) < ex && kotlin.math.abs(py) < ey) continue // the object
                val r = t.toDouble() / measured
                // Far off means it isn't the table (floor past the edge, other objects).
                if (r in tuning.calibrationMinRatio..tuning.calibrationMaxRatio) ratios.add(r)
            }
        }
        if (ratios.size < tuning.calibrationMinSamples) return null
        ratios.sort()
        return ratios[ratios.size / 2]
    }

    /** Copy of [frame] with every depth value multiplied by [scale]. */
    fun scaled(frame: DepthFrame, scale: Double): DepthFrame {
        val out = ShortArray(frame.depthMm.size) { i ->
            val d = frame.depthMm[i].toInt() and 0xFFFF
            if (d == 0) 0 else (d * scale).toInt().coerceIn(1, 65_535).toShort()
        }
        return DepthFrame(frame.width, frame.height, out, frame.confidence, frame.fx, frame.fy, frame.cx, frame.cy)
    }
}
