// Manifold (Apache-2.0, github.com/elalish/manifold, pinned in native/CMakeLists.txt): guarantees a
// closed, oriented 2-manifold for printing, plus the flat-base and plinth booleans.
#include <manifold/manifold.h>

#include <algorithm>
#include <exception>

#include "pipeline.h"

namespace facet::pipeline {

Status repairWithManifold(TriMesh &mesh, float baseCutZ, float plinthHeight, float plinthMargin) {
    if (mesh.triangleCount() == 0) return kEmpty;
    try {
        manifold::MeshGL gl;
        gl.numProp = 3;
        gl.vertProperties = mesh.positions;
        gl.triVerts.assign(mesh.indices.begin(), mesh.indices.end());
        // Stitch vertices that coincide along open edges (e.g. duplicated seam vertices).
        gl.Merge();

        manifold::Manifold solid(gl);
        if (solid.Status() != manifold::Manifold::Error::NoError || solid.IsEmpty()) return kNotManifold;

        if (baseCutZ > 0.f) {
            solid = solid.TrimByPlane({0.0, 0.0, 1.0}, baseCutZ).Translate({0.0, 0.0, -baseCutZ});
            if (solid.IsEmpty()) return kEmpty;
        }
        if (plinthHeight > 0.f) {
            const manifold::Box box = solid.BoundingBox();
            const double margin = std::max(0.f, plinthMargin);
            const manifold::vec3 size = {box.max.x - box.min.x + 2 * margin, box.max.y - box.min.y + 2 * margin,
                                         plinthHeight};
            const manifold::Manifold plinth =
                manifold::Manifold::Cube(size).Translate({box.min.x - margin, box.min.y - margin, box.min.z - plinthHeight});
            // Union, then lift so the plinth's underside rests on Z = 0.
            solid = (solid + plinth).Translate({0.0, 0.0, -(box.min.z - plinthHeight)});
        }
        if (solid.Status() != manifold::Manifold::Error::NoError) return kNotManifold;

        const manifold::MeshGL out = solid.GetMeshGL();
        TriMesh result;
        result.positions.resize(static_cast<size_t>(out.NumVert()) * 3);
        for (size_t v = 0; v < out.NumVert(); ++v) {
            for (int d = 0; d < 3; ++d) result.positions[v * 3 + d] = out.vertProperties[v * out.numProp + d];
        }
        result.indices.assign(out.triVerts.begin(), out.triVerts.end());
        mesh = std::move(result);
        return kOk;
    } catch (const std::exception &) {
        return kInternal;
    }
}

}  // namespace facet::pipeline
