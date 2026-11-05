rootProject.name = "facet"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Google's oss-licenses-plugin is published on Google Maven without a plugin marker, so map
    // its plugin id to the artifact (developers.google.com/android/guides/opensource).
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.google.android.gms.oss-licenses-plugin") {
                useModule("com.google.android.gms:oss-licenses-plugin:${requested.version}")
            }
        }
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
