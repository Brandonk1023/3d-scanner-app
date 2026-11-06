// Internal C++ side of include/facet_pipeline.h. One file per third-party library so each one's
// headers (and warnings) stay in a single translation unit.
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace facet::pipeline {

/** Indexed triangle mesh: xyz positions, 3 indices per triangle. Millimetres, box frame. */
struct TriMesh {
    std::vector<float> positions;
    std::vector<int32_t> indices;

    size_t vertexCount() const { return positions.size() / 3; }
    size_t triangleCount() const { return indices.size() / 3; }
};

/** Oriented points (box frame). */
struct PointCloud {
    float width = 0, depth = 0, height = 0, floorMargin = 0;
    std::vector<float> positions;
    std::vector<float> normals;
    size_t size() const { return positions.size() / 3; }
};

// Status codes mirror FACET_* in facet_pipeline.h.
enum Status : int32_t { kOk = 0, kInvalid = 1, kEmpty = 2, kNotManifold = 3, kIo = 4, kUnsupported = 5, kInternal = 6 };

// cleanup.cpp
struct CleanupOptions {
    float tableSearch = 0, tableClip = 0, tableMinArea = 0;
    bool keepLargest = false;
    int smoothingIterations = 0;
    bool fillHoles = false;
};
Status cleanup(TriMesh &mesh, const CleanupOptions &options);
size_t openEdgeCount(const TriMesh &mesh);

// repair_manifold.cpp
Status repairWithManifold(TriMesh &mesh, float baseCutZ, float plinthHeight, float plinthMargin);

// decimate_meshopt.cpp
Status decimate(TriMesh &mesh, size_t targetTriangles, float maxError);

// export_stl.cpp
Status writeStl(const TriMesh &mesh, const std::string &path);

// export_3mf.cpp
Status write3mf(const TriMesh &mesh, const std::string &path, const std::string &objectName);

// point_cloud.cpp
size_t addDepth(PointCloud &cloud, const uint16_t *depth, int width, int height, int depthStride,
                const uint8_t *confidence, int confidenceStride, float fx, float fy, float cx, float cy,
                const float camToBox[16], float minConfidence);

// poisson.cpp
Status reconstructPoisson(const PointCloud &cloud, int depth, float trimFraction, TriMesh &out);

/** Drops vertices no triangle uses and renumbers the indices. */
void compactVertices(TriMesh &mesh);

}  // namespace facet::pipeline
