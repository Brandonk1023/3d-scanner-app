cmake_minimum_required(VERSION 3.22)
project(facet_jni LANGUAGES CXX)

# Pull in the shared core (native/CMakeLists.txt) without its host tests.
set(FACET_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../.. ${CMAKE_CURRENT_BINARY_DIR}/facet_core)

add_library(facet_jni SHARED facet_jni.cpp)
target_link_libraries(facet_jni PRIVATE facet_core)
# 16 KB page-size alignment (required for new Play Store uploads targeting Android 15+).
target_link_options(facet_jni PRIVATE "-Wl,-z,max-page-size=16384")
