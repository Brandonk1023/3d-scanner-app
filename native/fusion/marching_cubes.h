// ----------------------------------------------------------------------------
// -                        Open3D: www.open3d.org                            -
// ----------------------------------------------------------------------------
// Copyright (c) 2018-2026 www.open3d.org
// SPDX-License-Identifier: MIT
// ----------------------------------------------------------------------------
// Modified by the Facet authors (2026-11-05) from Open3D v0.20.0
// (commit b6c5e196384ad71e75b6e6f9c5da22d046221f1d): the interface of
// UniformTSDFVolume::ExtractTriangleMesh (cpp/open3d/pipelines/integration/UniformTSDFVolume.h),
// reduced to a free function over a plain TSDF grid. See marching_cubes.cpp for the changes.
// ----------------------------------------------------------------------------
#pragma once

#include <cstdint>
#include <vector>

namespace facet::fusion {

/** A dense TSDF grid: voxel (i, j, k) at index (k * ny + j) * nx + i, centred at origin + (idx + 0.5) * voxel. */
struct TsdfGrid {
    int nx = 0, ny = 0, nz = 0;
    float voxel = 1.f;
    float origin[3] = {0.f, 0.f, 0.f};
    const float *tsdf = nullptr;    // negative = inside / behind the observed surface
    const float *weight = nullptr;  // cells with any corner below minWeight are skipped
};

/**
 * Marching cubes over the zero level set. Outputs xyz triples (same unit as the grid) and
 * triangle indices; shared edges share vertices, so the mesh is indexed and crack-free.
 */
void extractTriangleMesh(const TsdfGrid &grid, float minWeight, std::vector<float> &positions,
                         std::vector<int32_t> &indices);

}  // namespace facet::fusion
