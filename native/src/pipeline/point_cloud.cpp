// Photo mode: ARCore-posed depth stills → oriented point cloud in the box frame.
//
// Written for Facet. The approach (raw depth + confidence → camera-space points through the depth
// intrinsics) follows the ARCore Raw Depth codelab (github.com/google-ar/codelab-raw-depth-api,
// Apache-2.0), but no code was copied from it.
#include <cmath>

#include "pipeline.h"

namespace facet::pipeline {

namespace {

// Camera-frame point (mm, +X right, +Y up, −Z forward) of depth pixel (u, v); same pinhole model
// as TsdfVolume::integrate (image y down, pixel centres at +0.5).
inline bool unproject(const uint16_t *depth, int width, int height, int stride, int u, int v, float fx, float fy,
                      float cx, float cy, float out[3]) {
    if (u < 0 || v < 0 || u >= width || v >= height) return false;
    const uint16_t d = depth[static_cast<size_t>(v) * stride + u];
    if (d == 0) return false;
    const float z = static_cast<float>(d);
    out[0] = (u + 0.5f - cx) / fx * z;
    out[1] = -(v + 0.5f - cy) / fy * z;
    out[2] = -z;
    return true;
}

}  // namespace

size_t addDepth(PointCloud &cloud, const uint16_t *depth, int width, int height, int depthStride,
                const uint8_t *confidence, int confidenceStride, float fx, float fy, float cx, float cy,
                const float m[16], float minConfidence) {
    if (!depth || width < 2 || height < 2 || fx <= 0 || fy <= 0) return 0;
    size_t added = 0;
    // Neighbours further apart than this (relative to depth) are a depth edge: no reliable normal.
    constexpr float kMaxRelativeStep = 0.05f;
    for (int v = 0; v + 1 < height; ++v) {
        for (int u = 0; u + 1 < width; ++u) {
            if (confidence) {
                const float conf = confidence[static_cast<size_t>(v) * confidenceStride + u] / 255.f;
                if (conf < minConfidence || conf <= 0.f) continue;
            }
            float p[3], pu[3], pv[3];
            if (!unproject(depth, width, height, depthStride, u, v, fx, fy, cx, cy, p)) continue;
            if (!unproject(depth, width, height, depthStride, u + 1, v, fx, fy, cx, cy, pu)) continue;
            if (!unproject(depth, width, height, depthStride, u, v + 1, fx, fy, cx, cy, pv)) continue;
            const float limit = kMaxRelativeStep * -p[2];
            if (std::fabs(pu[2] - p[2]) > limit || std::fabs(pv[2] - p[2]) > limit) continue;

            const float a[3] = {pu[0] - p[0], pu[1] - p[1], pu[2] - p[2]};
            const float b[3] = {pv[0] - p[0], pv[1] - p[1], pv[2] - p[2]};
            float n[3] = {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
            const float len = std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
            if (len <= 0.f) continue;
            // Face the camera (camera at the origin of this frame).
            const float s = (n[0] * p[0] + n[1] * p[1] + n[2] * p[2]) > 0.f ? -1.f / len : 1.f / len;
            for (float &x : n) x *= s;

            // Camera → box (column-major rigid transform).
            const float bx = m[0] * p[0] + m[4] * p[1] + m[8] * p[2] + m[12];
            const float by = m[1] * p[0] + m[5] * p[1] + m[9] * p[2] + m[13];
            const float bz = m[2] * p[0] + m[6] * p[1] + m[10] * p[2] + m[14];
            if (std::fabs(bx) > cloud.width / 2 || std::fabs(by) > cloud.depth / 2) continue;
            if (bz < cloud.floorMargin || bz > cloud.height) continue;
            const float nx = m[0] * n[0] + m[4] * n[1] + m[8] * n[2];
            const float ny = m[1] * n[0] + m[5] * n[1] + m[9] * n[2];
            const float nz = m[2] * n[0] + m[6] * n[1] + m[10] * n[2];
            cloud.positions.insert(cloud.positions.end(), {bx, by, bz});
            cloud.normals.insert(cloud.normals.end(), {nx, ny, nz});
            ++added;
        }
    }
    return added;
}

}  // namespace facet::pipeline
