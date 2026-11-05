package app.facet.android.ar

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import app.facet.scan.ScanTuning
import org.json.JSONArray
import org.json.JSONObject
import app.facet.mesh.ObjectMask
import app.facet.scan.ObjectSegmenter
import app.facet.scan.plausible
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Saves keyframes for High detail (docs/04 §2 "Keyframes"): when the camera has moved ≥ 10° or
 * ≥ 5 cm since the last one and the image is sharp (variance of Laplacian), write
 * kf_NNN.jpg + kf_NNN.depth (raw depth + confidence) + kf_NNN.json (pose in the box frame and
 * intrinsics) into [dir]. Capped at [ScanTuning.Keyframes.maxCount].
 */
internal class KeyframeWriter(
    private val dir: File,
    private val tuning: ScanTuning.Keyframes,
    private val reconstruction: ScanTuning.Reconstruction,
    /** Created lazily on the keyframe thread (model load takes a moment); null = no carving. */
    private val segmenterFactory: (() -> ObjectSegmenter)?,
    /** Where to seed segmentation: a point inside the object, box frame mm. */
    private val seedInBox: FloatArray,
    /** A plausible object mask, with intrinsics already scaled to the mask (keyframe thread). */
    private val onMask: (ObjectMask, fx: Float, fy: Float, cx: Float, cy: Float, cameraToBox: FloatArray) -> Unit,
) {
    private var segmenter: ObjectSegmenter? = null
    private val masks = AtomicInteger(0)

    /** Plausible masks produced so far. */
    val maskCount: Int get() = masks.get()
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "facet-keyframes") }
    private val busy = AtomicBoolean(false)
    private val count = AtomicInteger(0)
    private val bytes = AtomicLong(0)

    /** cam→box of the last saved keyframe; written by the worker, read on the GL thread. */
    @Volatile private var lastPose: FloatArray? = null

    val savedCount: Int get() = count.get()
    val savedBytes: Long get() = bytes.get()

    init {
        dir.mkdirs()
    }

    /** GL thread: is this pose far enough from the last keyframe to be worth a new one? */
    fun wants(cameraToBox: FloatArray): Boolean {
        if (busy.get() || count.get() >= tuning.maxCount) return false
        val last = lastPose ?: return true
        val moved = sqrt(sq(cameraToBox[12] - last[12]) + sq(cameraToBox[13] - last[13]) + sq(cameraToBox[14] - last[14]))
        return moved >= tuning.minTranslationMm || rotationDeg(last, cameraToBox) >= tuning.minRotationDeg
    }

    /** Raw depth captured with the frame, if any (same layout as the fusion input). */
    class Depth(val width: Int, val height: Int, val depth: ShortArray, val confidence: ByteArray?, val fx: Float, val fy: Float, val cx: Float, val cy: Float)

    /**
     * GL thread: copies [image] (YUV_420_888, caller closes it) and queues the keyframe.
     * Intrinsics are the CPU image's.
     */
    fun submit(
        image: Image,
        timestampNs: Long,
        cameraToBox: FloatArray,
        fx: Float, fy: Float, cx: Float, cy: Float,
        depth: Depth?,
        /** DepthCalibration factor to apply to the raw depth, if known. */
        depthScale: Double?,
    ) {
        if (!busy.compareAndSet(false, true)) return
        val width = image.width
        val height = image.height
        val nv21 = try {
            toNv21(image)
        } catch (e: Exception) {
            Log.w(TAG, "Keyframe copy failed", e)
            busy.set(false)
            return
        }
        executor.execute {
            try {
                write(nv21, width, height, timestampNs, cameraToBox, fx, fy, cx, cy, depth, depthScale)
            } catch (e: Exception) {
                Log.w(TAG, "Keyframe write failed", e)
            } finally {
                busy.set(false)
            }
        }
    }

    private fun write(
        nv21: ByteArray, width: Int, height: Int, timestampNs: Long, cameraToBox: FloatArray,
        fx: Float, fy: Float, cx: Float, cy: Float, depth: Depth?, depthScale: Double?,
    ) {
        val sharpness = laplacianVariance(nv21, width, height)
        if (sharpness < tuning.minSharpness) return
        val n = count.get() + 1
        val name = String.format(Locale.ROOT, "kf_%03d", n)
        var written = 0L

        val jpeg = ByteArrayOutputStream().use { out ->
            YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
            out.toByteArray()
        }
        File(dir, "$name.jpg").writeBytes(jpeg)
        written += jpeg.size
        written += segment(name, jpeg, width, height, cameraToBox, fx, fy, cx, cy)

        val json = JSONObject()
            .put("timestampNs", timestampNs)
            .put("sharpness", sharpness)
            .put("cameraToBox", JSONArray(cameraToBox.map { it.toDouble() }))
            .put("image", JSONObject().put("width", width).put("height", height).put("fx", fx).put("fy", fy).put("cx", cx).put("cy", cy))
        if (depth != null) {
            val buf = ByteBuffer.allocate(depth.depth.size * 2 + (depth.confidence?.size ?: 0)).order(ByteOrder.LITTLE_ENDIAN)
            depth.depth.forEach { buf.putShort(it) }
            depth.confidence?.let { buf.put(it) }
            File(dir, "$name.depth").writeBytes(buf.array())
            written += buf.capacity()
            json.put(
                "depth",
                JSONObject().put("file", "$name.depth").put("width", depth.width).put("height", depth.height)
                    .put("fx", depth.fx).put("fy", depth.fy).put("cx", depth.cx).put("cy", depth.cy)
                    .put("scale", depthScale ?: JSONObject.NULL)
                    .put("format", "uint16le mm, then uint8 confidence" + if (depth.confidence == null) " (absent)" else ""),
            )
        }
        File(dir, "$name.json").writeText(json.toString())
        written += File(dir, "$name.json").length()

        bytes.addAndGet(written)
        count.set(n)
        lastPose = cameraToBox
    }

    /** Finds the object's outline and hands it to carving. Returns bytes written (the saved mask). */
    private fun segment(name: String, jpeg: ByteArray, width: Int, height: Int, cameraToBox: FloatArray, fx: Float, fy: Float, cx: Float, cy: Float): Long {
        val factory = segmenterFactory ?: return 0
        val seg = segmenter ?: runCatching(factory).onFailure { Log.w(TAG, "Segmenter unavailable", it) }.getOrNull()?.also { segmenter = it }
            ?: return 0
        // Seed: the point inside the object, projected into this photo.
        val s = seedInBox
        val m = cameraToBox
        val dx = s[0] - m[12]
        val dy = s[1] - m[13]
        val dz = s[2] - m[14]
        val camX = m[0] * dx + m[1] * dy + m[2] * dz
        val camY = m[4] * dx + m[5] * dy + m[6] * dz
        val camZ = m[8] * dx + m[9] * dy + m[10] * dz
        if (camZ >= -1f) return 0 // behind the camera
        val seedX = (fx * camX / -camZ + cx) / width
        val seedY = (cy - fy * camY / -camZ) / height
        if (seedX !in 0f..1f || seedY !in 0f..1f) return 0
        val mask = seg.segment(jpeg, seedX, seedY) ?: return 0
        if (!mask.plausible(seedX, seedY, reconstruction)) return 0
        masks.incrementAndGet()
        val sx = mask.width.toFloat() / width
        val sy = mask.height.toFloat() / height
        onMask(mask, fx * sx, fy * sy, cx * sx, cy * sy, cameraToBox)
        // Kept for High detail (and debugging): raw bytes, 1 = object.
        val file = File(dir, "$name.mask")
        file.writeBytes(mask.pixels)
        return file.length()
    }

    fun close() {
        executor.execute { segmenter?.close() }
        executor.shutdown()
    }

    private companion object {
        const val TAG = "FacetKeyframes"
        const val JPEG_QUALITY = 90

        fun sq(v: Float) = v * v

        /** Angle between the rotation parts of two rigid transforms, degrees. */
        fun rotationDeg(a: FloatArray, b: FloatArray): Double {
            // trace(Aᵀ B) over the 3×3 rotation blocks (column-major).
            var trace = 0.0
            for (col in 0 until 3) for (row in 0 until 3) trace += a[col * 4 + row] * b[col * 4 + row]
            return Math.toDegrees(acos(((trace - 1) / 2).coerceIn(-1.0, 1.0)))
        }

        /** YUV_420_888 → NV21 (Y plane, then interleaved V/U). */
        fun toNv21(image: Image): ByteArray {
            val w = image.width
            val h = image.height
            val out = ByteArray(w * h * 3 / 2)
            val y = image.planes[0]
            copyPlane(y.buffer, y.rowStride, y.pixelStride, w, h, out, 0, 1)
            val u = image.planes[1]
            val v = image.planes[2]
            copyPlane(v.buffer, v.rowStride, v.pixelStride, w / 2, h / 2, out, w * h, 2)
            copyPlane(u.buffer, u.rowStride, u.pixelStride, w / 2, h / 2, out, w * h + 1, 2)
            return out
        }

        fun copyPlane(src: ByteBuffer, rowStride: Int, pixelStride: Int, w: Int, h: Int, out: ByteArray, offset: Int, outStride: Int) {
            var o = offset
            for (row in 0 until h) {
                val base = row * rowStride
                for (col in 0 until w) {
                    out[o] = src.get(base + col * pixelStride)
                    o += outStride
                }
            }
        }

        /** Variance of the 4-neighbour Laplacian over the centre of the luma plane. */
        fun laplacianVariance(nv21: ByteArray, w: Int, h: Int): Double {
            val x0 = w / 4
            val x1 = w * 3 / 4
            val y0 = h / 4
            val y1 = h * 3 / 4
            var sum = 0.0
            var sumSq = 0.0
            var n = 0
            for (y in y0 until y1 step 2) {
                for (x in x0 until x1 step 2) {
                    val c = nv21[y * w + x].toInt() and 0xFF
                    val l = (nv21[(y - 1) * w + x].toInt() and 0xFF) + (nv21[(y + 1) * w + x].toInt() and 0xFF) +
                        (nv21[y * w + x - 1].toInt() and 0xFF) + (nv21[y * w + x + 1].toInt() and 0xFF) - 4 * c
                    sum += l
                    sumSq += l.toDouble() * l
                    n++
                }
            }
            if (n == 0) return 0.0
            val mean = sum / n
            return sumSq / n - mean * mean
        }
    }
}
