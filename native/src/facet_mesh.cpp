#include "facet_mesh.h"

#include <cmath>

namespace facet {

constexpr const char *kVersion = "0.1.0";

double boxDiagonal(double w, double d, double h) {
    if (w < 0.0 || d < 0.0 || h < 0.0) return -1.0;
    return std::sqrt(w * w + d * d + h * h);
}

}  // namespace facet

extern "C" {

const char *facet_core_version(void) { return facet::kVersion; }

double facet_box_diagonal(double width, double depth, double height) {
    return facet::boxDiagonal(width, depth, height);
}

}  // extern "C"
