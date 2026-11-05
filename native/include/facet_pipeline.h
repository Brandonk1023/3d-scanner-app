/*
 * Facet mesh pipeline — plain C API (second header next to facet_mesh.h).
 *
 * Live mode:  facet_volume_* (TSDF fusion) → facet_volume_extract_marching_cubes → facet_trimesh_repair
 *             (Manifold) → facet_trimesh_decimate (meshoptimizer) → facet_trimesh_write_stl / _3mf.
 * Photo mode: facet_point_cloud_add_depth (posed depth stills) → facet_poisson_reconstruct
 *             (Screened Poisson + density trim) → repair → decimate → write.
 *
 * Units: millimetres. Frame: the scan box frame of facet_mesh.h (origin at the centre of the box
 * base, Z up). Phase 3 wiring: interfaces are final, parameters are not tuned yet.
 */
#ifndef FACET_PIPELINE_H
#define FACET_PIPELINE_H

#include <stdint.h>

#include "facet_mesh.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Status codes returned by the pipeline calls. */
#define FACET_OK 0
#define FACET_ERR_INVALID 1      /* bad arguments */
#define FACET_ERR_EMPTY 2        /* nothing to work with (no triangles / no points) */
#define FACET_ERR_NOT_MANIFOLD 3 /* Manifold could not turn the input into a closed 2-manifold */
#define FACET_ERR_IO 4           /* file could not be written */
#define FACET_ERR_UNSUPPORTED 5  /* not implemented yet */
#define FACET_ERR_INTERNAL 6     /* a library threw */

/* ---- Triangle mesh handle ---------------------------------------------------------------- */

typedef struct facet_trimesh facet_trimesh;

/** Copies xyz positions (float_count = 3 × vertices) and triangle indices. NULL on bad input. */
facet_trimesh *facet_trimesh_create(const float *positions, int32_t float_count, const int32_t *indices,
                                    int32_t index_count);
void facet_trimesh_destroy(facet_trimesh *mesh);
/** Buffers stay valid until the next call that changes the mesh, or destroy. */
const float *facet_trimesh_positions(const facet_trimesh *mesh, int32_t *float_count);
const int32_t *facet_trimesh_indices(const facet_trimesh *mesh, int32_t *index_count);

/** Full-resolution marching cubes (ported from Open3D) of a fused volume. NULL if empty. */
facet_trimesh *facet_volume_extract_marching_cubes(const facet_volume *volume, float min_weight);

/* ---- Repair (Manifold) -------------------------------------------------------------------- */

/**
 * Merges coincident open-edge vertices, then builds a Manifold (closed, oriented 2-manifold) and
 * writes it back. Optional booleans, applied in this order:
 *   base_cut_z  > 0: cut everything below Z = base_cut_z and drop the result to Z = 0 (flat base).
 *   plinth_height > 0: union a box plinth under the model, plinth_margin wider on each side.
 * Returns FACET_OK, or FACET_ERR_NOT_MANIFOLD and leaves the mesh unchanged.
 */
int32_t facet_trimesh_repair(facet_trimesh *mesh, float base_cut_z, float plinth_height, float plinth_margin);

/** Hollowing with a given wall thickness (mm). Not implemented yet: FACET_ERR_UNSUPPORTED. */
int32_t facet_trimesh_hollow(facet_trimesh *mesh, float wall_thickness);

/* ---- Decimation (meshoptimizer) ----------------------------------------------------------- */

/**
 * Simplifies to at most target_triangles (no-op if already below), stopping early once the error
 * would exceed max_error (relative to the mesh extent; 0.01 = 1 %). Unused vertices are dropped.
 */
int32_t facet_trimesh_decimate(facet_trimesh *mesh, int32_t target_triangles, float max_error);

/* ---- Export -------------------------------------------------------------------------------- */

/** Binary STL (millimetres), facet normals from the winding. */
int32_t facet_trimesh_write_stl(const facet_trimesh *mesh, const char *path);

/** 3MF (lib3mf), unit = millimetre, one mesh object called object_name with one build item. */
int32_t facet_trimesh_write_3mf(const facet_trimesh *mesh, const char *path, const char *object_name);

/* ---- Photo mode: oriented point cloud + Screened Poisson ----------------------------------- */

typedef struct facet_point_cloud facet_point_cloud;

/**
 * Empty cloud that keeps only points inside the W × D × H scan box and at least floor_margin
 * above its base (drops the table).
 */
facet_point_cloud *facet_point_cloud_create(float width, float depth, float height, float floor_margin);
void facet_point_cloud_destroy(facet_point_cloud *cloud);

/**
 * Back-projects one depth still (same arguments and conventions as facet_volume_integrate) into
 * box-frame points with normals from neighbouring depth pixels, oriented towards the camera.
 * Returns the number of points added.
 */
int32_t facet_point_cloud_add_depth(facet_point_cloud *cloud, const uint16_t *depth, int32_t width, int32_t height,
                                    int32_t depth_stride, const uint8_t *confidence, int32_t confidence_stride,
                                    float fx, float fy, float cx, float cy, const float cam_to_box[16],
                                    float min_confidence);

int32_t facet_point_cloud_size(const facet_point_cloud *cloud);
/** xyz triples (positions) and unit normals; 3 × size floats each. */
const float *facet_point_cloud_positions(const facet_point_cloud *cloud);
const float *facet_point_cloud_normals(const facet_point_cloud *cloud);

/**
 * Screened Poisson reconstruction (PoissonRecon) at octree depth `depth` (e.g. 8), then trims the
 * lowest-density trim_fraction of vertices (0 = keep all; trimming opens the surface). *status
 * (may be NULL) receives a FACET_* code. Returns NULL on failure.
 */
facet_trimesh *facet_poisson_reconstruct(const facet_point_cloud *cloud, int32_t depth, float trim_fraction,
                                         int32_t *status);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // FACET_PIPELINE_H
