// lib3mf (BSD-2-Clause, github.com/3MFConsortium/lib3mf, pinned in native/CMakeLists.txt): 3MF export.
// Linked statically; the "implicit" C++ binding calls the lib3mf_* C functions directly.
#include "lib3mf_implicit.hpp"

#include <exception>
#include <vector>

#include "pipeline.h"

namespace facet::pipeline {

Status write3mf(const TriMesh &mesh, const std::string &path, const std::string &objectName) {
    if (mesh.triangleCount() == 0) return kEmpty;
    try {
        Lib3MF::PWrapper wrapper = Lib3MF::CWrapper::loadLibrary();
        Lib3MF::PModel model = wrapper->CreateModel();
        model->SetUnit(Lib3MF::eModelUnit::MilliMeter);

        std::vector<Lib3MF::sPosition> vertices(mesh.vertexCount());
        for (size_t v = 0; v < vertices.size(); ++v) {
            for (int d = 0; d < 3; ++d) vertices[v].m_Coordinates[d] = mesh.positions[v * 3 + d];
        }
        std::vector<Lib3MF::sTriangle> triangles(mesh.triangleCount());
        for (size_t t = 0; t < triangles.size(); ++t) {
            for (int c = 0; c < 3; ++c) triangles[t].m_Indices[c] = static_cast<Lib3MF_uint32>(mesh.indices[t * 3 + c]);
        }

        Lib3MF::PMeshObject object = model->AddMeshObject();
        object->SetName(objectName.empty() ? "scan" : objectName);
        object->SetGeometry(vertices, triangles);
        model->AddBuildItem(object.get(), wrapper->GetIdentityTransform());

        Lib3MF::PWriter writer = model->QueryWriter("3mf");
        writer->WriteToFile(path);
        return kOk;
    } catch (const Lib3MF::ELib3MFException &) {
        return kIo;
    } catch (const std::exception &) {
        return kInternal;
    }
}

}  // namespace facet::pipeline
