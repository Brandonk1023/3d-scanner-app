cmake_minimum_required(VERSION 3.22)
project(facet_core VERSION 0.3.0 LANGUAGES C CXX)

# Shared C++ reconstruction core. Built as:
#   - a static library linked into libfacet_jni.so on Android (native/android/src/main/cpp)
#   - a static library per Apple target, bound by Kotlin/Native cinterop (see shared/build.gradle.kts)
#   - a host build with unit tests (`make test-native`)
# Nothing here may depend on Android or Apple APIs.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------------------------
# Third-party code. Every entry is pinned to an exact commit (never a branch). Each facet_fetch()
# name, and each name on a "facet-license:" line, needs a license file at
# androidApp/src/main/assets/licenses/<name>.txt and a row in LICENSE_CHECKLIST.md —
# `./gradlew verifyNativeLicenses` fails the build otherwise.
# Do NOT add OpenMVS, CGAL, Meshroom/AliceVision, COLMAP or CUDA/PyTorch code.
# ---------------------------------------------------------------------------------------------
include(cmake/FacetFetch.cmake)
include(cmake/Lib3mfLibzip.cmake)

# Open3D v0.20.0 (MIT): not fetched — marching cubes is ported into fusion/ with Open3D's notice.
# facet-license: open3d

# Manifold v3.5.4 (Apache-2.0): manifold repair + booleans. Static, serial (MANIFOLD_PAR=OFF, no TBB).
facet_fetch(manifold https://github.com/elalish/manifold.git ce50d78021d64507f89e8c9fc2c2e51018117857)

# lib3mf v2.5.0 (BSD-2-Clause): 3MF export. Its bundled libzip, zlib, cpp-base64 and fast_float are
# compiled in (each has its own license file); bundled LibreSSL is not (USE_INCLUDED_SSL is unused).
# facet-license: libzip zlib cpp-base64 fast_float
facet_fetch(lib3mf https://github.com/3MFConsortium/lib3mf.git 64bb454d1fcb53effa57d3cef752a10d740d41a2
    SUBMODULES submodules/libzip submodules/zlib submodules/fast_float)

# meshoptimizer v1.3 (MIT): decimation. src/*.cpp compiled directly.
facet_fetch(meshoptimizer https://github.com/zeux/meshoptimizer.git 9e1f07b159d3cb777f1c67ed31fc11fd117986f4)

# PoissonRecon (MIT), master as of 2026-04-29 (code version 18.76; the repo has no release tags
# after V8.0). Header-only Src/ only; JPEG/, JPEG-turbo/, PNG/ and ZLIB/ are never compiled.
facet_fetch(poissonrecon https://github.com/mkazhdan/PoissonRecon.git 262b0f539d404057d1f36e1adc07fc9388678899)

# --- Manifold -------------------------------------------------------------------------------
set(MANIFOLD_PAR OFF CACHE BOOL "" FORCE)            # no TBB (see LICENSE_CHECKLIST.md before enabling)
set(MANIFOLD_CROSS_SECTION OFF CACHE BOOL "" FORCE)  # no Clipper2; 3D booleans don't need it
set(MANIFOLD_CBIND OFF CACHE BOOL "" FORCE)
set(MANIFOLD_PYBIND OFF CACHE BOOL "" FORCE)
set(MANIFOLD_JSBIND OFF CACHE BOOL "" FORCE)
set(MANIFOLD_TEST OFF CACHE BOOL "" FORCE)
set(MANIFOLD_DOWNLOADS OFF CACHE BOOL "" FORCE)      # never fetch anything we haven't pinned
set(MANIFOLD_EXPORT OFF CACHE BOOL "" FORCE)
# --- lib3mf ---------------------------------------------------------------------------------
set(LIB3MF_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(LIB3MF_TESTS OFF CACHE BOOL "" FORCE)
set(USE_INCLUDED_ZLIB ON CACHE BOOL "" FORCE)
set(USE_INCLUDED_LIBZIP ON CACHE BOOL "" FORCE)
set(USE_INCLUDED_CPPBASE64 ON CACHE BOOL "" FORCE)
set(USE_INCLUDED_FASTFLOAT ON CACHE BOOL "" FORCE)
set(STRIP_BINARIES OFF CACHE BOOL "" FORCE)

# Third-party libraries are always static here, whatever the parent build asks for.
set(_facet_shared ${BUILD_SHARED_LIBS})
set(BUILD_SHARED_LIBS OFF)
facet_configure_lib3mf_libzip("${lib3mf_SOURCE_DIR}")
FetchContent_MakeAvailable(manifold lib3mf)
set(BUILD_SHARED_LIBS ${_facet_shared})

# --- meshoptimizer: its sources, compiled directly -------------------------------------------
file(GLOB FACET_MESHOPT_SOURCES CONFIGURE_DEPENDS "${meshoptimizer_SOURCE_DIR}/src/*.cpp")
add_library(facet_meshopt STATIC ${FACET_MESHOPT_SOURCES})
target_include_directories(facet_meshopt SYSTEM PUBLIC "${meshoptimizer_SOURCE_DIR}/src")

# ---------------------------------------------------------------------------------------------
# Facet core
# ---------------------------------------------------------------------------------------------
add_library(facet_core STATIC
    src/facet_mesh.cpp
    src/facet_pipeline.cpp
    src/tsdf.cpp
    fusion/marching_cubes.cpp
    src/pipeline/cleanup.cpp
    src/pipeline/repair_manifold.cpp
    src/pipeline/decimate_meshopt.cpp
    src/pipeline/export_stl.cpp
    src/pipeline/export_3mf.cpp
    src/pipeline/point_cloud.cpp
    src/pipeline/poisson.cpp
)
target_include_directories(facet_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_compile_options(facet_core PRIVATE -Wall -Wextra -Wpedantic)
target_link_libraries(facet_core PRIVATE manifold::manifold lib3mf facet_meshopt)

# PoissonRecon is header-only and noisy: Src/ only, as a system include, in poisson.cpp alone.
set(FACET_POISSON_INCLUDES "${poissonrecon_SOURCE_DIR}/Src")
if(ANDROID)
    list(APPEND FACET_POISSON_INCLUDES "${CMAKE_CURRENT_SOURCE_DIR}/compat/android")  # <sys/timeb.h> shim
endif()
set_source_files_properties(src/pipeline/poisson.cpp PROPERTIES
    INCLUDE_DIRECTORIES "${FACET_POISSON_INCLUDES}"
    COMPILE_OPTIONS "-w")

# Apple: Kotlin/Native cinterop links one archive, so merge the core and its static dependencies
# into libfacet_core_all.a (built by shared/build.gradle.kts → buildFacetCore* tasks).
if(APPLE)
    add_custom_target(facet_core_bundle
        COMMAND xcrun libtool -static -no_warning_for_no_symbols
                -o "$<TARGET_FILE_DIR:facet_core>/libfacet_core_all.a"
                "$<TARGET_FILE:facet_core>" "$<TARGET_FILE:manifold>" "$<TARGET_FILE:lib3mf>" "$<TARGET_FILE:facet_meshopt>"
        DEPENDS facet_core manifold lib3mf facet_meshopt
        COMMENT "Merging libfacet_core_all.a"
        VERBATIM)
endif()

option(FACET_BUILD_TESTS "Build C++ unit tests" ${PROJECT_IS_TOP_LEVEL})
if(FACET_BUILD_TESTS AND NOT CMAKE_SYSTEM_NAME STREQUAL "iOS" AND NOT ANDROID)
    enable_testing()
    add_executable(facet_core_tests tests/facet_mesh_test.cpp tests/facet_pipeline_test.cpp tests/facet_cleanup_test.cpp)
    target_link_libraries(facet_core_tests PRIVATE facet_core)
    add_test(NAME facet_core_tests COMMAND facet_core_tests)
endif()
