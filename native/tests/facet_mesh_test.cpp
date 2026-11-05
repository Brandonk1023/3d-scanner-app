// Minimal dependency-free test runner for the C++ core. Run with `make test-native`.
#include "facet_mesh.h"

#include <cmath>
#include <cstdio>
#include <cstring>

static int failures = 0;

#define EXPECT(cond)                                                     \
    do {                                                                 \
        if (!(cond)) {                                                   \
            std::fprintf(stderr, "%s:%d: FAILED: %s\n", __FILE__, __LINE__, #cond); \
            ++failures;                                                  \
        }                                                                \
    } while (0)

static bool near(double a, double b) { return std::fabs(a - b) < 1e-9; }

int main() {
    EXPECT(std::strcmp(facet_core_version(), "0.1.0") == 0);
    EXPECT(near(facet_box_diagonal(3, 4, 0), 5.0));
    EXPECT(near(facet_box_diagonal(2, 3, 6), 7.0));
    EXPECT(near(facet_box_diagonal(0, 0, 0), 0.0));
    EXPECT(facet_box_diagonal(-1, 1, 1) == -1.0);

    if (failures == 0) std::printf("facet_core tests: all passed\n");
    return failures == 0 ? 0 : 1;
}
