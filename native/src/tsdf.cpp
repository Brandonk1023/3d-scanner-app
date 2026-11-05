#include "tsdf.h"

#include "../fusion/marching_cubes.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>

namespace facet {

TsdfVolume::TsdfVolume(float width, float depth, float height, float voxel, float truncation)
    : width_(width), depth_(depth), height_(height), voxel_(voxel), trunc_(truncation) {
    nx_ = std::max(2, static_cast<int>(std::ceil(width / voxel)));
    ny_ = std::max(2, static_cast<int>(std::ceil(depth / voxel)));
    nz_ = std::max(2, static_cast<int>(std::ceil(height / voxel)));
    const size_t n = static_cast<size_t>(nx_) * ny_ * nz_;
    tsdf_.assign(n, 1.f);
    weight_.assign(n, 0.f);
}

TsdfVolume::IntegrateResult TsdfVolume::integrate(const uint16_t *depth, int width, int height, int depthStride,
                                                  const uint8_t *confidence, int confidenceStride,
                                                  float fx, float fy, float cx, float cy,
                                                  const float m[16], float minConfidence) {
    IntegrateResult result;
    // Rigid inverse of camToBox (column-major): R^T and -R^T t.
    const float r00 = m[0], r10 = m[1], r20 = m[2];
    const float r01 = m[4], r11 = m[5], r21 = m[6];
    const float r02 = m[8], r12 = m[9], r22 = m[10];
    const float tx = m[12], ty = m[13], tz = m[14];
    // boxToCam rows = columns of R.
    const float b00 = r00, b01 = r10, b02 = r20;
    const float b10 = r01, b11 = r11, b12 = r21;
    const float b20 = r02, b21 = r12, b22 = r22;
    const float btx = -(b00 * tx + b01 * ty + b02 * tz);
    const float bty = -(b10 * tx + b11 * ty + b12 * tz);
    const float btz = -(b20 * tx + b21 * ty + b22 * tz);

    double confSum = 0.0;
    const float x0 = -width_ / 2 + 0.5f * voxel_;
    const float y0 = -depth_ / 2 + 0.5f * voxel_;
    // Camera-space step for +1 voxel along box X.
    const float sx = b00 * voxel_, sy = b10 * voxel_, sz = b20 * voxel_;

    for (int k = 0; k < nz_; ++k) {
        const float pz = (k + 0.5f) * voxel_;
        for (int j = 0; j < ny_; ++j) {
            const float py = y0 + j * voxel_;
            float cxp = b00 * x0 + b01 * py + b02 * pz + btx;
            float cyp = b10 * x0 + b11 * py + b12 * pz + bty;
            float czp = b20 * x0 + b21 * py + b22 * pz + btz;
            size_t idx = index(0, j, k);
            for (int i = 0; i < nx_; ++i, ++idx, cxp += sx, cyp += sy, czp += sz) {
                const float forward = -czp;  // distance along the optical axis
                if (forward <= 1.f) continue;
                const float u = fx * cxp / forward + cx;
                const float v = cy - fy * cyp / forward;
                // Bounds-check in float: converting a huge (near-grazing) projection to int is UB.
                if (!(u >= 0.f && v >= 0.f && u < static_cast<float>(width) && v < static_cast<float>(height))) continue;
                const int ui = static_cast<int>(u);
                const int vi = static_cast<int>(v);
                if (ui >= width || vi >= height) continue;
                const uint16_t d = depth[static_cast<size_t>(vi) * depthStride + ui];
                if (d == 0) continue;
                const float conf = confidence ? confidence[static_cast<size_t>(vi) * confidenceStride + ui] / 255.f : 1.f;
                if (conf < minConfidence || conf <= 0.f) continue;
                const float sdf = static_cast<float>(d) - forward;
                if (sdf < -trunc_) continue;  // hidden behind the observed surface
                const float t = std::min(1.f, sdf / trunc_);
                float &w = weight_[idx];
                float &value = tsdf_[idx];
                value = (value * w + t * conf) / (w + conf);
                w = std::min(w + conf, kMaxWeight);
                ++result.updated;
                if (sdf < trunc_) {
                    confSum += conf;
                    ++result.surfaceSamples;
                }
            }
        }
    }
    result.meanConfidence = result.surfaceSamples > 0 ? static_cast<float>(confSum / result.surfaceSamples) : 0.f;
    return result;
}

namespace {

/**
 * Surface nets over an sx × sy × sz sample grid. sample(a, b, c, value) returns false where there
 * is no data; pos(a, b, c, out) maps fractional sample coordinates to output positions. Negative
 * values are inside; triangles are wound counter-clockwise seen from outside.
 */
template <class Sample, class Pos>
void surfaceNets(int sx, int sy, int sz, Sample sample, Pos pos, std::vector<float> &positions, std::vector<int32_t> &indices) {
    if (sx < 2 || sy < 2 || sz < 2) return;
    // One vertex per cell that straddles the surface: the mean of its edge crossings.
    const int cx = sx - 1, cy = sy - 1, cz = sz - 1;
    std::vector<int32_t> cellVertex(static_cast<size_t>(cx) * cy * cz, -1);
    auto cellIndex = [&](int a, int b, int c) { return (static_cast<size_t>(c) * cy + b) * cx + a; };
    static const int corner[8][3] = {{0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {1, 1, 0}, {0, 0, 1}, {1, 0, 1}, {0, 1, 1}, {1, 1, 1}};
    static const int edge[12][2] = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7}, {0, 4}, {1, 5}, {2, 6}, {3, 7}};

    for (int c = 0; c < cz; ++c) {
        for (int b = 0; b < cy; ++b) {
            for (int a = 0; a < cx; ++a) {
                float v[8];
                bool ok = true;
                int negative = 0;
                for (int n = 0; n < 8 && ok; ++n) {
                    ok = sample(a + corner[n][0], b + corner[n][1], c + corner[n][2], v[n]);
                    if (v[n] < 0.f) ++negative;
                }
                if (!ok || negative == 0 || negative == 8) continue;
                float sum[3] = {0, 0, 0};
                int crossings = 0;
                for (const auto &e : edge) {
                    const float va = v[e[0]], vb = v[e[1]];
                    if ((va < 0.f) == (vb < 0.f)) continue;
                    const float t = va / (va - vb);
                    for (int d = 0; d < 3; ++d) {
                        sum[d] += corner[e[0]][d] + t * (corner[e[1]][d] - corner[e[0]][d]);
                    }
                    ++crossings;
                }
                float p[3];
                pos(a + sum[0] / crossings, b + sum[1] / crossings, c + sum[2] / crossings, p);
                cellVertex[cellIndex(a, b, c)] = static_cast<int32_t>(positions.size() / 3);
                positions.insert(positions.end(), p, p + 3);
            }
        }
    }

    // One quad per sample-grid edge with a sign change, joining the 4 cells around that edge.
    auto quad = [&](size_t q0, size_t q1, size_t q2, size_t q3, bool flip) {
        const int32_t i0 = cellVertex[q0], i1 = cellVertex[q1], i2 = cellVertex[q2], i3 = cellVertex[q3];
        if (i0 < 0 || i1 < 0 || i2 < 0 || i3 < 0) return;
        // Wound counter-clockwise seen from outside (positive SDF side).
        if (flip) {
            indices.insert(indices.end(), {i0, i1, i2, i0, i2, i3});
        } else {
            indices.insert(indices.end(), {i0, i2, i1, i0, i3, i2});
        }
    };
    for (int c = 0; c < sz; ++c) {
        for (int b = 0; b < sy; ++b) {
            for (int a = 0; a < sx; ++a) {
                float v0;
                if (!sample(a, b, c, v0)) continue;
                const bool inside = v0 < 0.f;
                float v1;
                // Edge along +X: cells (a, b-1..b, c-1..c).
                if (a + 1 < sx && b >= 1 && c >= 1 && b <= cy - 1 && c <= cz - 1 &&
                    sample(a + 1, b, c, v1) && (v1 < 0.f) != inside) {
                    quad(cellIndex(a, b - 1, c - 1), cellIndex(a, b, c - 1), cellIndex(a, b, c),
                         cellIndex(a, b - 1, c), inside);
                }
                // Edge along +Y: cells (a-1..a, b, c-1..c).
                if (b + 1 < sy && a >= 1 && c >= 1 && a <= cx - 1 && c <= cz - 1 &&
                    sample(a, b + 1, c, v1) && (v1 < 0.f) != inside) {
                    quad(cellIndex(a - 1, b, c - 1), cellIndex(a - 1, b, c), cellIndex(a, b, c),
                         cellIndex(a, b, c - 1), inside);
                }
                // Edge along +Z: cells (a-1..a, b-1..b, c).
                if (c + 1 < sz && a >= 1 && b >= 1 && a <= cx - 1 && b <= cy - 1 &&
                    sample(a, b, c + 1, v1) && (v1 < 0.f) != inside) {
                    quad(cellIndex(a - 1, b - 1, c), cellIndex(a, b - 1, c), cellIndex(a, b, c),
                         cellIndex(a - 1, b, c), inside);
                }
            }
        }
    }
}

}  // namespace

void TsdfVolume::extract(int step, float minWeight, std::vector<float> &positions, std::vector<int32_t> &indices) const {
    positions.clear();
    indices.clear();
    step = std::max(1, step);
    // Sample grid: every `step` voxels.
    const int sx = (nx_ - 1) / step + 1;
    const int sy = (ny_ - 1) / step + 1;
    const int sz = (nz_ - 1) / step + 1;
    auto sample = [&](int a, int b, int c, float &value) -> bool {
        const size_t idx = index(a * step, b * step, c * step);
        if (weight_[idx] < minWeight) return false;
        value = tsdf_[idx];
        return true;
    };
    auto pos = [&](float a, float b, float c, float out[3]) {
        out[0] = -width_ / 2 + (a * step + 0.5f) * voxel_;
        out[1] = -depth_ / 2 + (b * step + 0.5f) * voxel_;
        out[2] = (c * step + 0.5f) * voxel_;
    };
    surfaceNets(sx, sy, sz, sample, pos, positions, indices);
}

void TsdfVolume::extractMarchingCubes(float minWeight, std::vector<float> &positions,
                                      std::vector<int32_t> &indices) const {
    fusion::TsdfGrid grid;
    grid.nx = nx_;
    grid.ny = ny_;
    grid.nz = nz_;
    grid.voxel = voxel_;
    grid.origin[0] = -width_ / 2;
    grid.origin[1] = -depth_ / 2;
    grid.origin[2] = 0.f;
    grid.tsdf = tsdf_.data();
    grid.weight = weight_.data();
    fusion::extractTriangleMesh(grid, minWeight, positions, indices);
}

void TsdfVolume::carve(const uint8_t *mask, int width, int height, float fx, float fy, float cx, float cy,
                       const float m[16]) {
    if (seen_.empty()) {
        seen_.assign(tsdf_.size(), 0);
        outside_.assign(tsdf_.size(), 0);
    }
    // Same projection as integrate(): box → camera (rigid inverse), pinhole, image y down.
    const float b00 = m[0], b01 = m[1], b02 = m[2];
    const float b10 = m[4], b11 = m[5], b12 = m[6];
    const float b20 = m[8], b21 = m[9], b22 = m[10];
    const float tx = m[12], ty = m[13], tz = m[14];
    const float btx = -(b00 * tx + b01 * ty + b02 * tz);
    const float bty = -(b10 * tx + b11 * ty + b12 * tz);
    const float btz = -(b20 * tx + b21 * ty + b22 * tz);
    const float x0 = -width_ / 2 + 0.5f * voxel_;
    const float y0 = -depth_ / 2 + 0.5f * voxel_;
    const float sx = b00 * voxel_, sy = b10 * voxel_, sz = b20 * voxel_;
    for (int k = 0; k < nz_; ++k) {
        const float pz = (k + 0.5f) * voxel_;
        for (int j = 0; j < ny_; ++j) {
            const float py = y0 + j * voxel_;
            float cxp = b00 * x0 + b01 * py + b02 * pz + btx;
            float cyp = b10 * x0 + b11 * py + b12 * pz + bty;
            float czp = b20 * x0 + b21 * py + b22 * pz + btz;
            size_t idx = index(0, j, k);
            for (int i = 0; i < nx_; ++i, ++idx, cxp += sx, cyp += sy, czp += sz) {
                const float forward = -czp;
                if (forward <= 1.f) continue;
                const float u = fx * cxp / forward + cx;
                const float v = cy - fy * cyp / forward;
                if (!(u >= 0.f && v >= 0.f && u < static_cast<float>(width) && v < static_cast<float>(height))) continue;
                const int ui = static_cast<int>(u);
                const int vi = static_cast<int>(v);
                if (ui >= width || vi >= height) continue;
                if (seen_[idx] < 0xFFFF) ++seen_[idx];
                if (mask[static_cast<size_t>(vi) * width + ui] == 0 && outside_[idx] < 0xFFFF) ++outside_[idx];
            }
        }
    }
    ++carvedViews_;
}

void TsdfVolume::extractHull(float maxOutsideFraction, int minSeen, std::vector<float> &positions,
                             std::vector<int32_t> &indices) const {
    positions.clear();
    indices.clear();
    if (seen_.empty()) return;
    // Occupancy field padded by one empty voxel on every side so the shape comes out closed,
    // then a 3×3×3 box blur for smooth (not blocky) surfaces.
    const int px = nx_ + 2, py = ny_ + 2, pz = nz_ + 2;
    auto pidx = [&](int i, int j, int k) { return (static_cast<size_t>(k) * py + j) * px + i; };
    std::vector<float> occ(static_cast<size_t>(px) * py * pz, 1.f);
    for (int k = 0; k < nz_; ++k)
        for (int j = 0; j < ny_; ++j)
            for (int i = 0; i < nx_; ++i) {
                const size_t idx = index(i, j, k);
                const int seen = seen_[idx];
                if (seen >= minSeen && outside_[idx] <= maxOutsideFraction * seen) occ[pidx(i + 1, j + 1, k + 1)] = -1.f;
            }
    std::vector<float> field(occ.size(), 1.f);
    for (int k = 1; k < pz - 1; ++k)
        for (int j = 1; j < py - 1; ++j)
            for (int i = 1; i < px - 1; ++i) {
                float sum = 0.f;
                for (int dk = -1; dk <= 1; ++dk)
                    for (int dj = -1; dj <= 1; ++dj)
                        for (int di = -1; di <= 1; ++di) sum += occ[pidx(i + di, j + dj, k + dk)];
                field[pidx(i, j, k)] = sum / 27.f;
            }
    auto sample = [&](int a, int b, int c, float &value) -> bool {
        value = field[pidx(a, b, c)];
        return true;
    };
    auto pos = [&](float a, float b, float c, float out[3]) {
        out[0] = -width_ / 2 + (a - 1 + 0.5f) * voxel_;
        out[1] = -depth_ / 2 + (b - 1 + 0.5f) * voxel_;
        out[2] = (c - 1 + 0.5f) * voxel_;
    };
    surfaceNets(px, py, pz, sample, pos, positions, indices);
}

namespace {
constexpr char kMagic[8] = {'F', 'T', 'S', 'D', 'F', '0', '1', '\0'};
}

bool TsdfVolume::save(const std::string &path) const {
    std::unique_ptr<FILE, int (*)(FILE *)> f(std::fopen(path.c_str(), "wb"), std::fclose);
    if (!f) return false;
    const float dims[5] = {width_, depth_, height_, voxel_, trunc_};
    const int32_t counts[3] = {nx_, ny_, nz_};
    bool ok = std::fwrite(kMagic, 1, sizeof kMagic, f.get()) == sizeof kMagic &&
              std::fwrite(dims, sizeof(float), 5, f.get()) == 5 &&
              std::fwrite(counts, sizeof(int32_t), 3, f.get()) == 3 &&
              std::fwrite(tsdf_.data(), sizeof(float), tsdf_.size(), f.get()) == tsdf_.size() &&
              std::fwrite(weight_.data(), sizeof(float), weight_.size(), f.get()) == weight_.size();
    return ok;
}

TsdfVolume *TsdfVolume::load(const std::string &path) {
    std::unique_ptr<FILE, int (*)(FILE *)> f(std::fopen(path.c_str(), "rb"), std::fclose);
    if (!f) return nullptr;
    char magic[8];
    float dims[5];
    int32_t counts[3];
    if (std::fread(magic, 1, sizeof magic, f.get()) != sizeof magic || std::memcmp(magic, kMagic, sizeof magic) != 0) return nullptr;
    if (std::fread(dims, sizeof(float), 5, f.get()) != 5 || std::fread(counts, sizeof(int32_t), 3, f.get()) != 3) return nullptr;
    std::unique_ptr<TsdfVolume> v(new TsdfVolume(dims[0], dims[1], dims[2], dims[3], dims[4]));
    if (v->nx_ != counts[0] || v->ny_ != counts[1] || v->nz_ != counts[2]) return nullptr;
    if (std::fread(v->tsdf_.data(), sizeof(float), v->tsdf_.size(), f.get()) != v->tsdf_.size()) return nullptr;
    if (std::fread(v->weight_.data(), sizeof(float), v->weight_.size(), f.get()) != v->weight_.size()) return nullptr;
    return v.release();
}

}  // namespace facet
