// Minimal dependency-free test runner for the C++ core. Run with `make test-native`.
#include "facet_mesh.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include <utility>
#include <vector>

static int failures = 0;

#define EXPECT(cond)                                                     \
    do {                                                                 \
        if (!(cond)) {                                                   \
            std::fprintf(stderr, "%s:%d: FAILED: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                  \
        }                                                                \
    } while (0)

static bool near(double a, double b) { return std::fabs(a - b) < 1e-9; }

namespace {

struct V3 {
    double x, y, z;
    V3 operator+(V3 o) const { return {x + o.x, y + o.y, z + o.z}; }
    V3 operator-(V3 o) const { return {x - o.x, y - o.y, z - o.z}; }
    V3 operator*(double s) const { return {x * s, y * s, z * s}; }
    double dot(V3 o) const { return x * o.x + y * o.y + z * o.z; }
    V3 cross(V3 o) const { return {y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x}; }
    double len() const { return std::sqrt(dot(*this)); }
    V3 norm() const { return *this * (1.0 / len()); }
};

constexpr int W = 160, H = 120;
constexpr float FX = 140, FY = 140, CX = 80, CY = 60;

/** Camera at `eye` looking at `target` (Z up): column-major cam→box matrix + basis. */
void lookAt(V3 eye, V3 target, float m[16], V3 &right, V3 &up, V3 &back) {
    back = (eye - target).norm();  // camera +Z points away from the scene
    right = V3{0, 0, 1}.cross(back).norm();
    up = back.cross(right);
    const V3 cols[3] = {right, up, back};
    for (int c = 0; c < 3; ++c) {
        m[c * 4 + 0] = static_cast<float>(cols[c].x);
        m[c * 4 + 1] = static_cast<float>(cols[c].y);
        m[c * 4 + 2] = static_cast<float>(cols[c].z);
        m[c * 4 + 3] = 0;
    }
    m[12] = static_cast<float>(eye.x);
    m[13] = static_cast<float>(eye.y);
    m[14] = static_cast<float>(eye.z);
    m[15] = 1;
}

/** Depth map (mm along the optical axis) of a sphere, ray-cast from a camera. */
std::vector<uint16_t> renderSphere(V3 eye, V3 right, V3 up, V3 back, V3 center, double radius) {
    std::vector<uint16_t> depth(W * H, 0);
    for (int v = 0; v < H; ++v) {
        for (int u = 0; u < W; ++u) {
            // Camera-frame direction with forward component 1, so t = depth along the axis.
            const double xc = (u + 0.5 - CX) / FX, yc = -(v + 0.5 - CY) / FY;
            const V3 dir = right * xc + up * yc - back;
            const V3 oc = eye - center;
            const double a = dir.dot(dir), b = 2 * oc.dot(dir), c = oc.dot(oc) - radius * radius;
            const double disc = b * b - 4 * a * c;
            if (disc < 0) continue;
            const double t = (-b - std::sqrt(disc)) / (2 * a);
            if (t > 0) depth[v * W + u] = static_cast<uint16_t>(std::lround(t));
        }
    }
    return depth;
}

void testTsdfSphere() {
    const V3 center{0, 0, 60};
    const double radius = 40;
    facet_volume *vol = facet_volume_create(120, 120, 120, 2.f, 8.f);
    EXPECT(vol != nullptr);
    int32_t nx, ny, nz;
    facet_volume_dims(vol, &nx, &ny, &nz);
    EXPECT(nx == 60 && ny == 60 && nz == 60);

    int frames = 0;
    for (double elevation : {15.0, 45.0, 75.0}) {
        for (int az = 0; az < 360; az += 30) {
            const double e = elevation * M_PI / 180, a = az * M_PI / 180;
            const V3 eye = center + V3{std::cos(e) * std::cos(a), std::cos(e) * std::sin(a), std::sin(e)} * 300;
            float m[16];
            V3 right, up, back;
            lookAt(eye, center, m, right, up, back);
            const auto depth = renderSphere(eye, right, up, back, center, radius);
            const auto r = facet_volume_integrate(vol, depth.data(), W, H, W, nullptr, 0, FX, FY, CX, CY, m, 0.f);
            EXPECT(r.surface_samples > 0);
            EXPECT(near(r.mean_confidence, 1.0));
            ++frames;
        }
    }
    EXPECT(frames == 36);

    const int32_t tris = facet_volume_extract(vol, 1, 0.5f);
    EXPECT(tris > 1000);
    int32_t floats = 0, idxCount = 0;
    const float *pos = facet_volume_mesh_positions(vol, &floats);
    const int32_t *idx = facet_volume_mesh_indices(vol, &idxCount);
    EXPECT(idxCount == tris * 3);

    // Every vertex lies on the sphere (within a voxel).
    int off = 0;
    for (int i = 0; i < floats / 3; ++i) {
        const V3 p{pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]};
        if (std::fabs((p - center).len() - radius) > 2.0) ++off;
    }
    EXPECT(off == 0);
    if (off) std::fprintf(stderr, "  %d of %d vertices off the sphere\n", off, floats / 3);

    // Counter-clockwise triangles face outward.
    int outward = 0;
    for (int t = 0; t < tris; ++t) {
        const V3 a{pos[idx[t * 3] * 3], pos[idx[t * 3] * 3 + 1], pos[idx[t * 3] * 3 + 2]};
        const V3 b{pos[idx[t * 3 + 1] * 3], pos[idx[t * 3 + 1] * 3 + 1], pos[idx[t * 3 + 1] * 3 + 2]};
        const V3 c{pos[idx[t * 3 + 2] * 3], pos[idx[t * 3 + 2] * 3 + 1], pos[idx[t * 3 + 2] * 3 + 2]};
        const V3 n = (b - a).cross(c - a);
        if (n.dot((a + b + c) * (1.0 / 3) - center) > 0) ++outward;
    }
    EXPECT(outward > tris * 0.98);
    if (outward <= tris * 0.98) std::fprintf(stderr, "  only %d of %d triangles face outward\n", outward, tris);

    // Coarse preview has fewer triangles but still a surface.
    const int32_t coarse = facet_volume_extract(vol, 2, 0.5f);
    EXPECT(coarse > 100 && coarse < tris / 2);

    // Save / load round trip.
    const char *path = "facet_volume_test.bin";
    EXPECT(facet_volume_save(vol, path) == 1);
    facet_volume *loaded = facet_volume_load(path);
    EXPECT(loaded != nullptr);
    if (loaded) {
        EXPECT(facet_volume_extract(loaded, 1, 0.5f) == tris);
        facet_volume_destroy(loaded);
    }
    std::remove(path);
    facet_volume_destroy(vol);
}

/** Object mask of a sphere, as a 640 × 480 photo would see it. */
std::vector<uint8_t> sphereMask(V3 eye, V3 right, V3 up, V3 back, V3 center, double radius, float fx, float cx, float cy, int w, int h) {
    std::vector<uint8_t> mask(w * h, 0);
    for (int v = 0; v < h; ++v)
        for (int u = 0; u < w; ++u) {
            const V3 dir = right * ((u + 0.5 - cx) / fx) + up * (-(v + 0.5 - cy) / fx) - back;
            const V3 oc = eye - center;
            const double a = dir.dot(dir), b = 2 * oc.dot(dir), c = oc.dot(oc) - radius * radius;
            if (b * b - 4 * a * c >= 0) mask[v * w + u] = 255;
        }
    return mask;
}

void testSilhouetteCarving() {
    const V3 center{0, 0, 50};
    const double radius = 35;
    facet_volume *vol = facet_volume_create(120, 120, 110, 2.f, 8.f);
    for (double elevation : {10.0, 40.0, 70.0}) {
        for (int az = 0; az < 360; az += 30) {
            const double e = elevation * M_PI / 180, a = az * M_PI / 180;
            const V3 eye = center + V3{std::cos(e) * std::cos(a), std::cos(e) * std::sin(a), std::sin(e)} * 350;
            float m[16];
            V3 right, up, back;
            lookAt(eye, center, m, right, up, back);
            const auto mask = sphereMask(eye, right, up, back, center, radius, 500, 320, 240, 640, 480);
            facet_volume_carve(vol, mask.data(), 640, 480, 500, 500, 320, 240, m);
        }
    }
    EXPECT(facet_volume_carved_views(vol) == 36);
    const int32_t tris = facet_volume_extract_hull(vol, 0.1f, 5);
    EXPECT(tris > 1000);
    int32_t floats = 0, n = 0;
    const float *pos = facet_volume_mesh_positions(vol, &floats);
    const int32_t *idx = facet_volume_mesh_indices(vol, &n);
    // The hull of a sphere from 36 views hugs it (a little larger between views).
    double maxErr = 0;
    for (int i = 0; i < floats / 3; ++i) {
        const double r = (V3{pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]} - center).len();
        maxErr = std::fmax(maxErr, std::fabs(r - radius));
    }
    EXPECT(maxErr < 5.0);
    if (maxErr >= 5.0) std::fprintf(stderr, "  hull off the sphere by up to %.1f mm\n", maxErr);
    // Closed: every edge is shared by exactly two triangles.
    std::vector<std::pair<int, int>> edges;
    for (int t = 0; t < n; t += 3)
        for (int e = 0; e < 3; ++e) {
            int a = idx[t + e], b = idx[t + (e + 1) % 3];
            edges.push_back({std::min(a, b), std::max(a, b)});
        }
    std::sort(edges.begin(), edges.end());
    int open = 0;
    for (size_t i = 0; i < edges.size();) {
        size_t j = i;
        while (j < edges.size() && edges[j] == edges[i]) ++j;
        if (j - i != 2) ++open;
        i = j;
    }
    EXPECT(open == 0);
    if (open) std::fprintf(stderr, "  %d edges not shared by exactly two triangles\n", open);
    // Outward-facing.
    int outward = 0;
    for (int t = 0; t < n; t += 3) {
        const V3 a{pos[idx[t] * 3], pos[idx[t] * 3 + 1], pos[idx[t] * 3 + 2]};
        const V3 b{pos[idx[t + 1] * 3], pos[idx[t + 1] * 3 + 1], pos[idx[t + 1] * 3 + 2]};
        const V3 c{pos[idx[t + 2] * 3], pos[idx[t + 2] * 3 + 1], pos[idx[t + 2] * 3 + 2]};
        if ((b - a).cross(c - a).dot((a + b + c) * (1.0 / 3) - center) > 0) ++outward;
    }
    EXPECT(outward > n / 3 * 0.98);
    facet_volume_destroy(vol);
}

void testInvalidVolume() {
    EXPECT(facet_volume_create(0, 10, 10, 1, 4) == nullptr);
    EXPECT(facet_volume_create(2500, 2500, 2500, 0.4f, 4) == nullptr);  // far too many voxels
    EXPECT(facet_volume_load("does-not-exist.bin") == nullptr);
}

}  // namespace

int main() {
    EXPECT(std::strcmp(facet_core_version(), "0.2.0") == 0);
    EXPECT(near(facet_box_diagonal(3, 4, 0), 5.0));
    EXPECT(near(facet_box_diagonal(2, 3, 6), 7.0));
    EXPECT(near(facet_box_diagonal(0, 0, 0), 0.0));
    EXPECT(facet_box_diagonal(-1, 1, 1) == -1.0);
    testTsdfSphere();
    testSilhouetteCarving();
    testInvalidVolume();

    if (failures == 0) std::printf("facet_core tests: all passed\n");
    return failures == 0 ? 0 : 1;
}
