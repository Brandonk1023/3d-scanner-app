// TSDF volume: depth fusion inside the scan box + surface-nets mesh extraction.
// Internal C++; the platforms see it only through the C API in include/facet_mesh.h.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace facet {

/**
 * Truncated signed distance field over the scan box. Box frame: origin at the centre of the box
 * base, X along the width, Y along the depth, Z up, millimetres. Voxel centres sit at
 * (-W/2 + (i+0.5)v, -D/2 + (j+0.5)v, (k+0.5)v).
 */
class TsdfVolume {
public:
    /** truncation: SDF band half-width in mm (should exceed the depth noise). */
    TsdfVolume(float width, float depth, float height, float voxel, float truncation);

    int nx() const { return nx_; }
    int ny() const { return ny_; }
    int nz() const { return nz_; }
    float voxel() const { return voxel_; }
    float truncation() const { return trunc_; }

    struct IntegrateResult {
        /** Voxels whose value changed (near-surface or carved free space). */
        int updated = 0;
        /** Mean confidence (0..1) of the depth samples that hit the surface inside the box. */
        float meanConfidence = 0.f;
        /** Number of depth samples that hit the surface inside the box. */
        int surfaceSamples = 0;
    };

    /**
     * Fuses one depth map. depth: millimetres along the optical axis, 0 = no data, row stride in
     * pixels. confidence: 0..255 per pixel or null (all 255). Intrinsics in depth-pixel units.
     * camToBox: column-major 4x4 taking camera-frame millimetres (+X right, +Y up, −Z forward) to
     * the box frame. Samples below minConfidence (0..1) are ignored.
     */
    IntegrateResult integrate(const uint16_t *depth, int width, int height, int depthStride,
                              const uint8_t *confidence, int confidenceStride,
                              float fx, float fy, float cx, float cy,
                              const float camToBox[16], float minConfidence);

    /**
     * Surface-nets mesh of the zero crossing, sampled every `step` voxels (1 = full resolution,
     * 2 = coarse live preview). Only cells whose 8 corners all have weight ≥ minWeight count.
     * Output positions are xyz triples in box-frame mm; indices are triangles.
     */
    void extract(int step, float minWeight, std::vector<float> &positions, std::vector<int32_t> &indices) const;

    /**
     * Silhouette carving: one object mask (row-major, width × height, nonzero = object) seen with
     * the given intrinsics (image pixels) and camera pose (same convention as integrate). Each
     * voxel counts the views that saw it and the views that put it outside the object.
     */
    void carve(const uint8_t *mask, int width, int height, float fx, float fy, float cx, float cy, const float camToBox[16]);

    /** Views carved so far. */
    int carvedViews() const { return carvedViews_; }

    /**
     * Closed, smoothed mesh of the visual hull: voxels seen by at least minSeen views and outside the
     * object in at most maxOutsideFraction of them.
     */
    void extractHull(float maxOutsideFraction, int minSeen, std::vector<float> &positions, std::vector<int32_t> &indices) const;

    bool save(const std::string &path) const;
    static TsdfVolume *load(const std::string &path);

    // Direct access for tests.
    float tsdfAt(int i, int j, int k) const { return tsdf_[index(i, j, k)]; }
    float weightAt(int i, int j, int k) const { return weight_[index(i, j, k)]; }

private:
    size_t index(int i, int j, int k) const { return (static_cast<size_t>(k) * ny_ + j) * nx_ + i; }

    static constexpr float kMaxWeight = 64.f;

    float width_, depth_, height_, voxel_, trunc_;
    int nx_, ny_, nz_;
    std::vector<float> tsdf_;
    std::vector<float> weight_;
    // Carving votes (allocated on first carve; not persisted).
    std::vector<uint16_t> seen_;
    std::vector<uint16_t> outside_;
    int carvedViews_ = 0;
};

}  // namespace facet
