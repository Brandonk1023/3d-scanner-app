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
}
