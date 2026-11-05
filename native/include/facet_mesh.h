/*
 * Facet mesh core — plain C API.
 *
 * This is the only header platforms see. Android calls it through JNI (native/android),
 * iOS through Kotlin/Native cinterop (shared/src/nativeInterop/cinterop/facet_mesh.def).
 * Everything behind it is C++; nothing C++ crosses this boundary.
 *
 * Units: millimetres. Coordinates: Z-up.
 *
 * Phase 3: TSDF volume over the scan box, depth integration and surface extraction.
 * Cleanup (table removal, largest component, watertight, smoothing) follows in Phase 4.
 */

#include <stdint.h>
#ifndef FACET_MESH_H
#define FACET_MESH_H

#ifdef __cplusplus
extern "C" {
#endif

/** Version of the C++ core, e.g. "0.1.0". Static storage; never free it. */
const char *facet_core_version(void);

/** Diagonal of a W × D × H box in the same unit as its inputs. Returns -1 for negative sizes. */
double facet_box_diagonal(double width, double depth, double height);

/* ---- TSDF volume (docs/04 §4) ------------------------------------------------------------
 * Box frame: origin at the centre of the box base, X = width, Y = depth, Z up, millimetres.
 */

typedef struct facet_volume facet_volume;

/** New empty volume over a W × D × H box. truncation = SDF band half-width (mm). NULL on failure. */
facet_volume *facet_volume_create(float width, float depth, float height, float voxel, float truncation);

void facet_volume_destroy(facet_volume *volume);

/** Voxel counts along X, Y, Z (out params may be NULL). */
void facet_volume_dims(const facet_volume *volume, int32_t *nx, int32_t *ny, int32_t *nz);

typedef struct {
    int32_t updated;          /* voxels changed by this frame */
    int32_t surface_samples;  /* depth samples near the surface inside the box */
    float mean_confidence;    /* 0..1 over those samples */
} facet_integrate_result;

/**
 * Fuses one depth map (mm along the optical axis, 0 = none; stride in pixels).
 * confidence: 0..255 per pixel, or NULL. Intrinsics in depth pixels. cam_to_box: column-major
 * 4x4 from camera-frame mm (+X right, +Y up, -Z forward) to the box frame.
 */
facet_integrate_result facet_volume_integrate(facet_volume *volume,
                                              const uint16_t *depth, int32_t width, int32_t height, int32_t depth_stride,
                                              const uint8_t *confidence, int32_t confidence_stride,
                                              float fx, float fy, float cx, float cy,
                                              const float cam_to_box[16], float min_confidence);

/**
 * Extracts the surface (sampled every `step` voxels; 1 = full resolution) into the volume's
 * internal buffers and returns the triangle count. Read them with facet_volume_mesh_* until the
 * next extract or destroy.
 */
int32_t facet_volume_extract(facet_volume *volume, int32_t step, float min_weight);
const float *facet_volume_mesh_positions(const facet_volume *volume, int32_t *float_count);
const int32_t *facet_volume_mesh_indices(const facet_volume *volume, int32_t *index_count);

/**
 * Silhouette carving: an object mask (width × height bytes, nonzero = object) from a photo with
 * intrinsics in image pixels and cam_to_box as for facet_volume_integrate.
 */
void facet_volume_carve(facet_volume *volume, const uint8_t *mask, int32_t width, int32_t height,
                        float fx, float fy, float cx, float cy, const float cam_to_box[16]);

/** Number of masks carved so far. */
int32_t facet_volume_carved_views(const facet_volume *volume);

/**
 * Extracts the closed visual-hull mesh into the internal buffers (read with facet_volume_mesh_*)
 * and returns the triangle count: voxels seen by ≥ min_seen masks and outside the object in
 * ≤ max_outside_fraction of them.
 */
int32_t facet_volume_extract_hull(facet_volume *volume, float max_outside_fraction, int32_t min_seen);

/** Persist / restore the whole volume (scan interruption). 1 = ok. */
int32_t facet_volume_save(const facet_volume *volume, const char *path);
facet_volume *facet_volume_load(const char *path);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // FACET_MESH_H
