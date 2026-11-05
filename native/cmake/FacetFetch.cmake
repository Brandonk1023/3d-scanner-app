# facet_fetch(<name> <git-url> <commit> [SUBMODULES <paths...>])
#
# Pinned third-party sources for the C++ core. Every dependency is pinned to an exact commit hash
# (never a branch), and its license must be recorded in LICENSE_CHECKLIST.md and copied to
# androidApp/src/main/assets/licenses/<name>.txt — `./gradlew verifyNativeLicenses` checks the
# second part by reading the facet_fetch() calls in native/CMakeLists.txt.
#
# Sources are checked out once into FACET_DEPS_DIR (default native/.deps, git-ignored) and shared by
# every build tree (Android ABIs, iOS targets, host tests). A file lock keeps parallel configures
# from cloning into the same folder at once. The result is handed to FetchContent through
# FETCHCONTENT_SOURCE_DIR_<NAME>, so FetchContent never downloads anything itself.
include_guard(GLOBAL)
include(FetchContent)

set(FACET_DEPS_DIR "${CMAKE_CURRENT_LIST_DIR}/../.deps" CACHE PATH "Shared checkout folder for pinned C++ dependencies")
find_package(Git REQUIRED)

function(facet_fetch name url commit)
    cmake_parse_arguments(PARSE_ARGV 3 ARG "" "" "SUBMODULES")
    string(TOUPPER "${name}" upper)
    get_filename_component(deps "${FACET_DEPS_DIR}" ABSOLUTE)
    set(dir "${deps}/${name}")
    set(stamp "${dir}/.facet-pinned")

    file(MAKE_DIRECTORY "${deps}")
    file(LOCK "${deps}/${name}.lock" GUARD FUNCTION TIMEOUT 1800)
    # The stamp records what was checked out; any change (commit or submodule list) re-fetches.
    string(REPLACE ";" " " want "${commit} ${ARG_SUBMODULES}")
    string(STRIP "${want}" want)
    set(have "")
    if(EXISTS "${stamp}")
        file(READ "${stamp}" have)
        string(STRIP "${have}" have)
    endif()
    if(NOT have STREQUAL want)
        message(STATUS "facet_fetch: ${name} @ ${commit}")
        file(REMOVE_RECURSE "${dir}")
        file(MAKE_DIRECTORY "${dir}")
        # Fetch exactly the pinned commit (GitHub allows fetching by hash), no history.
        foreach(cmd
                "init;-q"
                "remote;add;origin;${url}"
                "fetch;-q;--depth;1;origin;${commit}"
                "-c;advice.detachedHead=false;checkout;-q;FETCH_HEAD")
            execute_process(COMMAND "${GIT_EXECUTABLE}" ${cmd} WORKING_DIRECTORY "${dir}" RESULT_VARIABLE rc)
            if(NOT rc EQUAL 0)
                message(FATAL_ERROR "facet_fetch: git ${cmd} failed for ${name}")
            endif()
        endforeach()
        if(ARG_SUBMODULES)
            execute_process(
                COMMAND "${GIT_EXECUTABLE}" submodule update -q --init --depth 1 -- ${ARG_SUBMODULES}
                WORKING_DIRECTORY "${dir}" RESULT_VARIABLE rc)
            if(NOT rc EQUAL 0)
                message(FATAL_ERROR "facet_fetch: submodules failed for ${name}")
            endif()
        endif()
        execute_process(COMMAND "${GIT_EXECUTABLE}" rev-parse HEAD WORKING_DIRECTORY "${dir}"
                        OUTPUT_VARIABLE got OUTPUT_STRIP_TRAILING_WHITESPACE)
        if(NOT got STREQUAL commit)
            message(FATAL_ERROR "facet_fetch: ${name} checked out ${got}, expected ${commit}")
        endif()
        file(WRITE "${stamp}" "${want}\n")
    endif()

    set(FETCHCONTENT_SOURCE_DIR_${upper} "${dir}" CACHE PATH "Pinned ${name} sources" FORCE)
    FetchContent_Declare(${name} GIT_REPOSITORY "${url}" GIT_TAG "${commit}")
    set(${name}_SOURCE_DIR "${dir}" PARENT_SCOPE)
endfunction()
