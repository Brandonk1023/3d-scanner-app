package app.facet.mesh

import app.facet.geometry.BoxSize

/**
 * The shared C++ reconstruction core (native/), behind one interface.
 * Android: JNI (`AndroidMeshEngine`). iOS: Kotlin/Native cinterop (`IosMeshEngine`).
 *
 * Phase 0 exposes only the smoke-test calls. Coming in Phases 3–4 (docs/04 §4):
 * create a TSDF volume for a box, integrate a depth frame (with confidence + pose + intrinsics),
 * extract the live preview mesh, extract the final mesh with [CleanupOptions], uniform scale, free.
 */
interface MeshEngine {
    /** Version string of the C++ core. */
    fun coreVersion(): String

    /** Box diagonal computed in C++ — a trivial call that proves the native link works. */
    fun boxDiagonalMm(box: BoxSize): Double
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
