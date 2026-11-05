// Binary STL writer (our own; the format is an 80-byte header, a uint32 count and 50 bytes per triangle).
#include <cmath>
#include <cstdio>
#include <cstring>
#include <memory>

#include "pipeline.h"

namespace facet::pipeline {

namespace {

// STL is little-endian. Every target (arm64 / x86_64 Android, arm64 iOS, host) is too; write bytes
// explicitly anyway so a big-endian host would still produce a valid file.
void putU32(unsigned char *p, uint32_t v) {
    for (int i = 0; i < 4; ++i) p[i] = static_cast<unsigned char>(v >> (8 * i));
}
void putF32(unsigned char *p, float f) {
    uint32_t v;
    std::memcpy(&v, &f, 4);
    putU32(p, v);
}

}  // namespace

Status writeStl(const TriMesh &mesh, const std::string &path) {
    if (mesh.triangleCount() == 0) return kEmpty;
    if (mesh.triangleCount() > UINT32_MAX) return kInvalid;
    std::unique_ptr<FILE, int (*)(FILE *)> file(std::fopen(path.c_str(), "wb"), &std::fclose);
    if (!file) return kIo;

    unsigned char header[84] = {};
    const char title[] = "Facet binary STL, units: mm";
    std::memcpy(header, title, sizeof(title) - 1);
    putU32(header + 80, static_cast<uint32_t>(mesh.triangleCount()));
    if (std::fwrite(header, 1, sizeof(header), file.get()) != sizeof(header)) return kIo;

    unsigned char record[50];
    for (size_t t = 0; t < mesh.triangleCount(); ++t) {
        const float *v[3];
        for (int c = 0; c < 3; ++c) v[c] = &mesh.positions[static_cast<size_t>(mesh.indices[t * 3 + c]) * 3];
        const float e1[3] = {v[1][0] - v[0][0], v[1][1] - v[0][1], v[1][2] - v[0][2]};
        const float e2[3] = {v[2][0] - v[0][0], v[2][1] - v[0][1], v[2][2] - v[0][2]};
        float n[3] = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
        const float len = std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        for (float &x : n) x = len > 0.f ? x / len : 0.f;
        unsigned char *p = record;
        for (float x : n) putF32(p, x), p += 4;
        for (const float *vert : v)
            for (int d = 0; d < 3; ++d) putF32(p, vert[d]), p += 4;
        p[0] = p[1] = 0;  // attribute byte count
        if (std::fwrite(record, 1, sizeof(record), file.get()) != sizeof(record)) return kIo;
    }
    return std::fflush(file.get()) == 0 ? kOk : kIo;
}

}  // namespace facet::pipeline
