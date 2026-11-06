// Final-mesh cleanup (docs/04 §4): table removal, largest piece, Taubin smoothing and hole filling.
// Facet's own code; no third-party sources.
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <numeric>
#include <unordered_map>
#include <vector>

#include "pipeline.h"

namespace facet::pipeline {
namespace {

struct Vec3 {
    double x = 0, y = 0, z = 0;
    Vec3 operator+(Vec3 o) const { return {x + o.x, y + o.y, z + o.z}; }
    Vec3 operator-(Vec3 o) const { return {x - o.x, y - o.y, z - o.z}; }
    Vec3 operator*(double s) const { return {x * s, y * s, z * s}; }
    double dot(Vec3 o) const { return x * o.x + y * o.y + z * o.z; }
    Vec3 cross(Vec3 o) const { return {y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x}; }
    double len() const { return std::sqrt(dot(*this)); }
};

Vec3 vertexAt(const TriMesh &m, int32_t i) {
    return {m.positions[i * 3], m.positions[i * 3 + 1], m.positions[i * 3 + 2]};
}

int32_t addVertex(TriMesh &m, Vec3 p) {
    m.positions.push_back(static_cast<float>(p.x));
    m.positions.push_back(static_cast<float>(p.y));
    m.positions.push_back(static_cast<float>(p.z));
    return static_cast<int32_t>(m.vertexCount() - 1);
}

uint64_t edgeKey(int32_t a, int32_t b) {
    return (static_cast<uint64_t>(static_cast<uint32_t>(a)) << 32) | static_cast<uint32_t>(b);
}

void dropDegenerate(TriMesh &m) {
    std::vector<int32_t> kept;
    kept.reserve(m.indices.size());
    for (size_t t = 0; t < m.triangleCount(); ++t) {
        const int32_t a = m.indices[t * 3], b = m.indices[t * 3 + 1], c = m.indices[t * 3 + 2];
        if (a == b || b == c || a == c) continue;
        kept.insert(kept.end(), {a, b, c});
    }
    m.indices = std::move(kept);
}

/** Merges vertices closer than a micrometre grid, so pieces that touch share their edges. */
void weld(TriMesh &m) {
    constexpr double kQuantum = 1e-3;  // mm
    struct Key {
        int64_t x, y, z;
        bool operator==(const Key &o) const { return x == o.x && y == o.y && z == o.z; }
    };
    struct KeyHash {
        size_t operator()(const Key &k) const {
            uint64_t h = static_cast<uint64_t>(k.x) * 0x9E3779B97F4A7C15ull;
            h ^= static_cast<uint64_t>(k.y) * 0xC2B2AE3D27D4EB4Full + (h << 6) + (h >> 2);
            h ^= static_cast<uint64_t>(k.z) * 0x165667B19E3779F9ull + (h << 6) + (h >> 2);
            return static_cast<size_t>(h);
        }
    };
    std::unordered_map<Key, int32_t, KeyHash> seen;
    seen.reserve(m.vertexCount());
    std::vector<int32_t> remap(m.vertexCount());
    std::vector<float> positions;
    positions.reserve(m.positions.size());
    for (size_t v = 0; v < m.vertexCount(); ++v) {
        const Key k{std::llround(m.positions[v * 3] / kQuantum), std::llround(m.positions[v * 3 + 1] / kQuantum),
                    std::llround(m.positions[v * 3 + 2] / kQuantum)};
        auto [it, inserted] = seen.emplace(k, static_cast<int32_t>(positions.size() / 3));
        if (inserted) positions.insert(positions.end(), m.positions.begin() + v * 3, m.positions.begin() + v * 3 + 3);
        remap[v] = it->second;
    }
    m.positions = std::move(positions);
    for (int32_t &i : m.indices) i = remap[i];
    dropDegenerate(m);
}

/**
 * The table: the 2 mm band in the bottom `search` mm with the most upward-facing area. Returns
 * false when no band has at least minArea (no table in this mesh).
 */
bool findTable(const TriMesh &m, float search, float minArea, double &tableZ) {
    if (m.vertexCount() == 0 || !(search > 0)) return false;
    double minZ = m.positions[2];
    for (size_t v = 0; v < m.vertexCount(); ++v) minZ = std::min(minZ, static_cast<double>(m.positions[v * 3 + 2]));
    const int bins = std::max(2, static_cast<int>(std::ceil(search)));  // 1 mm bins
    std::vector<double> area(bins, 0.0), areaZ(bins, 0.0);
    const double cosUp = std::cos(25.0 * M_PI / 180.0);
    for (size_t t = 0; t < m.triangleCount(); ++t) {
        const Vec3 a = vertexAt(m, m.indices[t * 3]), b = vertexAt(m, m.indices[t * 3 + 1]),
                   c = vertexAt(m, m.indices[t * 3 + 2]);
        const Vec3 n = (b - a).cross(c - a);
        const double len = n.len();
        if (len <= 0 || n.z < cosUp * len) continue;
        const double z = (a.z + b.z + c.z) / 3.0;
        const int bin = static_cast<int>((z - minZ));
        if (bin < 0 || bin >= bins) continue;
        area[bin] += len / 2;
        areaZ[bin] += z * len / 2;
    }
    int best = -1;
    double bestArea = 0;
    for (int i = 0; i + 1 < bins; ++i) {
        const double a = area[i] + area[i + 1];
        if (a > bestArea) {
            bestArea = a;
            best = i;
        }
    }
    if (best < 0 || bestArea < minArea) return false;
    tableZ = (areaZ[best] + areaZ[best + 1]) / bestArea;
    return true;
}

/** Cuts away everything below Z = zc. New edge vertices sit exactly on the plane. */
void cutBelow(TriMesh &m, float zc) {
    constexpr float kEps = 1e-4f;
    const size_t n = m.vertexCount();
    std::vector<uint8_t> inside(n);
    for (size_t v = 0; v < n; ++v) {
        float &z = m.positions[v * 3 + 2];
        if (std::fabs(z - zc) <= kEps) z = zc;
        inside[v] = z >= zc;
    }
    std::unordered_map<uint64_t, int32_t> crossings;
    auto crossing = [&](int32_t p, int32_t q) -> int32_t {
        // Reuse a vertex that already lies on the plane.
        if (inside[p] && m.positions[p * 3 + 2] == zc) return p;
        if (inside[q] && m.positions[q * 3 + 2] == zc) return q;
        const uint64_t key = p < q ? edgeKey(p, q) : edgeKey(q, p);
        auto it = crossings.find(key);
        if (it != crossings.end()) return it->second;
        const Vec3 a = vertexAt(m, p), b = vertexAt(m, q);
        const double t = (zc - a.z) / (b.z - a.z);
        Vec3 x = a + (b - a) * t;
        x.z = zc;
        const int32_t id = addVertex(m, x);
        crossings.emplace(key, id);
        return id;
    };
    std::vector<int32_t> out;
    out.reserve(m.indices.size());
    for (size_t t = 0; t < m.triangleCount(); ++t) {
        const int32_t tri[3] = {m.indices[t * 3], m.indices[t * 3 + 1], m.indices[t * 3 + 2]};
        const int count = inside[tri[0]] + inside[tri[1]] + inside[tri[2]];
        if (count == 3) {
            out.insert(out.end(), tri, tri + 3);
            continue;
        }
        if (count == 0) continue;
        // Sutherland–Hodgman against the plane; keeps the winding.
        int32_t poly[4];
        int k = 0;
        for (int e = 0; e < 3; ++e) {
            const int32_t p = tri[e], q = tri[(e + 1) % 3];
            if (inside[p]) poly[k++] = p;
            if (inside[p] != inside[q]) poly[k++] = crossing(p, q);
        }
        for (int i = 1; i + 1 < k; ++i) out.insert(out.end(), {poly[0], poly[i], poly[i + 1]});
    }
    m.indices = std::move(out);
    dropDegenerate(m);
    compactVertices(m);
}

int32_t findRoot(std::vector<int32_t> &parent, int32_t i) {
    while (parent[i] != i) {
        parent[i] = parent[parent[i]];
        i = parent[i];
    }
    return i;
}

void keepLargestPiece(TriMesh &m) {
    std::vector<int32_t> parent(m.vertexCount());
    std::iota(parent.begin(), parent.end(), 0);
    auto unite = [&](int32_t a, int32_t b) {
        a = findRoot(parent, a);
        b = findRoot(parent, b);
        if (a != b) parent[a] = b;
    };
    for (size_t t = 0; t < m.triangleCount(); ++t) {
        unite(m.indices[t * 3], m.indices[t * 3 + 1]);
        unite(m.indices[t * 3], m.indices[t * 3 + 2]);
    }
    std::vector<double> area(m.vertexCount(), 0.0);
    for (size_t t = 0; t < m.triangleCount(); ++t) {
        const Vec3 a = vertexAt(m, m.indices[t * 3]), b = vertexAt(m, m.indices[t * 3 + 1]),
                   c = vertexAt(m, m.indices[t * 3 + 2]);
        area[findRoot(parent, m.indices[t * 3])] += (b - a).cross(c - a).len() / 2;
    }
    const auto best = static_cast<int32_t>(std::max_element(area.begin(), area.end()) - area.begin());
    std::vector<int32_t> kept;
    kept.reserve(m.indices.size());
    for (size_t t = 0; t < m.triangleCount(); ++t) {
        if (findRoot(parent, m.indices[t * 3]) == best) kept.insert(kept.end(), m.indices.begin() + t * 3, m.indices.begin() + t * 3 + 3);
    }
    m.indices = std::move(kept);
    compactVertices(m);
}

/** Directed edges a→b whose twin b→a doesn't exist. */
std::vector<std::pair<int32_t, int32_t>> boundaryEdges(const TriMesh &m) {
    std::unordered_map<uint64_t, int32_t> directed;
    directed.reserve(m.indices.size());
    for (size_t t = 0; t < m.triangleCount(); ++t)
        for (int e = 0; e < 3; ++e) ++directed[edgeKey(m.indices[t * 3 + e], m.indices[t * 3 + (e + 1) % 3])];
    std::vector<std::pair<int32_t, int32_t>> open;
    for (const auto &[key, count] : directed) {
        const auto a = static_cast<int32_t>(key >> 32), b = static_cast<int32_t>(key & 0xFFFFFFFFu);
        if (!directed.count(edgeKey(b, a))) {
            for (int32_t i = 0; i < count; ++i) open.emplace_back(a, b);
        }
    }
    return open;
}

/** Taubin λ|μ smoothing with uniform weights: removes noise without shrinking. */
void smooth(TriMesh &m, int iterations) {
    const size_t n = m.vertexCount();
    // Neighbour lists (CSR) from the unique undirected edges.
    std::vector<uint64_t> pairs;
    pairs.reserve(m.indices.size() * 2);
    for (size_t t = 0; t < m.triangleCount(); ++t) {
        for (int e = 0; e < 3; ++e) {
            const int32_t a = m.indices[t * 3 + e], b = m.indices[t * 3 + (e + 1) % 3];
            pairs.push_back(edgeKey(a, b));
            pairs.push_back(edgeKey(b, a));
        }
    }
    std::sort(pairs.begin(), pairs.end());
    pairs.erase(std::unique(pairs.begin(), pairs.end()), pairs.end());
    std::vector<int32_t> offset(n + 1, 0), neighbour(pairs.size());
    for (size_t i = 0; i < pairs.size(); ++i) {
        ++offset[(pairs[i] >> 32) + 1];
        neighbour[i] = static_cast<int32_t>(pairs[i] & 0xFFFFFFFFu);
    }
    for (size_t v = 0; v < n; ++v) offset[v + 1] += offset[v];

    std::vector<uint8_t> fixed(n, 0);
    for (const auto &[a, b] : boundaryEdges(m)) fixed[a] = fixed[b] = 1;

    std::vector<float> next(m.positions.size());
    auto pass = [&](float factor) {
        for (size_t v = 0; v < n; ++v) {
            const int32_t begin = offset[v], end = offset[v + 1];
            for (int d = 0; d < 3; ++d) next[v * 3 + d] = m.positions[v * 3 + d];
            if (fixed[v] || begin == end) continue;
            for (int d = 0; d < 3; ++d) {
                float sum = 0;
                for (int32_t i = begin; i < end; ++i) sum += m.positions[neighbour[i] * 3 + d];
                const float mean = sum / static_cast<float>(end - begin);
                next[v * 3 + d] += factor * (mean - m.positions[v * 3 + d]);
            }
        }
        m.positions.swap(next);
    };
    for (int i = 0; i < iterations; ++i) {
        pass(0.5f);
        pass(-0.53f);
    }
}

/** Extends the open edges lying on Z = zc straight down to Z = floorZ (a vertical wall). */
void extrudeBase(TriMesh &m, float zc, float floorZ) {
    std::unordered_map<int32_t, int32_t> below;
    auto lowered = [&](int32_t v) -> int32_t {
        auto it = below.find(v);
        if (it != below.end()) return it->second;
        Vec3 p = vertexAt(m, v);
        p.z = floorZ;
        const int32_t id = addVertex(m, p);
        below.emplace(v, id);
        return id;
    };
    for (const auto &[a, b] : boundaryEdges(m)) {
        if (m.positions[a * 3 + 2] != zc || m.positions[b * 3 + 2] != zc) continue;
        const int32_t a2 = lowered(a), b2 = lowered(b);
        // The mesh has a→b, so the wall uses b→a; its new open edge a2→b2 runs the same way.
        m.indices.insert(m.indices.end(), {b, a, a2, b, a2, b2});
    }
}

/** Fan around the loop's centroid; loop[i]→loop[i+1] is an open edge of the mesh. */
void fanFill(TriMesh &m, const std::vector<int32_t> &loop) {
    Vec3 c;
    for (int32_t v : loop) c = c + vertexAt(m, v);
    const int32_t centre = addVertex(m, c * (1.0 / static_cast<double>(loop.size())));
    for (size_t i = 0; i < loop.size(); ++i) m.indices.insert(m.indices.end(), {loop[(i + 1) % loop.size()], loop[i], centre});
}

/** Ear-clips the loop in its best-fit plane, falling back to a fan where that fails. */
void fillLoop(TriMesh &m, const std::vector<int32_t> &loop) {
    constexpr size_t kMaxEarClip = 3000;
    const size_t n = loop.size();
    if (n < 3) return;
    if (n == 3) {
        m.indices.insert(m.indices.end(), {loop[2], loop[1], loop[0]});
        return;
    }
    // Newell normal: the loop runs counter-clockwise around it.
    Vec3 normal;
    for (size_t i = 0; i < n; ++i) {
        const Vec3 p = vertexAt(m, loop[i]), q = vertexAt(m, loop[(i + 1) % n]);
        normal = normal + Vec3{(p.y - q.y) * (p.z + q.z), (p.z - q.z) * (p.x + q.x), (p.x - q.x) * (p.y + q.y)};
    }
    const double nl = normal.len();
    if (nl < 1e-9 || n > kMaxEarClip) {
        fanFill(m, loop);
        return;
    }
    normal = normal * (1.0 / nl);
    Vec3 u = std::fabs(normal.x) < 0.9 ? Vec3{1, 0, 0}.cross(normal) : Vec3{0, 1, 0}.cross(normal);
    u = u * (1.0 / u.len());
    const Vec3 v = normal.cross(u);
    std::vector<double> px(n), py(n);
    for (size_t i = 0; i < n; ++i) {
        const Vec3 p = vertexAt(m, loop[i]);
        px[i] = p.dot(u);
        py[i] = p.dot(v);
    }
    std::vector<size_t> prev(n), next(n);
    for (size_t i = 0; i < n; ++i) {
        prev[i] = (i + n - 1) % n;
        next[i] = (i + 1) % n;
    }
    auto cross2 = [&](size_t a, size_t b, size_t c) {
        return (px[b] - px[a]) * (py[c] - py[a]) - (py[b] - py[a]) * (px[c] - px[a]);
    };
    auto isEar = [&](size_t i) {
        const size_t a = prev[i], c = next[i];
        if (cross2(a, i, c) <= 1e-12) return false;  // reflex or flat
        for (size_t j = next[c]; j != a; j = next[j]) {
            if (loop[j] == loop[a] || loop[j] == loop[i] || loop[j] == loop[c]) continue;
            if (cross2(a, i, j) >= 0 && cross2(i, c, j) >= 0 && cross2(c, a, j) >= 0) return false;
        }
        return true;
    };
    size_t remaining = n, cur = 0, misses = 0;
    while (remaining > 3) {
        if (isEar(cur)) {
            const size_t a = prev[cur], c = next[cur];
            m.indices.insert(m.indices.end(), {loop[c], loop[cur], loop[a]});
            next[a] = c;
            prev[c] = a;
            --remaining;
            misses = 0;
            cur = c;
        } else if (++misses > remaining) {
            // No ear left (the projection crosses itself): fan the rest.
            std::vector<int32_t> rest;
            for (size_t i = 0, j = cur; i < remaining; ++i, j = next[j]) rest.push_back(loop[j]);
            fanFill(m, rest);
            return;
        } else {
            cur = next[cur];
        }
    }
    m.indices.insert(m.indices.end(), {loop[next[cur]], loop[cur], loop[prev[cur]]});
}

void fillHoles(TriMesh &m) {
    std::unordered_map<int32_t, std::vector<int32_t>> out;
    const auto open = boundaryEdges(m);
    for (const auto &[a, b] : open) out[a].push_back(b);
    for (const auto &[a, b] : open) {
        auto &starts = out[a];
        auto it = std::find(starts.begin(), starts.end(), b);
        if (it == starts.end()) continue;  // already used by an earlier loop
        starts.erase(it);
        std::vector<int32_t> loop{a};
        int32_t cur = b;
        bool closed = true;
        while (cur != a) {
            auto &nexts = out[cur];
            if (nexts.empty() || loop.size() > open.size()) {
                closed = false;
                break;
            }
            loop.push_back(cur);
            cur = nexts.back();
            nexts.pop_back();
        }
        if (closed) fillLoop(m, loop);
    }
}

}  // namespace

size_t openEdgeCount(const TriMesh &mesh) { return boundaryEdges(mesh).size(); }

Status cleanup(TriMesh &mesh, const CleanupOptions &o) {
    if (mesh.triangleCount() == 0) return kEmpty;
    weld(mesh);
    double tableZ = 0;
    const bool table = o.tableSearch > 0 && findTable(mesh, o.tableSearch, o.tableMinArea, tableZ);
    const float cutZ = static_cast<float>(tableZ) + std::max(0.f, o.tableClip);
    if (table) cutBelow(mesh, cutZ);
    if (mesh.triangleCount() == 0) return kEmpty;
    if (o.keepLargest) keepLargestPiece(mesh);
    if (o.smoothingIterations > 0) smooth(mesh, o.smoothingIterations);
    if (table && cutZ > tableZ + 1e-3) extrudeBase(mesh, cutZ, static_cast<float>(tableZ));
    if (o.fillHoles) fillHoles(mesh);
    return mesh.triangleCount() == 0 ? kEmpty : kOk;
}

}  // namespace facet::pipeline
