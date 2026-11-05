# lib3mf compiles its bundled libzip sources itself, but needs libzip's generated platform headers
# (config.h, zipconf.h). Upstream (v2.5.0, CMakeLists.txt) makes them with a nested `cmake` run that
# does not forward the toolchain, so it fails or produces host headers when cross-compiling for
# Android / iOS. This runs the same configure with the current toolchain instead, into the folder
# lib3mf reads from, and creates ${CMAKE_BINARY_DIR}/libzip, which is the folder lib3mf checks
# before running its own configure (so it is skipped). lib3mf's sources are not modified.
include_guard(GLOBAL)

function(facet_configure_lib3mf_libzip lib3mf_source_dir)
    set(out "${FETCHCONTENT_BASE_DIR}/lib3mf-build/libzip")
    if(NOT FETCHCONTENT_BASE_DIR)
        set(out "${CMAKE_BINARY_DIR}/_deps/lib3mf-build/libzip")
    endif()
    file(MAKE_DIRECTORY "${CMAKE_BINARY_DIR}/libzip")
    if(EXISTS "${out}/zipconf.h" AND EXISTS "${out}/config.h")
        return()
    endif()

    # Same options as lib3mf's own configure (no crypto, no bzip2/lzma/zstd, static, no tools).
    set(args
        "-DZLIB_INCLUDE_DIR=${lib3mf_source_dir}/submodules/zlib"
        "-DZLIB_LIBRARY=zlibstatic"
        -DENABLE_COMMONCRYPTO=OFF -DENABLE_GNUTLS=OFF -DENABLE_MBEDTLS=OFF -DENABLE_OPENSSL=OFF
        -DENABLE_WINDOWS_CRYPTO=OFF -DENABLE_BZIP2=OFF -DENABLE_LZMA=OFF -DENABLE_ZSTD=OFF
        -DBUILD_TOOLS=OFF -DBUILD_REGRESS=OFF -DBUILD_EXAMPLES=OFF -DBUILD_DOC=OFF
        -DBUILD_SHARED_LIBS=OFF -DLIBZIP_DO_INSTALL=OFF
        "-DCMAKE_POLICY_VERSION_MINIMUM=3.10")
    # Forward the target toolchain.
    foreach(var CMAKE_TOOLCHAIN_FILE CMAKE_MAKE_PROGRAM CMAKE_C_COMPILER CMAKE_SYSTEM_NAME CMAKE_SYSTEM_VERSION
                CMAKE_OSX_SYSROOT CMAKE_OSX_ARCHITECTURES CMAKE_OSX_DEPLOYMENT_TARGET CMAKE_BUILD_TYPE
                ANDROID_ABI ANDROID_PLATFORM ANDROID_NDK ANDROID_STL)
        if(DEFINED ${var} AND NOT "${${var}}" STREQUAL "")
            list(APPEND args "-D${var}=${${var}}")
        endif()
    endforeach()

    execute_process(
        COMMAND "${CMAKE_COMMAND}" -G "${CMAKE_GENERATOR}" ${args}
                -S "${lib3mf_source_dir}/submodules/libzip" -B "${out}"
        RESULT_VARIABLE rc OUTPUT_VARIABLE log ERROR_VARIABLE log)
    if(NOT rc EQUAL 0 OR NOT EXISTS "${out}/zipconf.h" OR NOT EXISTS "${out}/config.h")
        message(FATAL_ERROR "Configuring lib3mf's bundled libzip failed:\n${log}")
    endif()
endfunction()
