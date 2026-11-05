rootProject.name = "facet"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

include(":shared")
include(":androidApp")

// Android build of the C++ core (NDK/CMake + JNI bridge). Lives next to the C++ sources.
include(":nativeAndroid")
project(":nativeAndroid").projectDir = file("native/android")
