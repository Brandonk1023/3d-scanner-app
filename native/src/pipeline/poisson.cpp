// PoissonRecon (MIT, github.com/mkazhdan/PoissonRecon, pinned in native/CMakeLists.txt): watertight
// surface from the photo-mode point cloud, plus density trimming.
//
// Uses only the header-only reconstruction interface (Src/Reconstructors.h) with our own input /
// output streams, following Src/Reconstruction.example.cpp. The repository's bundled JPEG/,
// JPEG-turbo/, PNG/ and ZLIB/ folders are not on the include path and nothing from them is compiled.
#include "PreProcessor.h"
#include "Reconstructors.h"

#include <algorithm>
#include <exception>
#include <vector>

#include "pipeline.h"

namespace facet::pipeline {

namespace {

using namespace PoissonRecon;
using Real = float;
constexpr unsigned int Dim = 3;

struct CloudStream : public Reconstructor::InputOrientedSampleStream<Real, Dim> {
    explicit CloudStream(const PointCloud &cloud) : cloud_(cloud) {}
    void reset(void) { next_ = 0; }
    bool read(Point<Real, Dim> &p, Point<Real, Dim> &n) {
        if (next_ >= cloud_.size()) return false;
        for (unsigned int d = 0; d < Dim; d++) {
            p[d] = cloud_.positions[next_ * 3 + d];
            n[d] = cloud_.normals[next_ * 3 + d];
        }
        next_++;
        return true;
    }

private:
    const PointCloud &cloud_;
    size_t next_ = 0;
};

struct VertexStream : public Reconstructor::OutputLevelSetVertexStream<Real, Dim> {
    VertexStream(std::vector<float> &positions, std::vector<float> &density) : positions_(positions), density_(density) {}
    size_t size(void) const { return positions_.size() / 3; }
    size_t write(const Point<Real, Dim> &p, const Point<Real, Dim> &, const Real &density) {
        for (unsigned int d = 0; d < Dim; d++) positions_.push_back(p[d]);
        density_.push_back(density);
        return positions_.size() / 3 - 1;
    }

private:
    std::vector<float> &positions_;
    std::vector<float> &density_;
};

struct TriangleStream : public Reconstructor::OutputFaceStream<2> {
    explicit TriangleStream(std::vector<int32_t> &indices) : indices_(indices) {}
    size_t size(void) const { return indices_.size() / 3; }
    size_t write(const std::vector<node_index_type> &polygon) {
        // polygonMesh = false: the extractor emits triangles. Fan anything larger just in case.
        for (size_t i = 1; i + 1 < polygon.size(); i++) {
            indices_.push_back(static_cast<int32_t>(polygon[0]));
            indices_.push_back(static_cast<int32_t>(polygon[i]));
            indices_.push_back(static_cast<int32_t>(polygon[i + 1]));
        }
        return indices_.size() / 3 - 1;
    }

private:
    std::vector<int32_t> &indices_;
};

}  // namespace

Status reconstructPoisson(const PointCloud &cloud, int depth, float trimFraction, TriMesh &out) {
    if (cloud.size() < 16) return kEmpty;
    if (depth < 4 || depth > 12) return kInvalid;
    try {
        using Poisson = Reconstructor::Poisson;
        static const unsigned int FEMSig = FEMDegreeAndBType<Poisson::DefaultFEMDegree, Poisson::DefaultFEMBoundary>::Signature;
        using FEMSigs = IsotropicUIntPack<Dim, FEMSig>;
        using Solver = Poisson::Solver<Real, Dim, FEMSigs>;

        // TODO(tuning): point weight (screening), samples per node, depth from box size.
        Poisson::SolutionParameters<Real> solverParams;
        solverParams.verbose = false;
        solverParams.depth = static_cast<unsigned int>(depth);

        Reconstructor::LevelSetExtractionParameters extractionParams;
        extractionParams.outputDensity = true;
        extractionParams.verbose = false;

        CloudStream samples(cloud);
        auto *implicit = Solver::Solve(samples, solverParams);
        if (!implicit) return kInternal;

        std::vector<float> density;
        out = TriMesh{};
        VertexStream vertices(out.positions, density);
        TriangleStream triangles(out.indices);
        implicit->extractLevelSet(vertices, triangles, extractionParams);
        delete implicit;

        // Density trim: drop triangles touching the lowest-density vertices (far from any sample).
        if (trimFraction > 0.f && !density.empty()) {
            std::vector<float> sorted = density;
            const size_t k = std::min(sorted.size() - 1, static_cast<size_t>(trimFraction * sorted.size()));
            std::nth_element(sorted.begin(), sorted.begin() + k, sorted.end());
            const float threshold = sorted[k];
            std::vector<int32_t> kept;
            kept.reserve(out.indices.size());
            for (size_t t = 0; t < out.triangleCount(); ++t) {
                const int32_t *tri = &out.indices[t * 3];
                if (density[tri[0]] < threshold || density[tri[1]] < threshold || density[tri[2]] < threshold) continue;
                kept.insert(kept.end(), tri, tri + 3);
            }
            out.indices = std::move(kept);
            compactVertices(out);
        }
        return out.triangleCount() > 0 ? kOk : kEmpty;
    } catch (const std::exception &) {
        return kInternal;
    }
}

}  // namespace facet::pipeline
