# Facet (placeholder name)

Phone 3D scanner for 3D printing and CAD. Accurate, true-to-scale meshes, processed entirely on the
phone — nothing is uploaded. Android first (ARCore Depth API), iPhone next from the same repo.

- Design handoff and specs: [`design/`](design/) (start with `design/docs/01-product-spec.md`)
- Project structure: [`design/docs/07-multiplatform.md`](design/docs/07-multiplatform.md)

## Layout

| Path | What |
|---|---|
| `shared/` | Kotlin Multiplatform: scan logic, `ScanTuning`, exporters, data, ViewModels, platform interfaces |
| `native/` | C++ reconstruction core with a plain C API (`include/facet_mesh.h`) + tests |
| `native/android/` | Gradle module `:nativeAndroid`: NDK/CMake build of the core + JNI bridge |
| `androidApp/` | Jetpack Compose app, ARCore, theme from `design/android/theme` |
| `iosApp/` | SwiftUI shell; `project.yml` is the XcodeGen spec (the `.xcodeproj` is generated) |

## Build

Requirements: Android Studio (SDK 37, NDK 30.0.16248370, CMake 4.1.2), Xcode, `brew install xcodegen`.

```sh
make android    # debug APK
make install    # install on the connected phone
make test       # shared tests (Android JVM + iOS Simulator) + C++ core tests
make ios        # generate the Xcode project and build for the iOS Simulator
```
