// meshoptimizer (MIT, github.com/zeux/meshoptimizer, pinned in native/CMakeLists.txt): decimates heavy
// scans to a target triangle count before export.
#include "meshoptimizer.h"

#include <algorithm>
#include <vector>

#include "pipeline.h"

namespace facet::pipeline {

void compactVertices(TriMesh &mesh) {
    std::vector<int32_t> remap(mesh.vertexCount(), -1);
    std::vector<float> positions;
    positions.reserve(mesh.positions.size());
    for (int32_t &i : mesh.indices) {
        if (remap[i] < 0) {
            remap[i] = static_cast<int32_t>(positions.size() / 3);
            positions.insert(positions.end(), mesh.positions.begin() + i * 3, mesh.positions.begin() + i * 3 + 3);
        }
        i = remap[i];
    }
    mesh.positions = std::move(positions);
}

Status decimate(TriMesh &mesh, size_t targetTriangles, float maxError) {
    if (mesh.triangleCount() == 0) return kEmpty;
    if (targetTriangles == 0) return kInvalid;
    if (mesh.triangleCount() <= targetTriangles) return kOk;

    std::vector<unsigned int> indices(mesh.indices.begin(), mesh.indices.end());
    std::vector<unsigned int> simplified(indices.size());
    float resultError = 0.f;
    // Default options: borders may move, which is fine for the closed meshes Manifold produces.
    const size_t count = meshopt_simplify(simplified.data(), indices.data(), indices.size(), mesh.positions.data(),
                                          mesh.vertexCount(), sizeof(float) * 3, targetTriangles * 3,
                                          std::max(0.f, maxError), 0, &resultError);
    simplified.resize(count);
    mesh.indices.assign(simplified.begin(), simplified.end());
    compactVertices(mesh);
    return mesh.triangleCount() > 0 ? kOk : kEmpty;
}

}  // namespace facet::pipeline
