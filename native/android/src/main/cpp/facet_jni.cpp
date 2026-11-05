// JNI bridge: maps app.facet.nativecore.FacetCoreJni's external functions onto the C API.
#include <jni.h>

#include "facet_mesh.h"

extern "C" {

JNIEXPORT jstring JNICALL
Java_app_facet_nativecore_FacetCoreJni_coreVersion(JNIEnv *env, jobject /* this */) {
    return env->NewStringUTF(facet_core_version());
}

JNIEXPORT jdouble JNICALL
Java_app_facet_nativecore_FacetCoreJni_boxDiagonal(JNIEnv * /* env */, jobject /* this */,
                                                   jdouble width, jdouble depth, jdouble height) {
    return facet_box_diagonal(width, depth, height);
}

}  // extern "C"
