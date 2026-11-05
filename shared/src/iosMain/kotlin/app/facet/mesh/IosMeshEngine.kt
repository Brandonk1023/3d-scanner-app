package app.facet.mesh

import app.facet.geometry.BoxSize
import app.facet.nativecore.facet_box_diagonal
import app.facet.nativecore.facet_core_version
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

/** [MeshEngine] backed by the C++ core through Kotlin/Native cinterop. */
@OptIn(ExperimentalForeignApi::class)
class IosMeshEngine : MeshEngine {
    override fun coreVersion(): String = facet_core_version()?.toKString() ?: "unknown"

    override fun boxDiagonalMm(box: BoxSize): Double =
        facet_box_diagonal(box.widthMm, box.depthMm, box.heightMm)
}
