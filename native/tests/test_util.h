// Shared helpers for the C++ core tests (dependency-free runner, see facet_mesh_test.cpp).
#pragma once

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

inline int failures = 0;

#define EXPECT(cond)                                                     \
    do {                                                                 \
        if (!(cond)) {                                                   \
            std::fprintf(stderr, "%s:%d: FAILED: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                  \
        }                                                                \
    } while (0)

inline bool near(double a, double b) { return std::fabs(a - b) < 1e-9; }

namespace testutil {

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
inline void lookAt(V3 eye, V3 target, float m[16], V3 &right, V3 &up, V3 &back) {
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
inline std::vector<uint16_t> renderSphere(V3 eye, V3 right, V3 up, V3 back, V3 center, double radius) {
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

}  // namespace testutil
