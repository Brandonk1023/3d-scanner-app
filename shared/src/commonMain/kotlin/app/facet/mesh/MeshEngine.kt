package app.facet.mesh

import app.facet.geometry.BoxSize

/**
 * The shared C++ reconstruction core (native/), behind one interface.
 * Android: JNI (`AndroidMeshEngine`). iOS: Kotlin/Native cinterop (`IosMeshEngine`).
 *
 * Phase 3: TSDF volumes for live fusion. Phase 4 adds the final mesh with [CleanupOptions].
 */
interface MeshEngine {
    /** Version string of the C++ core. */
    fun coreVersion(): String

    /** Box diagonal computed in C++ — a trivial call that proves the native link works. */
    fun boxDiagonalMm(box: BoxSize): Double

    /** Empty volume over the scan box (box frame, see [TsdfVolume]); null if it can't be allocated. */
    fun createVolume(box: BoxSize, voxelMm: Double, truncationMm: Double): TsdfVolume?

    /** Restores a volume written by [TsdfVolume.save]; null if missing or corrupt. */
    fun loadVolume(path: String): TsdfVolume?
}

/** One depth map, as the platform's AR layer delivers it. [depthMm] is row-major, width × height. */
class DepthFrame(
    val width: Int,
    val height: Int,
    /** Millimetres along the optical axis, 0 = no data (read as unsigned). */
    val depthMm: ShortArray,
    /** 0..255 per pixel (read as unsigned), or null when the platform has none. */
    val confidence: ByteArray?,
    /** Intrinsics in depth pixels. */
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
)

/** An object's outline in a photo: row-major, width × height, nonzero = object. */
class ObjectMask(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(pixels.size >= width * height)
    }
}

data class IntegrateResult(val updatedVoxels: Int, val surfaceSamples: Int, val meanConfidence: Float)

/**
 * Truncated signed distance volume over the scan box. Box frame: origin at the centre of the box
 * base, X = width, Y = depth, Z up, millimetres. Not thread-safe: use from one thread at a time.
 */
interface TsdfVolume {
    /**
     * Fuses [frame]. [cameraToBox]: column-major 4×4 from camera-frame mm (+X right, +Y up,
     * −Z forward) to the box frame. Samples below [minConfidence] (0..1) are ignored.
     */
    fun integrate(frame: DepthFrame, cameraToBox: FloatArray, minConfidence: Float): IntegrateResult

    /** Surface mesh in box-frame mm, sampled every [step] voxels (2 = coarse live preview). */
    fun extract(step: Int, minWeight: Float): Mesh

    /**
     * Silhouette carving: the object's outline in a photo taken with intrinsics [fx]..[cy] (image
     * pixels) from [cameraToBox] (same convention as [integrate]).
     */
    fun carve(mask: ObjectMask, fx: Float, fy: Float, cx: Float, cy: Float, cameraToBox: FloatArray)

    /** Masks carved so far. */
    val carvedViews: Int

    /**
     * Closed, smoothed visual hull (box-frame mm): voxels seen in at least [minSeen] masks and outside
     * the object in at most [maxOutsideFraction] of them.
     */
    fun extractHull(maxOutsideFraction: Float, minSeen: Int): Mesh

    fun save(path: String): Boolean

    /** Frees native memory; the volume can't be used afterwards. */
    fun close()
}

/** Post-processing applied to the final mesh (Settings › Scanning). */
data class CleanupOptions(
    val removeTablePlane: Boolean = true,
    val keepLargestComponent: Boolean = true,
    val watertight: Boolean = true,
    val smoothing: Smoothing = Smoothing.LOW,
)

enum class Smoothing { OFF, LOW, MEDIUM, HIGH }

/**
 * Indexed triangle mesh in millimetres, Z-up, resting on Z = 0, centred on X/Y.
 * [positions] is xyz-interleaved; [indices] are triangle vertex indices (3 per triangle).
 */
class Mesh(val positions: FloatArray, val indices: IntArray) {
    init {
        require(positions.size % 3 == 0) { "positions must be xyz triples" }
        require(indices.size % 3 == 0) { "indices must be triangles" }
    }

    val vertexCount: Int get() = positions.size / 3
    val triangleCount: Int get() = indices.size / 3
}
