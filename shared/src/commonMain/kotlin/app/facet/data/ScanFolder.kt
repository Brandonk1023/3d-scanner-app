package app.facet.data

import app.facet.geometry.BoxSize
import app.facet.mesh.DepthFrame
import app.facet.mesh.ObjectMask
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.use

/**
 * What capture leaves behind for High detail, written to `capture.json` in the scan's folder when
 * the scan is finished. Keyframe poses are in the frame of [box], so the fine pass needs it.
 */
@Serializable
data class CaptureInfo(
    val version: Int = VERSION,
    /** The placed box (not the mesh bounds); the fusion volume adds ScanTuning's margins around it. */
    val boxWidthMm: Double,
    val boxDepthMm: Double,
    val boxHeightMm: Double,
    /** DepthCalibration's locked factor for raw depth, or null if the table was never measured. */
    val depthScale: Double? = null,
    /** Product of every scale check applied to the scan (1 = unchanged); re-applied after High detail. */
    val scaleFactor: Double = 1.0,
) {
    val box: BoxSize get() = BoxSize(boxWidthMm, boxDepthMm, boxHeightMm)

    companion object {
        const val VERSION = 1

        fun of(box: BoxSize, depthScale: Double?) =
            CaptureInfo(boxWidthMm = box.widthMm, boxDepthMm = box.depthMm, boxHeightMm = box.heightMm, depthScale = depthScale)
    }
}

/**
 * One keyframe's `kf_NNN.json`, as written by the Android KeyframeWriter (and [ScanFolder.writeKeyframe]
 * for simulated scans). Unknown fields are ignored so older and newer files both read.
 */
@Serializable
data class KeyframeMeta(
    val timestampNs: Long = 0,
    val sharpness: Double = 0.0,
    /** Column-major 4×4, camera-frame mm (+X right, +Y up, −Z forward) → box frame. */
    val cameraToBox: List<Float>,
    val image: Intrinsics,
    val depth: DepthInfo? = null,
    val mask: MaskInfo? = null,
) {
    @Serializable
    data class Intrinsics(val width: Int, val height: Int, val fx: Float, val fy: Float, val cx: Float, val cy: Float)

    @Serializable
    data class DepthInfo(
        val file: String,
        val width: Int,
        val height: Int,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
        /** Depth calibration known when the frame was saved (null early in the scan). */
        val scale: Double? = null,
    )

    /** Object outline (1 byte per pixel, nonzero = object), intrinsics scaled to the mask. */
    @Serializable
    data class MaskInfo(val file: String, val width: Int, val height: Int, val fx: Float, val fy: Float, val cx: Float, val cy: Float)
}

/** A keyframe found on disk: its file stem ("kf_007") and metadata. */
data class Keyframe(val name: String, val meta: KeyframeMeta)

/**
 * Files of one scan's folder (`files/scans/<id>/`): `capture.json` and `keyframes/`. The meshes are
 * [RoomScanRepository]'s business.
 */
class ScanFolder(private val fs: FileSystem, val dir: Path) {
    private val keyframesDir: Path get() = dir / RoomScanRepository.KEYFRAMES_DIR
    private val captureFile: Path get() = dir / CAPTURE_FILE

    fun readCaptureInfo(): CaptureInfo? = try {
        if (fs.exists(captureFile)) json.decodeFromString<CaptureInfo>(fs.read(captureFile) { readUtf8() }) else null
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null // SerializationException is one
    }

    fun writeCaptureInfo(info: CaptureInfo) {
        fs.createDirectories(dir)
        val tmp = dir / "$CAPTURE_FILE.tmp"
        fs.write(tmp) { writeUtf8(json.encodeToString(CaptureInfo.serializer(), info)) }
        fs.atomicMove(tmp, captureFile)
    }

    /** Multiplies the stored scale factor (a scale check was applied). No-op without capture.json. */
    fun recordScale(factor: Double) {
        val info = readCaptureInfo() ?: return
        writeCaptureInfo(info.copy(scaleFactor = info.scaleFactor * factor))
    }

    /** Keyframes in capture order; unreadable ones are skipped. */
    fun keyframes(): List<Keyframe> {
        if (!fs.exists(keyframesDir)) return emptyList()
        return fs.list(keyframesDir)
            .filter { it.name.startsWith("kf_") && it.name.endsWith(".json") }
            .sortedBy { it.name }
            .mapNotNull { path ->
                try {
                    val meta = json.decodeFromString<KeyframeMeta>(fs.read(path) { readUtf8() })
                    if (meta.cameraToBox.size == 16) Keyframe(path.name.removeSuffix(".json"), meta) else null
                } catch (e: IOException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
    }

    /** Raw depth + confidence (uint16 LE mm, then uint8 confidence if present); null if missing or short. */
    fun loadDepth(keyframe: Keyframe): DepthFrame? {
        val d = keyframe.meta.depth ?: return null
        val path = keyframesDir / d.file
        if (!safeName(d.file) || !fs.exists(path)) return null
        val n = d.width * d.height
        if (d.width <= 0 || d.height <= 0) return null
        return try {
            fs.source(path).buffer().use { src ->
                val depth = ShortArray(n) { src.readShortLe() }
                val confidence = if (src.exhausted()) null else src.readByteArray().takeIf { it.size >= n }
                DepthFrame(d.width, d.height, depth, confidence, d.fx, d.fy, d.cx, d.cy)
            }
        } catch (e: IOException) {
            null
        }
    }

    fun loadMask(keyframe: Keyframe): ObjectMask? {
        val m = keyframe.meta.mask ?: return null
        val path = keyframesDir / m.file
        if (!safeName(m.file) || !fs.exists(path) || m.width <= 0 || m.height <= 0) return null
        return try {
            val bytes = fs.read(path) { readByteArray() }
            if (bytes.size >= m.width * m.height) ObjectMask(m.width, m.height, bytes) else null
        } catch (e: IOException) {
            null
        }
    }

    /** Bytes used by the keyframes folder. */
    fun keyframeBytes(): Long {
        if (!fs.exists(keyframesDir)) return 0
        return fs.list(keyframesDir).sumOf { fs.metadataOrNull(it)?.size ?: 0L }
    }

    /**
     * Writes a keyframe in the capture format (debug simulated scans). Returns its stem. The image
     * itself (JPEG) is optional and not written here.
     */
    fun writeKeyframe(
        index: Int,
        cameraToBox: FloatArray,
        depth: DepthFrame,
        depthScale: Double?,
        mask: ObjectMask? = null,
    ): String {
        fs.createDirectories(keyframesDir)
        val name = "kf_" + index.toString().padStart(3, '0')
        val buf = Buffer()
        depth.depthMm.forEach { buf.writeShortLe(it.toInt()) }
        depth.confidence?.let { buf.write(it) }
        fs.write(keyframesDir / "$name.depth") { writeAll(buf) }
        val maskInfo = mask?.let {
            fs.write(keyframesDir / "$name.mask") { write(it.pixels) }
            val sx = it.width.toFloat() / depth.width
            val sy = it.height.toFloat() / depth.height
            KeyframeMeta.MaskInfo("$name.mask", it.width, it.height, depth.fx * sx, depth.fy * sy, depth.cx * sx, depth.cy * sy)
        }
        val meta = KeyframeMeta(
            cameraToBox = cameraToBox.toList(),
            image = KeyframeMeta.Intrinsics(depth.width, depth.height, depth.fx, depth.fy, depth.cx, depth.cy),
            depth = KeyframeMeta.DepthInfo("$name.depth", depth.width, depth.height, depth.fx, depth.fy, depth.cx, depth.cy, depthScale),
            mask = maskInfo,
        )
        fs.write(keyframesDir / "$name.json") { writeUtf8(json.encodeToString(KeyframeMeta.serializer(), meta)) }
        return name
    }

    private fun safeName(file: String) = file.isNotEmpty() && file.none { it == '/' || it == '\\' } && file != ".."

    companion object {
        const val CAPTURE_FILE = "capture.json"
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}
