// Cleanup tests: table removal, largest piece, smoothing and hole filling (facet_trimesh_cleanup).
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

#include "facet_mesh.h"
#include "facet_pipeline.h"
#include "test_util.h"

using namespace testutil;

namespace {

// A cylinder standing on a table. The table sits a little above the volume floor, as it does when
// the placed plane is a few mm off.
constexpr double kTableZ = 4, kRadius = 30, kHeight = 50;

/** Depth map of the cylinder + table from one camera. */
std::vector<uint16_t> renderScene(V3 eye, V3 right, V3 up, V3 back) {
    std::vector<uint16_t> depth(W * H, 0);
    for (int v = 0; v < H; ++v) {
        for (int u = 0; u < W; ++u) {
            const double xc = (u + 0.5 - CX) / FX, yc = -(v + 0.5 - CY) / FY;
            const V3 d = right * xc + up * yc - back;
            double best = 1e18;
            // Table plane.
            if (d.z < 0) {
                const double t = (kTableZ - eye.z) / d.z;
                const V3 p = eye + d * t;
                if (t > 0 && std::fabs(p.x) < 400 && std::fabs(p.y) < 400) best = std::min(best, t);
            }
            // Side wall.
            const double a = d.x * d.x + d.y * d.y, b = 2 * (eye.x * d.x + eye.y * d.y),
                         c = eye.x * eye.x + eye.y * eye.y - kRadius * kRadius;
            const double disc = b * b - 4 * a * c;
            if (a > 0 && disc >= 0) {
                const double t = (-b - std::sqrt(disc)) / (2 * a);
                const double z = eye.z + d.z * t;
                if (t > 0 && z >= kTableZ && z <= kTableZ + kHeight) best = std::min(best, t);
            }
            // Top cap.
            if (d.z < 0) {
                const double t = (kTableZ + kHeight - eye.z) / d.z;
                const V3 p = eye + d * t;
                if (t > 0 && p.x * p.x + p.y * p.y <= kRadius * kRadius) best = std::min(best, t);
            }
            if (best < 1e17) depth[v * W + u] = static_cast<uint16_t>(std::lround(best));
        }
    }
    return depth;
}

struct Stats {
    double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9, minZ = 1e9, maxZ = -1e9, volume = 0;
    int32_t triangles = 0;
};

Stats stats(const facet_trimesh *m) {
    int32_t fc = 0, ic = 0;
    const float *p = facet_trimesh_positions(m, &fc);
    const int32_t *idx = facet_trimesh_indices(m, &ic);
    Stats s;
    for (int32_t i = 0; i < fc / 3; ++i) {
        s.minX = std::min(s.minX, double(p[i * 3])), s.maxX = std::max(s.maxX, double(p[i * 3]));
        s.minY = std::min(s.minY, double(p[i * 3 + 1])), s.maxY = std::max(s.maxY, double(p[i * 3 + 1]));
        s.minZ = std::min(s.minZ, double(p[i * 3 + 2])), s.maxZ = std::max(s.maxZ, double(p[i * 3 + 2]));
    }
    for (int32_t t = 0; t < ic / 3; ++t) {
        const float *a = p + idx[t * 3] * 3, *b = p + idx[t * 3 + 1] * 3, *c = p + idx[t * 3 + 2] * 3;
        const V3 A{a[0], a[1], a[2]}, B{b[0], b[1], b[2]}, C{c[0], c[1], c[2]};
        s.volume += A.dot(B.cross(C)) / 6.0;
    }
    s.triangles = ic / 3;
    return s;
}

facet_cleanup_options defaultOptions() {
    facet_cleanup_options o{};
    o.table_search_mm = 30;
    o.table_clip_mm = 6;
    o.table_min_area_mm2 = 400;
    o.keep_largest = 1;
    o.smoothing_iterations = 3;
    o.fill_holes = 1;
    return o;
}

void testCylinderOnTable() {
    facet_volume *vol = facet_volume_create(140, 140, 80, 2.f, 8.f);
    EXPECT(vol != nullptr);
    if (!vol) return;
    // Views from above the table only, like a real scan.
    for (double elevation : {20.0, 45.0, 70.0}) {
        for (int az = 0; az < 360; az += 30) {
            const double e = elevation * M_PI / 180, a = az * M_PI / 180;
            const V3 target{0, 0, kTableZ + kHeight / 2};
            const V3 eye = target + V3{std::cos(e) * std::cos(a), std::cos(e) * std::sin(a), std::sin(e)} * 300;
            float m[16];
            V3 right, up, back;
            lookAt(eye, target, m, right, up, back);
            const auto depth = renderScene(eye, right, up, back);
            facet_volume_integrate(vol, depth.data(), W, H, W, nullptr, 0, FX, FY, CX, CY, m, 0.f);
        }
    }
    EXPECT(facet_volume_extract(vol, 1, 0.5f) > 0);
    int32_t fc = 0, ic = 0;
    const float *pos = facet_volume_mesh_positions(vol, &fc);
    const int32_t *idx = facet_volume_mesh_indices(vol, &ic);
    facet_trimesh *mesh = facet_trimesh_create(pos, fc, idx, ic);
    facet_volume_destroy(vol);
    EXPECT(mesh != nullptr);
    if (!mesh) return;

    const Stats raw = stats(mesh);
    EXPECT(raw.maxX - raw.minX > 100);  // the table spans the volume
    EXPECT(facet_trimesh_open_edges(mesh) > 0);

    const facet_cleanup_options o = defaultOptions();
    EXPECT(facet_trimesh_cleanup(mesh, &o) == FACET_OK);
    const Stats s = stats(mesh);
    std::printf("  cylinder: x %.1f..%.1f y %.1f..%.1f z %.1f..%.1f vol %.0f, open edges %d\n", s.minX, s.maxX,
                s.minY, s.maxY, s.minZ, s.maxZ, s.volume, facet_trimesh_open_edges(mesh));
    EXPECT(facet_trimesh_open_edges(mesh) == 0);
    EXPECT(std::fabs((s.maxX - s.minX) - 2 * kRadius) < 4);
    EXPECT(std::fabs((s.maxY - s.minY) - 2 * kRadius) < 4);
    EXPECT(std::fabs(s.minZ - kTableZ) < 2);
    EXPECT(std::fabs(s.maxZ - (kTableZ + kHeight)) < 3);
    const double expected = M_PI * kRadius * kRadius * kHeight;
    EXPECT(std::fabs(s.volume - expected) < 0.08 * expected);
    EXPECT(facet_trimesh_repair(mesh, 0.f, 0.f, 0.f) == FACET_OK);
    facet_trimesh_destroy(mesh);
}

/** Axis-aligned box 0..s, outward winding; `top` false leaves the top face open. */
void addBox(std::vector<float> &p, std::vector<int32_t> &idx, float s, float x0, bool top) {
    const int32_t base = static_cast<int32_t>(p.size() / 3);
    for (int i = 0; i < 8; ++i) {
        p.push_back(x0 + ((i & 1) ? s : 0));
        p.push_back((i & 2) ? s : 0);
        p.push_back((i & 4) ? s : 0);
    }
    const int32_t faces[6][4] = {{0, 2, 3, 1}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 6, 7, 3}, {0, 4, 6, 2}, {1, 3, 7, 5}};
    for (int f = 0; f < 6; ++f) {
        if (!top && f == 1) continue;
        const auto &q = faces[f];
        idx.insert(idx.end(), {base + q[0], base + q[1], base + q[2], base + q[0], base + q[2], base + q[3]});
    }
}

void testFillAndLargestPiece() {
    std::vector<float> p;
    std::vector<int32_t> idx;
    addBox(p, idx, 20, 0, false);  // open-top box
    addBox(p, idx, 2, 50, true);   // a small separate crumb
    facet_trimesh *mesh = facet_trimesh_create(p.data(), int32_t(p.size()), idx.data(), int32_t(idx.size()));
    EXPECT(facet_trimesh_open_edges(mesh) == 4);

    facet_cleanup_options o{};
    o.keep_largest = 1;
    o.fill_holes = 1;
    EXPECT(facet_trimesh_cleanup(mesh, &o) == FACET_OK);
    const Stats s = stats(mesh);
    EXPECT(facet_trimesh_open_edges(mesh) == 0);
    EXPECT(s.maxX < 21);  // crumb gone
    EXPECT(std::fabs(s.volume - 8000) < 1);
    EXPECT(s.triangles == 12);
    EXPECT(facet_trimesh_repair(mesh, 0.f, 0.f, 0.f) == FACET_OK);
    facet_trimesh_destroy(mesh);

    // Without the options nothing but welding happens.
    p.clear();
    idx.clear();
    addBox(p, idx, 20, 0, false);
    mesh = facet_trimesh_create(p.data(), int32_t(p.size()), idx.data(), int32_t(idx.size()));
    const facet_cleanup_options none{};
    EXPECT(facet_trimesh_cleanup(mesh, &none) == FACET_OK);
    EXPECT(facet_trimesh_open_edges(mesh) == 4);
    EXPECT(stats(mesh).triangles == 10);
    facet_trimesh_destroy(mesh);

    EXPECT(facet_trimesh_cleanup(nullptr, &none) == FACET_ERR_INVALID);
}

/** A non-convex (L-shaped) hole must be ear-clipped, not fanned across the notch. */
void testConcaveHole() {
    // L-shaped prism, open at the top: floor + 6 walls. Outline counter-clockwise from above.
    const float outline[6][2] = {{0, 0}, {30, 0}, {30, 10}, {10, 10}, {10, 30}, {0, 30}};
    std::vector<float> p;
    std::vector<int32_t> idx;
    for (int z = 0; z < 2; ++z)
        for (const auto &o : outline) p.insert(p.end(), {o[0], o[1], z * 10.f});
    // Floor, facing down (clockwise from above): fan from vertex 0 is valid for this L.
    for (int i = 1; i + 1 < 6; ++i) idx.insert(idx.end(), {0, i + 1, i});
    for (int i = 0; i < 6; ++i) {
        const int j = (i + 1) % 6;
        idx.insert(idx.end(), {i, j, j + 6, i, j + 6, i + 6});
    }
    facet_trimesh *mesh = facet_trimesh_create(p.data(), int32_t(p.size()), idx.data(), int32_t(idx.size()));
    facet_cleanup_options o{};
    o.fill_holes = 1;
    EXPECT(facet_trimesh_cleanup(mesh, &o) == FACET_OK);
    const Stats s = stats(mesh);
    EXPECT(facet_trimesh_open_edges(mesh) == 0);
    EXPECT(std::fabs(s.volume - 5000) < 1);  // (30·30 − 20·20) · 10
    EXPECT(s.maxZ < 10.01);                  // flat patch, no centre vertex poking up
    facet_trimesh_destroy(mesh);
}

/** Smoothing removes noise without shrinking the shape (Taubin). */
void testSmoothingKeepsSize() {
    // Noisy closed sphere from a UV grid.
    std::vector<float> p;
    std::vector<int32_t> idx;
    const int rings = 40, segs = 80;
    const double r = 40;
    uint64_t seed = 1;
    p.insert(p.end(), {0, 0, float(r)});
    for (int i = 1; i < rings; ++i) {
        const double th = M_PI * i / rings;
        for (int j = 0; j < segs; ++j) {
            const double ph = 2 * M_PI * j / segs;
            seed = seed * 6364136223846793005ull + 1442695040888963407ull;  // LCG: per-vertex noise
            const double n = 1.0 + 0.02 * (double(seed >> 11) / double(1ull << 53) * 2 - 1);  // ±2 %
            p.insert(p.end(), {float(r * n * std::sin(th) * std::cos(ph)), float(r * n * std::sin(th) * std::sin(ph)),
                               float(r * n * std::cos(th))});
        }
    }
    p.insert(p.end(), {0, 0, float(-r)});
    const int32_t south = int32_t(p.size() / 3 - 1);
    auto ring = [&](int i, int j) { return 1 + (i - 1) * segs + (j % segs); };
    for (int j = 0; j < segs; ++j) idx.insert(idx.end(), {0, ring(1, j), ring(1, j + 1)});
    for (int i = 1; i + 1 < rings; ++i)
        for (int j = 0; j < segs; ++j)
            idx.insert(idx.end(), {ring(i, j), ring(i + 1, j), ring(i + 1, j + 1), ring(i, j), ring(i + 1, j + 1), ring(i, j + 1)});
    for (int j = 0; j < segs; ++j) idx.insert(idx.end(), {south, ring(rings - 1, j + 1), ring(rings - 1, j)});

    facet_trimesh *mesh = facet_trimesh_create(p.data(), int32_t(p.size()), idx.data(), int32_t(idx.size()));
    auto roughness = [&]() {
        int32_t fc = 0;
        const float *q = facet_trimesh_positions(mesh, &fc);
        double sum = 0;
        for (int32_t i = 0; i < fc / 3; ++i) sum += std::fabs(V3{q[i * 3], q[i * 3 + 1], q[i * 3 + 2]}.len() - r);
        return sum / (fc / 3);
    };
    const double before = roughness();
    const double volBefore = stats(mesh).volume;
    facet_cleanup_options o{};
    o.smoothing_iterations = 10;
    EXPECT(facet_trimesh_cleanup(mesh, &o) == FACET_OK);
    const double after = roughness();
    if (after >= before * 0.6) std::fprintf(stderr, "  roughness %.3f -> %.3f\n", before, after);
    EXPECT(after < before * 0.6);
    EXPECT(std::fabs(stats(mesh).volume - volBefore) < 0.02 * volBefore);
    facet_trimesh_destroy(mesh);
}

}  // namespace

void runCleanupTests() {
    testCylinderOnTable();
    testFillAndLargestPiece();
    testConcaveHole();
    testSmoothingKeepsSize();
}
