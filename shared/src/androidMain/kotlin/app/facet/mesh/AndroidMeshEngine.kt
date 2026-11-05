package app.facet.mesh

import app.facet.geometry.BoxSize
import app.facet.nativecore.FacetCoreJni

/** [MeshEngine] backed by the C++ core through JNI (native/android). */
class AndroidMeshEngine : MeshEngine {
    override fun coreVersion(): String = FacetCoreJni.coreVersion()

    override fun boxDiagonalMm(box: BoxSize): Double =
        FacetCoreJni.boxDiagonal(box.widthMm, box.depthMm, box.heightMm)
}
