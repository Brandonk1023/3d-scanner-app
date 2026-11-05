// Pipeline tests: marching cubes → Manifold → meshoptimizer → STL / 3MF, and point cloud → Poisson.
#include "facet_pipeline.h"

#include <cmath>
#include <cstdio>
#include <vector>

#include "test_util.h"

using namespace testutil;

namespace {

const V3 kCenter{0, 0, 60};
constexpr double kRadius = 40;

struct MeshView {
    const float *pos;
    const int32_t *idx;
    int32_t floats, indices;
    explicit MeshView(const facet_trimesh *m) {
        pos = facet_trimesh_positions(m, &floats);
        idx = facet_trimesh_indices(m, &indices);
    }
    int32_t triangles() const { return indices / 3; }
    V3 vertex(int32_t i) const { return {pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]}; }
    double signedVolume() const {
        double v = 0;
        for (int32_t t = 0; t < triangles(); ++t) {
            const V3 a = vertex(idx[t * 3]), b = vertex(idx[t * 3 + 1]), c = vertex(idx[t * 3 + 2]);
            v += a.dot(b.cross(c)) / 6.0;
        }
        return v;
    }
    /** Vertices further than tol from the test sphere. */
    int offSphere(double tol) const {
        int off = 0;
        for (int32_t i = 0; i < floats / 3; ++i)
            if (std::fabs((vertex(i) - kCenter).len() - kRadius) > tol) ++off;
        return off;
    }
    double minZ() const {
        double z = 1e9;
        for (int32_t i = 0; i < floats / 3; ++i) z = std::min(z, static_cast<double>(pos[i * 3 + 2]));
        return z;
    }
    double maxZ() const {
        double z = -1e9;
        for (int32_t i = 0; i < floats / 3; ++i) z = std::max(z, static_cast<double>(pos[i * 3 + 2]));
        return z;
    }
};

/** Calls f(depth, m) for 36 views around the sphere. */
template <class F>
void forEachView(F f) {
    for (double elevation : {15.0, 45.0, 75.0}) {
        for (int az = 0; az < 360; az += 30) {
            const double e = elevation * M_PI / 180, a = az * M_PI / 180;
            const V3 eye = kCenter + V3{std::cos(e) * std::cos(a), std::cos(e) * std::sin(a), std::sin(e)} * 300;
            float m[16];
            V3 right, up, back;
            lookAt(eye, kCenter, m, right, up, back);
            f(renderSphere(eye, right, up, back, kCenter, kRadius), m);
        }
    }
}

// Under-side views too, so the fused sphere is closed for marching cubes.
template <class F>
void forEachViewAllAround(F f) {
    forEachView(f);
    for (int az = 0; az < 360; az += 45) {
        const double e = -45 * M_PI / 180, a = az * M_PI / 180;
        const V3 eye = kCenter + V3{std::cos(e) * std::cos(a), std::cos(e) * std::sin(a), std::sin(e)} * 300;
        float m[16];
        V3 right, up, back;
        lookAt(eye, kCenter, m, right, up, back);
        f(renderSphere(eye, right, up, back, kCenter, kRadius), m);
    }
}

long fileSize(const char *path) {
    FILE *f = std::fopen(path, "rb");
    if (!f) return -1;
    std::fseek(f, 0, SEEK_END);
    const long n = std::ftell(f);
    std::fclose(f);
    return n;
}

void testLivePipeline() {
    // Volume over a box that encloses the whole sphere (base at Z = 0, sphere centre at Z = 60).
    facet_volume *vol = facet_volume_create(120, 120, 120, 2.f, 8.f);
    EXPECT(vol != nullptr);
    if (!vol) return;
    forEachViewAllAround([&](const std::vector<uint16_t> &depth, const float m[16]) {
        facet_volume_integrate(vol, depth.data(), W, H, W, nullptr, 0, FX, FY, CX, CY, m, 0.f);
    });

    facet_trimesh *mesh = facet_volume_extract_marching_cubes(vol, 0.5f);
    EXPECT(mesh != nullptr);
    if (!mesh) {
        facet_volume_destroy(vol);
        return;
    }
    {
        MeshView v(mesh);
        EXPECT(v.triangles() > 1000);
        EXPECT(v.offSphere(2.0) == 0);
        const double expected = 4.0 / 3.0 * M_PI * kRadius * kRadius * kRadius;
        // Positive = counter-clockwise outward winding.
        EXPECT(std::fabs(v.signedVolume() - expected) < 0.05 * expected);
        if (std::fabs(v.signedVolume() - expected) >= 0.05 * expected)
            std::fprintf(stderr, "  marching cubes volume %.0f, expected %.0f\n", v.signedVolume(), expected);
    }

    // Manifold: a closed marching-cubes sphere is already a valid manifold.
    EXPECT(facet_trimesh_repair(mesh, 0.f, 0.f, 0.f) == FACET_OK);
    const int32_t repairedTris = MeshView(mesh).triangles();
    EXPECT(repairedTris > 1000);

    // meshoptimizer: down to 1500 triangles, still on the sphere.
    EXPECT(facet_trimesh_decimate(mesh, 1500, 0.02f) == FACET_OK);
    {
        MeshView v(mesh);
        EXPECT(v.triangles() <= 1500 && v.triangles() > 200);
        EXPECT(v.offSphere(3.0) == 0);
    }
    // Decimated spheres stay closed: repair again for the booleans below.
    EXPECT(facet_trimesh_repair(mesh, 0.f, 0.f, 0.f) == FACET_OK);

    // Export.
    const int32_t tris = MeshView(mesh).triangles();
    EXPECT(facet_trimesh_write_stl(mesh, "facet_test.stl") == FACET_OK);
    EXPECT(fileSize("facet_test.stl") == 84 + 50L * tris);
    EXPECT(facet_trimesh_write_3mf(mesh, "facet_test.3mf", "sphere") == FACET_OK);
    {
        FILE *f = std::fopen("facet_test.3mf", "rb");
        char magic[2] = {0, 0};
        if (f) {
            EXPECT(std::fread(magic, 1, 2, f) == 2);
            std::fclose(f);
        }
        EXPECT(magic[0] == 'P' && magic[1] == 'K');  // a ZIP package
    }
    std::remove("facet_test.stl");
    std::remove("facet_test.3mf");

    // Flat base: cut at Z = 40 and drop to Z = 0 → spans 0..60.
    EXPECT(facet_trimesh_repair(mesh, 40.f, 0.f, 0.f) == FACET_OK);
    {
        MeshView v(mesh);
        EXPECT(std::fabs(v.minZ()) < 1e-3);
        EXPECT(std::fabs(v.maxZ() - 60.0) < 2.0);
    }
    // Plinth 3 mm under it: still starts at Z = 0, 3 mm taller.
    EXPECT(facet_trimesh_repair(mesh, 0.f, 3.f, 2.f) == FACET_OK);
    {
        MeshView v(mesh);
        EXPECT(std::fabs(v.minZ()) < 1e-3);
        EXPECT(std::fabs(v.maxZ() - 63.0) < 2.0);
    }

    EXPECT(facet_trimesh_hollow(mesh, 2.f) == FACET_ERR_UNSUPPORTED);
    facet_trimesh_destroy(mesh);
    facet_volume_destroy(vol);
}

void testOpenMeshIsRejected() {
    // A single triangle is not a closed manifold.
    const float p[] = {0, 0, 0, 10, 0, 0, 0, 10, 0};
    const int32_t i[] = {0, 1, 2};
    facet_trimesh *m = facet_trimesh_create(p, 9, i, 3);
    EXPECT(m != nullptr);
    EXPECT(facet_trimesh_repair(m, 0.f, 0.f, 0.f) == FACET_ERR_NOT_MANIFOLD);
    EXPECT(MeshView(m).triangles() == 1);  // unchanged
    facet_trimesh_destroy(m);

    const int32_t bad[] = {0, 1, 3};
    EXPECT(facet_trimesh_create(p, 9, bad, 3) == nullptr);
    EXPECT(facet_trimesh_create(p, 8, i, 3) == nullptr);
}

void testPhotoPipeline() {
    facet_point_cloud *cloud = facet_point_cloud_create(120, 120, 120, 0.f);
    EXPECT(cloud != nullptr);
    if (!cloud) return;
    forEachViewAllAround([&](const std::vector<uint16_t> &depth, const float m[16]) {
        facet_point_cloud_add_depth(cloud, depth.data(), W, H, W, nullptr, 0, FX, FY, CX, CY, m, 0.f);
    });
    const int32_t n = facet_point_cloud_size(cloud);
    EXPECT(n > 5000);
    // Points on the sphere with outward normals.
    const float *p = facet_point_cloud_positions(cloud);
    const float *nn = facet_point_cloud_normals(cloud);
    int off = 0, inward = 0;
    for (int32_t i = 0; i < n; ++i) {
        const V3 q{p[i * 3], p[i * 3 + 1], p[i * 3 + 2]};
        const V3 nv{nn[i * 3], nn[i * 3 + 1], nn[i * 3 + 2]};
        if (std::fabs((q - kCenter).len() - kRadius) > 1.5) ++off;
        if (nv.dot(q - kCenter) <= 0) ++inward;
    }
    EXPECT(off < n / 100);
    EXPECT(inward < n / 100);

    int32_t status = -1;
    facet_trimesh *mesh = facet_poisson_reconstruct(cloud, 7, 0.f, &status);
    EXPECT(status == FACET_OK);
    EXPECT(mesh != nullptr);
    if (mesh) {
        MeshView v(mesh);
        EXPECT(v.triangles() > 1000);
        EXPECT(v.offSphere(3.0) < v.floats / 3 / 100);
        const double expected = 4.0 / 3.0 * M_PI * kRadius * kRadius * kRadius;
        EXPECT(std::fabs(v.signedVolume() - expected) < 0.08 * expected);
        if (std::fabs(v.signedVolume() - expected) >= 0.08 * expected)
            std::fprintf(stderr, "  poisson volume %.0f, expected %.0f\n", v.signedVolume(), expected);
        EXPECT(facet_trimesh_repair(mesh, 0.f, 0.f, 0.f) == FACET_OK);
        facet_trimesh_destroy(mesh);
    }

    // Trimming removes some triangles.
    facet_trimesh *trimmed = facet_poisson_reconstruct(cloud, 7, 0.1f, &status);
    EXPECT(status == FACET_OK && trimmed != nullptr);
    if (trimmed) facet_trimesh_destroy(trimmed);

    facet_point_cloud_destroy(cloud);

    facet_point_cloud *empty = facet_point_cloud_create(10, 10, 10, 0.f);
    EXPECT(facet_poisson_reconstruct(empty, 7, 0.f, &status) == nullptr);
    EXPECT(status == FACET_ERR_EMPTY);
    facet_point_cloud_destroy(empty);
}

}  // namespace

void runPipelineTests() {
    testLivePipeline();
    testOpenMeshIsRejected();
    testPhotoPipeline();
}
