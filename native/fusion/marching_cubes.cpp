// ----------------------------------------------------------------------------
// -                        Open3D: www.open3d.org                            -
// ----------------------------------------------------------------------------
// Copyright (c) 2018-2026 www.open3d.org
// SPDX-License-Identifier: MIT
// ----------------------------------------------------------------------------
// Modified by the Facet authors (2026-11-05) from Open3D v0.20.0
// (commit b6c5e196384ad71e75b6e6f9c5da22d046221f1d),
// UniformTSDFVolume::ExtractTriangleMesh in cpp/open3d/pipelines/integration/UniformTSDFVolume.cpp:
//   - Works on a plain TsdfGrid (nx × ny × nz, any aspect) instead of a cubic UniformTSDFVolume.
//   - No Eigen / TBB / geometry::TriangleMesh: positions and indices go to std::vectors.
//   - Edge-to-vertex map keyed by a 64-bit edge id instead of Eigen::Vector4i.
//   - Skips a cell when a corner's weight is below minWeight (Open3D: weight == 0).
//   - Colour interpolation removed (Facet exports geometry only).
// ----------------------------------------------------------------------------
#include "marching_cubes.h"

#include <cmath>
#include <unordered_map>

#include "marching_cubes_tables.h"

namespace facet::fusion {

void extractTriangleMesh(const TsdfGrid &g, float minWeight, std::vector<float> &positions,
                         std::vector<int32_t> &indices) {
    using namespace mc;
    positions.clear();
    indices.clear();
    if (g.nx < 2 || g.ny < 2 || g.nz < 2 || !g.tsdf || !g.weight) return;

    const float half_voxel_length = g.voxel * 0.5f;
    auto index_of = [&](int x, int y, int z) { return (static_cast<size_t>(z) * g.ny + y) * g.nx + x; };
    // Map of "edge id = (x, y, z, direction)" to "global vertex index".
    std::unordered_map<uint64_t, int32_t> edgeindex_to_vertexindex;
    auto edge_id = [&](int x, int y, int z, int dir) {
        return static_cast<uint64_t>(index_of(x, y, z)) * 3u + static_cast<uint64_t>(dir);
    };
    int32_t edge_to_index[12];

    for (int x = 0; x < g.nx - 1; x++) {
        for (int y = 0; y < g.ny - 1; y++) {
            for (int z = 0; z < g.nz - 1; z++) {
                int cube_index = 0;
                float f[8];
                for (int i = 0; i < 8; i++) {
                    const size_t idx = index_of(x + shift[i][0], y + shift[i][1], z + shift[i][2]);
                    if (!(g.weight[idx] >= minWeight) || g.weight[idx] <= 0.f) {
                        cube_index = 0;
                        break;
                    }
                    f[i] = g.tsdf[idx];
                    if (f[i] < 0.0f) cube_index |= (1 << i);
                }
                if (cube_index == 0 || cube_index == 255) continue;

                for (int i = 0; i < 12; i++) {
                    if (!(edge_table[cube_index] & (1 << i))) continue;
                    const int ex = x + edge_shift[i][0];
                    const int ey = y + edge_shift[i][1];
                    const int ez = z + edge_shift[i][2];
                    const int dir = edge_shift[i][3];
                    const uint64_t key = edge_id(ex, ey, ez, dir);
                    auto it = edgeindex_to_vertexindex.find(key);
                    if (it != edgeindex_to_vertexindex.end()) {
                        edge_to_index[i] = it->second;
                        continue;
                    }
                    const int32_t vertex = static_cast<int32_t>(positions.size() / 3);
                    edge_to_index[i] = vertex;
                    edgeindex_to_vertexindex.emplace(key, vertex);
                    float pt[3] = {half_voxel_length + g.voxel * ex, half_voxel_length + g.voxel * ey,
                                   half_voxel_length + g.voxel * ez};
                    const float f0 = std::abs(f[edge_to_vert[i][0]]);
                    const float f1 = std::abs(f[edge_to_vert[i][1]]);
                    pt[dir] += f0 * g.voxel / (f0 + f1);
                    for (int d = 0; d < 3; d++) positions.push_back(pt[d] + g.origin[d]);
                }
                for (int i = 0; tri_table[cube_index][i] != -1; i += 3) {
                    indices.push_back(edge_to_index[tri_table[cube_index][i]]);
                    indices.push_back(edge_to_index[tri_table[cube_index][i + 2]]);
                    indices.push_back(edge_to_index[tri_table[cube_index][i + 1]]);
                }
            }
        }
    }
}

}  // namespace facet::fusion
