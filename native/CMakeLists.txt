cmake_minimum_required(VERSION 3.22)
project(facet_core VERSION 0.1.0 LANGUAGES CXX)

# Shared C++ reconstruction core. Built as:
#   - a static library linked into libfacet_jni.so on Android (native/android/src/main/cpp)
#   - a static library per Apple target, bound by Kotlin/Native cinterop (see shared/build.gradle.kts)
#   - a host build with unit tests (`make test-native`)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(facet_core STATIC
    src/facet_mesh.cpp
)
target_include_directories(facet_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_compile_options(facet_core PRIVATE -Wall -Wextra -Wpedantic)

option(FACET_BUILD_TESTS "Build C++ unit tests" ${PROJECT_IS_TOP_LEVEL})
if(FACET_BUILD_TESTS AND NOT CMAKE_SYSTEM_NAME STREQUAL "iOS" AND NOT ANDROID)
    enable_testing()
    add_executable(facet_core_tests tests/facet_mesh_test.cpp)
    target_link_libraries(facet_core_tests PRIVATE facet_core)
    add_test(NAME facet_core_tests COMMAND facet_core_tests)
endif()
