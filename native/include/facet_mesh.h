/*
 * Facet mesh core — plain C API.
 *
 * This is the only header platforms see. Android calls it through JNI (native/android),
 * iOS through Kotlin/Native cinterop (shared/src/nativeInterop/cinterop/facet_mesh.def).
 * Everything behind it is C++; nothing C++ crosses this boundary.
 *
 * Units: millimetres. Coordinates: Z-up.
 *
 * Phase 0 exposes only smoke-test functions. TSDF volume, depth integration, marching cubes
 * and cleanup are added in Phases 3–4 (see design/docs/04-scan-experience.md §4).
 */
#ifndef FACET_MESH_H
#define FACET_MESH_H

#ifdef __cplusplus
extern "C" {
#endif

/** Version of the C++ core, e.g. "0.1.0". Static storage; never free it. */
const char *facet_core_version(void);

/** Diagonal of a W × D × H box in the same unit as its inputs. Returns -1 for negative sizes. */
double facet_box_diagonal(double width, double depth, double height);

#ifdef __cplusplus
}  // extern "C"
#endif

#endif  // FACET_MESH_H
