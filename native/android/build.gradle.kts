// Android build of the shared C++ core: compiles native/ with the NDK and exposes it through JNI.
// It's a separate module because the KMP Android library plugin used by :shared has no NDK support.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.facet.nativecore"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                cppFlags += listOf("-std=c++17")
            }
        }
        ndk {
            // Every ARCore Depth phone is 64-bit ARM; x86_64 keeps the emulator working.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}
