#include "facet_mesh.h"

#include "facet_volume_impl.h"

#include <new>
#include <vector>

#include <cmath>

namespace facet {

constexpr const char *kVersion = "0.3.0";

double boxDiagonal(double w, double d, double h) {
    if (w < 0.0 || d < 0.0 || h < 0.0) return -1.0;
    return std::sqrt(w * w + d * d + h * h);
}

}  // namespace facet

extern "C" {

const char *facet_core_version(void) { return facet::kVersion; }

double facet_box_diagonal(double width, double depth, double height) {
    return facet::boxDiagonal(width, depth, height);
}

facet_volume *facet_volume_create(float width, float depth, float height, float voxel, float truncation) {
    if (!(width > 0 && depth > 0 && height > 0 && voxel > 0 && truncation > 0)) return nullptr;
    // Guard against absurd sizes (box limits × min voxel stay far below this).
    const double cells = (width / voxel) * (depth / voxel) * (height / voxel);
    if (cells > 64.0e6) return nullptr;
    auto *tsdf = new (std::nothrow) facet::TsdfVolume(width, depth, height, voxel, truncation);
    if (!tsdf) return nullptr;
    return new (std::nothrow) facet_volume{tsdf, {}, {}};
}

void facet_volume_destroy(facet_volume *volume) {
    if (!volume) return;
    delete volume->tsdf;
    delete volume;
}

void facet_volume_dims(const facet_volume *volume, int32_t *nx, int32_t *ny, int32_t *nz) {
    if (nx) *nx = volume->tsdf->nx();
    if (ny) *ny = volume->tsdf->ny();
    if (nz) *nz = volume->tsdf->nz();
}

facet_integrate_result facet_volume_integrate(facet_volume *volume,
                                              const uint16_t *depth, int32_t width, int32_t height, int32_t depth_stride,
                                              const uint8_t *confidence, int32_t confidence_stride,
                                              float fx, float fy, float cx, float cy,
                                              const float cam_to_box[16], float min_confidence) {
    const auto r = volume->tsdf->integrate(depth, width, height, depth_stride, confidence, confidence_stride,
                                           fx, fy, cx, cy, cam_to_box, min_confidence);
    return facet_integrate_result{r.updated, r.surfaceSamples, r.meanConfidence};
}

int32_t facet_volume_extract(facet_volume *volume, int32_t step, float min_weight) {
    volume->tsdf->extract(step, min_weight, volume->positions, volume->indices);
    return static_cast<int32_t>(volume->indices.size() / 3);
}

const float *facet_volume_mesh_positions(const facet_volume *volume, int32_t *float_count) {
    if (float_count) *float_count = static_cast<int32_t>(volume->positions.size());
    return volume->positions.data();
}

const int32_t *facet_volume_mesh_indices(const facet_volume *volume, int32_t *index_count) {
    if (index_count) *index_count = static_cast<int32_t>(volume->indices.size());
    return volume->indices.data();
}

void facet_volume_carve(facet_volume *volume, const uint8_t *mask, int32_t width, int32_t height,
                        float fx, float fy, float cx, float cy, const float cam_to_box[16]) {
    volume->tsdf->carve(mask, width, height, fx, fy, cx, cy, cam_to_box);
}

int32_t facet_volume_carved_views(const facet_volume *volume) { return volume->tsdf->carvedViews(); }

int32_t facet_volume_extract_hull(facet_volume *volume, float max_outside_fraction, int32_t min_seen) {
    volume->tsdf->extractHull(max_outside_fraction, min_seen, volume->positions, volume->indices);
    return static_cast<int32_t>(volume->indices.size() / 3);
}

int32_t facet_volume_save(const facet_volume *volume, const char *path) {
    return volume->tsdf->save(path) ? 1 : 0;
}

facet_volume *facet_volume_load(const char *path) {
    facet::TsdfVolume *tsdf = facet::TsdfVolume::load(path);
    if (!tsdf) return nullptr;
    return new (std::nothrow) facet_volume{tsdf, {}, {}};
}

}  // extern "C"
