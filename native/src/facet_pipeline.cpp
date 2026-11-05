// C API for include/facet_pipeline.h. Nothing C++ (and no exception) crosses this boundary.
#include "facet_pipeline.h"

#include <new>

#include "facet_volume_impl.h"
#include "pipeline/pipeline.h"

using facet::pipeline::PointCloud;
using facet::pipeline::TriMesh;

struct facet_trimesh {
    TriMesh mesh;
};

struct facet_point_cloud {
    PointCloud cloud;
};

extern "C" {

facet_trimesh *facet_trimesh_create(const float *positions, int32_t float_count, const int32_t *indices,
                                    int32_t index_count) {
    if (!positions || !indices || float_count < 0 || index_count < 0 || float_count % 3 || index_count % 3) return nullptr;
    const int32_t vertices = float_count / 3;
    for (int32_t i = 0; i < index_count; ++i) {
        if (indices[i] < 0 || indices[i] >= vertices) return nullptr;
    }
    auto *m = new (std::nothrow) facet_trimesh;
    if (!m) return nullptr;
    m->mesh.positions.assign(positions, positions + float_count);
    m->mesh.indices.assign(indices, indices + index_count);
    return m;
}

void facet_trimesh_destroy(facet_trimesh *mesh) { delete mesh; }

const float *facet_trimesh_positions(const facet_trimesh *mesh, int32_t *float_count) {
    if (float_count) *float_count = static_cast<int32_t>(mesh->mesh.positions.size());
    return mesh->mesh.positions.data();
}

const int32_t *facet_trimesh_indices(const facet_trimesh *mesh, int32_t *index_count) {
    if (index_count) *index_count = static_cast<int32_t>(mesh->mesh.indices.size());
    return mesh->mesh.indices.data();
}

facet_trimesh *facet_volume_extract_marching_cubes(const facet_volume *volume, float min_weight) {
    if (!volume) return nullptr;
    auto *m = new (std::nothrow) facet_trimesh;
    if (!m) return nullptr;
    volume->tsdf->extractMarchingCubes(min_weight, m->mesh.positions, m->mesh.indices);
    if (m->mesh.triangleCount() == 0) {
        delete m;
        return nullptr;
    }
    return m;
}

int32_t facet_trimesh_repair(facet_trimesh *mesh, float base_cut_z, float plinth_height, float plinth_margin) {
    if (!mesh) return FACET_ERR_INVALID;
    return facet::pipeline::repairWithManifold(mesh->mesh, base_cut_z, plinth_height, plinth_margin);
}

int32_t facet_trimesh_hollow(facet_trimesh *mesh, float wall_thickness) {
    if (!mesh || !(wall_thickness > 0.f)) return FACET_ERR_INVALID;
    // TODO(Phase 5): inward offset + Manifold difference.
    return FACET_ERR_UNSUPPORTED;
}

int32_t facet_trimesh_decimate(facet_trimesh *mesh, int32_t target_triangles, float max_error) {
    if (!mesh || target_triangles <= 0) return FACET_ERR_INVALID;
    return facet::pipeline::decimate(mesh->mesh, static_cast<size_t>(target_triangles), max_error);
}

int32_t facet_trimesh_write_stl(const facet_trimesh *mesh, const char *path) {
    if (!mesh || !path) return FACET_ERR_INVALID;
    return facet::pipeline::writeStl(mesh->mesh, path);
}

int32_t facet_trimesh_write_3mf(const facet_trimesh *mesh, const char *path, const char *object_name) {
    if (!mesh || !path) return FACET_ERR_INVALID;
    return facet::pipeline::write3mf(mesh->mesh, path, object_name ? object_name : "");
}

facet_point_cloud *facet_point_cloud_create(float width, float depth, float height, float floor_margin) {
    if (!(width > 0 && depth > 0 && height > 0)) return nullptr;
    auto *c = new (std::nothrow) facet_point_cloud;
    if (!c) return nullptr;
    c->cloud.width = width;
    c->cloud.depth = depth;
    c->cloud.height = height;
    c->cloud.floorMargin = floor_margin;
    return c;
}

void facet_point_cloud_destroy(facet_point_cloud *cloud) { delete cloud; }

int32_t facet_point_cloud_add_depth(facet_point_cloud *cloud, const uint16_t *depth, int32_t width, int32_t height,
                                    int32_t depth_stride, const uint8_t *confidence, int32_t confidence_stride,
                                    float fx, float fy, float cx, float cy, const float cam_to_box[16],
                                    float min_confidence) {
    if (!cloud || !cam_to_box) return 0;
    return static_cast<int32_t>(facet::pipeline::addDepth(cloud->cloud, depth, width, height, depth_stride, confidence,
                                                          confidence_stride, fx, fy, cx, cy, cam_to_box, min_confidence));
}

int32_t facet_point_cloud_size(const facet_point_cloud *cloud) {
    return cloud ? static_cast<int32_t>(cloud->cloud.size()) : 0;
}

const float *facet_point_cloud_positions(const facet_point_cloud *cloud) { return cloud->cloud.positions.data(); }

const float *facet_point_cloud_normals(const facet_point_cloud *cloud) { return cloud->cloud.normals.data(); }

facet_trimesh *facet_poisson_reconstruct(const facet_point_cloud *cloud, int32_t depth, float trim_fraction,
                                         int32_t *status) {
    int32_t s = FACET_ERR_INVALID;
    facet_trimesh *result = nullptr;
    if (cloud) {
        auto *m = new (std::nothrow) facet_trimesh;
        if (!m) {
            s = FACET_ERR_INTERNAL;
        } else {
            s = facet::pipeline::reconstructPoisson(cloud->cloud, depth, trim_fraction, m->mesh);
            if (s == FACET_OK) result = m;
            else delete m;
        }
    }
    if (status) *status = s;
    return result;
}

}  // extern "C"
