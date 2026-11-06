package app.facet.mesh

import app.facet.geometry.BoxSize

/**
 * The shared C++ reconstruction core (native/), behind one interface.
 * Android: JNI (`AndroidMeshEngine`). iOS: Kotlin/Native cinterop (`IosMeshEngine`).
 *
 * TSDF volumes for live fusion, plus the mesh pipeline stages ([ScanPipeline] chains them):
 * cleanup, Manifold repair, meshoptimizer decimation, STL / 3MF writers and Screened Poisson for photo mode.
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

    /**
     * Closed, oriented 2-manifold (Manifold) with the optional flat base / plinth booleans. On
     * failure the result carries the status and the input mesh unchanged.
     */
    fun repair(mesh: Mesh, options: RepairOptions = RepairOptions()): MeshResult

    /**
     * Table removal, largest piece, smoothing and hole filling ([CleanupParams]). On failure the
     * result carries the status and the input mesh unchanged.
     */
    fun cleanup(mesh: Mesh, params: CleanupParams): MeshResult

    /** Decimates to at most [targetTriangles] (meshoptimizer); [maxError] is relative to the mesh extent. */
    fun decimate(mesh: Mesh, targetTriangles: Int, maxError: Float = 0.01f): MeshResult

    /** Binary STL in millimetres. */
    fun writeStl(mesh: Mesh, path: String): PipelineStatus

    /** 3MF (lib3mf), unit = millimetre, one object called [objectName]. */
    fun write3mf(mesh: Mesh, path: String, objectName: String): PipelineStatus

    /**
     * Empty photo-mode point cloud that keeps points inside [box] and at least [floorMarginMm] above
     * its base. Null if it can't be allocated.
     */
    fun createPointCloud(box: BoxSize, floorMarginMm: Double): PointCloud?
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

    /** Full-resolution marching cubes (ported from Open3D) for the final Live-mode mesh; empty if nothing fused. */
    fun extractMarchingCubes(minWeight: Float): Mesh

    fun save(path: String): Boolean

    /** Frees native memory; the volume can't be used afterwards. */
    fun close()
}

/**
 * Cleanup of the final mesh (native `facet_trimesh_cleanup`, docs/04 §4). Millimetres. Built from
 * Settings › Scanning and [app.facet.scan.ScanTuning.Cleanup] by `ScanTuning.Cleanup.params`.
 */
data class CleanupParams(
    /** The table is looked for this far above the mesh's lowest point; 0 = there is no table. */
    val tableSearchMm: Float = 0f,
    /** Cut this far above the table; the cut edge is extended straight down to it. */
    val tableClipMm: Float = 0f,
    /** Upward-facing area a 2 mm height band needs to count as the table. */
    val tableMinAreaMm2: Float = 0f,
    val keepLargest: Boolean = false,
    /** Taubin passes (0 = off). */
    val smoothingIterations: Int = 0,
    /** Close every hole with a flat patch (watertight). */
    val fillHoles: Boolean = false,
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
