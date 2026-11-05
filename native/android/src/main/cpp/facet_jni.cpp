// JNI bridge: maps app.facet.nativecore.FacetCoreJni's external functions onto the C API.
#include <jni.h>

#include "facet_mesh.h"
#include "facet_pipeline.h"

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

// ---- TSDF volume. Handles are facet_volume* stored in a jlong. ----

JNIEXPORT jlong JNICALL
Java_app_facet_nativecore_FacetCoreJni_volumeCreate(JNIEnv * /* env */, jobject /* this */,
                                                    jfloat w, jfloat d, jfloat h, jfloat voxel, jfloat trunc) {
    return reinterpret_cast<jlong>(facet_volume_create(w, d, h, voxel, trunc));
}

JNIEXPORT void JNICALL
Java_app_facet_nativecore_FacetCoreJni_volumeDestroy(JNIEnv * /* env */, jobject /* this */, jlong handle) {
    facet_volume_destroy(reinterpret_cast<facet_volume *>(handle));
}

JNIEXPORT jfloat JNICALL
Java_app_facet_nativecore_FacetCoreJni_volumeIntegrate(JNIEnv *env, jobject /* this */, jlong handle,
                                                       jshortArray depth, jint width, jint height,
                                                       jbyteArray confidence,
                                                       jfloat fx, jfloat fy, jfloat cx, jfloat cy,
                                                       jfloatArray camToBox, jfloat minConfidence,
                                                       jintArray counts) {
    auto *volume = reinterpret_cast<facet_volume *>(handle);
    float m[16];
    env->GetFloatArrayRegion(camToBox, 0, 16, m);
    jshort *d = env->GetShortArrayElements(depth, nullptr);
    jbyte *c = confidence ? env->GetByteArrayElements(confidence, nullptr) : nullptr;
    const facet_integrate_result r = facet_volume_integrate(
        volume, reinterpret_cast<const uint16_t *>(d), width, height, width,
        reinterpret_cast<const uint8_t *>(c), width, fx, fy, cx, cy, m, minConfidence);
    if (c) env->ReleaseByteArrayElements(confidence, c, JNI_ABORT);
    env->ReleaseShortArrayElements(depth, d, JNI_ABORT);
    const jint out[2] = {r.updated, r.surface_samples};
    env->SetIntArrayRegion(counts, 0, 2, out);
    return r.mean_confidence;
}

JNIEXPORT jint JNICALL
Java_app_facet_nativecore_FacetCoreJni_volumeExtract(JNIEnv * /* env */, jobject /* this */, jlong handle,
                                                     jint step, jfloat minWeight) {
    return facet_volume_extract(reinterpret_cast<facet_volume *>(handle), step, minWeight);
}

JNIEXPORT jfloatArray JNICALL
Java_app_facet_nativecore_FacetCoreJni_volumeMeshPositions(JNIEnv *env, jobject /* this */, jlong handle) {
    int32_t n = 0;
    const float *p = facet_volume_mesh_positions(reinterpret_cast<facet_volume *>(handle), &n);
    jfloatArray out = env->NewFloatArray(n);
    if (out && n > 0) env->SetFloatArrayRegion(out, 0, n, p);
    return out;
}

JNIEXPORT jintArray JNICALL
Java_app_facet_nativecore_FacetCoreJni_volumeMeshIndices(JNIEnv *env, jobject /* this */, jlong handle) {
    int32_t n = 0;
    const int32_t *p = facet_volume_mesh_indices(reinterpret_cast<facet_volume *>(handle), &n);
    jintArray out = env->NewIntArray(n);
    if (out && n > 0) env->SetIntArrayRegion(out, 0, n, reinterpret_cast<const jint *>(p));
    return out;
}

JNIEXPORT void JNICALL
Java_app_facet_nativecore_FacetCoreJni_volumeCarve(JNIEnv *env, jobject /* this */, jlong handle,
                                                   jbyteArray mask, jint width, jint height,
                                                   jfloat fx, jfloat fy, jfloat cx, jfloat cy, jfloatArray camToBox) {
    float m[16];
    env->GetFloatArrayRegion(camToBox, 0, 16, m);
    jbyte *px = env->GetByteArrayElements(mask, nullptr);
    facet_volume_carve(reinterpret_cast<facet_volume *>(handle), reinterpret_cast<const uint8_t *>(px), width, height,
                       fx, fy, cx, cy, m);
    env->ReleaseByteArrayElements(mask, px, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_app_facet_nativecore_FacetCoreJni_volumeCarvedViews(JNIEnv * /* env */, jobject /* this */, jlong handle) {
    return facet_volume_carved_views(reinterpret_cast<facet_volume *>(handle));
}

JNIEXPORT jint JNICALL
Java_app_facet_nativecore_FacetCoreJni_volumeExtractHull(JNIEnv * /* env */, jobject /* this */, jlong handle,
                                                         jfloat maxOutsideFraction, jint minSeen) {
    return facet_volume_extract_hull(reinterpret_cast<facet_volume *>(handle), maxOutsideFraction, minSeen);
}

JNIEXPORT jboolean JNICALL
Java_app_facet_nativecore_FacetCoreJni_volumeSave(JNIEnv *env, jobject /* this */, jlong handle, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    const int32_t ok = facet_volume_save(reinterpret_cast<facet_volume *>(handle), p);
    env->ReleaseStringUTFChars(path, p);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_app_facet_nativecore_FacetCoreJni_volumeLoad(JNIEnv *env, jobject /* this */, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    facet_volume *v = facet_volume_load(p);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(v);
}

// ---- Mesh pipeline (facet_pipeline.h). Mesh handles are facet_trimesh*, clouds facet_point_cloud*. ----

JNIEXPORT jlong JNICALL
Java_app_facet_nativecore_FacetCoreJni_meshCreate(JNIEnv *env, jobject /* this */, jfloatArray positions,
                                                  jintArray indices) {
    const jsize nf = env->GetArrayLength(positions);
    const jsize ni = env->GetArrayLength(indices);
    jfloat *p = env->GetFloatArrayElements(positions, nullptr);
    jint *i = env->GetIntArrayElements(indices, nullptr);
    facet_trimesh *m = facet_trimesh_create(p, nf, reinterpret_cast<const int32_t *>(i), ni);
    env->ReleaseIntArrayElements(indices, i, JNI_ABORT);
    env->ReleaseFloatArrayElements(positions, p, JNI_ABORT);
    return reinterpret_cast<jlong>(m);
}

JNIEXPORT void JNICALL
Java_app_facet_nativecore_FacetCoreJni_meshDestroy(JNIEnv * /* env */, jobject /* this */, jlong mesh) {
    facet_trimesh_destroy(reinterpret_cast<facet_trimesh *>(mesh));
}

JNIEXPORT jfloatArray JNICALL
Java_app_facet_nativecore_FacetCoreJni_meshPositions(JNIEnv *env, jobject /* this */, jlong mesh) {
    int32_t n = 0;
    const float *p = facet_trimesh_positions(reinterpret_cast<facet_trimesh *>(mesh), &n);
    jfloatArray out = env->NewFloatArray(n);
    if (out && n > 0) env->SetFloatArrayRegion(out, 0, n, p);
    return out;
}

JNIEXPORT jintArray JNICALL
Java_app_facet_nativecore_FacetCoreJni_meshIndices(JNIEnv *env, jobject /* this */, jlong mesh) {
    int32_t n = 0;
    const int32_t *p = facet_trimesh_indices(reinterpret_cast<facet_trimesh *>(mesh), &n);
    jintArray out = env->NewIntArray(n);
    if (out && n > 0) env->SetIntArrayRegion(out, 0, n, reinterpret_cast<const jint *>(p));
    return out;
}

JNIEXPORT jlong JNICALL
Java_app_facet_nativecore_FacetCoreJni_volumeExtractMarchingCubes(JNIEnv * /* env */, jobject /* this */,
                                                                   jlong volume, jfloat minWeight) {
    return reinterpret_cast<jlong>(
        facet_volume_extract_marching_cubes(reinterpret_cast<facet_volume *>(volume), minWeight));
}

JNIEXPORT jint JNICALL
Java_app_facet_nativecore_FacetCoreJni_meshRepair(JNIEnv * /* env */, jobject /* this */, jlong mesh,
                                                  jfloat baseCutZ, jfloat plinthHeight, jfloat plinthMargin) {
    return facet_trimesh_repair(reinterpret_cast<facet_trimesh *>(mesh), baseCutZ, plinthHeight, plinthMargin);
}

JNIEXPORT jint JNICALL
Java_app_facet_nativecore_FacetCoreJni_meshDecimate(JNIEnv * /* env */, jobject /* this */, jlong mesh,
                                                    jint targetTriangles, jfloat maxError) {
    return facet_trimesh_decimate(reinterpret_cast<facet_trimesh *>(mesh), targetTriangles, maxError);
}

JNIEXPORT jint JNICALL
Java_app_facet_nativecore_FacetCoreJni_meshWriteStl(JNIEnv *env, jobject /* this */, jlong mesh, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    const int32_t status = facet_trimesh_write_stl(reinterpret_cast<facet_trimesh *>(mesh), p);
    env->ReleaseStringUTFChars(path, p);
    return status;
}

JNIEXPORT jint JNICALL
Java_app_facet_nativecore_FacetCoreJni_meshWrite3mf(JNIEnv *env, jobject /* this */, jlong mesh, jstring path,
                                                    jstring name) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    const char *n = env->GetStringUTFChars(name, nullptr);
    const int32_t status = facet_trimesh_write_3mf(reinterpret_cast<facet_trimesh *>(mesh), p, n);
    env->ReleaseStringUTFChars(name, n);
    env->ReleaseStringUTFChars(path, p);
    return status;
}

JNIEXPORT jlong JNICALL
Java_app_facet_nativecore_FacetCoreJni_cloudCreate(JNIEnv * /* env */, jobject /* this */, jfloat w, jfloat d,
                                                   jfloat h, jfloat floorMargin) {
    return reinterpret_cast<jlong>(facet_point_cloud_create(w, d, h, floorMargin));
}

JNIEXPORT void JNICALL
Java_app_facet_nativecore_FacetCoreJni_cloudDestroy(JNIEnv * /* env */, jobject /* this */, jlong cloud) {
    facet_point_cloud_destroy(reinterpret_cast<facet_point_cloud *>(cloud));
}

JNIEXPORT jint JNICALL
Java_app_facet_nativecore_FacetCoreJni_cloudAddDepth(JNIEnv *env, jobject /* this */, jlong cloud,
                                                     jshortArray depth, jint width, jint height,
                                                     jbyteArray confidence,
                                                     jfloat fx, jfloat fy, jfloat cx, jfloat cy,
                                                     jfloatArray camToBox, jfloat minConfidence) {
    float m[16];
    env->GetFloatArrayRegion(camToBox, 0, 16, m);
    jshort *d = env->GetShortArrayElements(depth, nullptr);
    jbyte *c = confidence ? env->GetByteArrayElements(confidence, nullptr) : nullptr;
    const int32_t added = facet_point_cloud_add_depth(
        reinterpret_cast<facet_point_cloud *>(cloud), reinterpret_cast<const uint16_t *>(d), width, height, width,
        reinterpret_cast<const uint8_t *>(c), width, fx, fy, cx, cy, m, minConfidence);
    if (c) env->ReleaseByteArrayElements(confidence, c, JNI_ABORT);
    env->ReleaseShortArrayElements(depth, d, JNI_ABORT);
    return added;
}

JNIEXPORT jint JNICALL
Java_app_facet_nativecore_FacetCoreJni_cloudSize(JNIEnv * /* env */, jobject /* this */, jlong cloud) {
    return facet_point_cloud_size(reinterpret_cast<facet_point_cloud *>(cloud));
}

JNIEXPORT jlong JNICALL
Java_app_facet_nativecore_FacetCoreJni_poissonReconstruct(JNIEnv *env, jobject /* this */, jlong cloud,
                                                          jint depth, jfloat trimFraction, jintArray status) {
    int32_t s = 0;
    facet_trimesh *m = facet_poisson_reconstruct(reinterpret_cast<facet_point_cloud *>(cloud), depth, trimFraction, &s);
    const jint out = s;
    env->SetIntArrayRegion(status, 0, 1, &out);
    return reinterpret_cast<jlong>(m);
}

}  // extern "C"
