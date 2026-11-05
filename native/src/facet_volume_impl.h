// Definition of the opaque facet_volume handle, shared by facet_mesh.cpp and facet_pipeline.cpp.
#pragma once

#include <cstdint>
#include <vector>

#include "tsdf.h"

struct facet_volume {
    facet::TsdfVolume *tsdf;
    std::vector<float> positions;
    std::vector<int32_t> indices;
};
