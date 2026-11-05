package app.facet.nativecore

/**
 * Raw JNI bindings to the C API in native/include/facet_mesh.h.
 * Don't call this from app code — use the shared `MeshEngine` interface instead.
 */
object FacetCoreJni {
    init {
        System.loadLibrary("facet_jni")
    }

    external fun coreVersion(): String

    external fun boxDiagonal(width: Double, depth: Double, height: Double): Double

    // TSDF volume; handle 0 = failed to create/load.
    external fun volumeCreate(width: Float, depth: Float, height: Float, voxel: Float, truncation: Float): Long
    external fun volumeDestroy(handle: Long)

    /** Returns mean confidence; [counts] receives (updated voxels, surface samples). */
    external fun volumeIntegrate(
        handle: Long,
        depth: ShortArray, width: Int, height: Int,
        confidence: ByteArray?,
        fx: Float, fy: Float, cx: Float, cy: Float,
        camToBox: FloatArray, minConfidence: Float,
        counts: IntArray,
    ): Float

    external fun volumeExtract(handle: Long, step: Int, minWeight: Float): Int
    external fun volumeMeshPositions(handle: Long): FloatArray
    external fun volumeMeshIndices(handle: Long): IntArray
    external fun volumeCarve(
        handle: Long, mask: ByteArray, width: Int, height: Int,
        fx: Float, fy: Float, cx: Float, cy: Float, camToBox: FloatArray,
    )
    external fun volumeCarvedViews(handle: Long): Int
    external fun volumeExtractHull(handle: Long, maxOutsideFraction: Float, minSeen: Int): Int
    external fun volumeSave(handle: Long, path: String): Boolean
    external fun volumeLoad(path: String): Long
}
