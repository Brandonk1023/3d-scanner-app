# 07 · Multiplatform structure (Android now, iOS next)

**Decision:** one repo, **Kotlin Multiplatform from day one**, with a **shared C++ reconstruction core**. Android ships first; iOS reuses everything except the platform layer.

## What's shared vs per-platform

| Layer | Where | Shared? |
|---|---|---|
| Mesh reconstruction (TSDF fusion, marching cubes, cleanup, watertight, smoothing, scaling) | `native/` C++ with a plain C API | ✅ both (NDK + JNI on Android, static lib / XCFramework + cinterop on iOS) |
| Scan logic: `ScanSession` state machine, coverage model, guidance engine, `ScanTuning` | `shared/commonMain` | ✅ |
| Exporters: STL (binary, mm), 3MF | `shared/commonMain` | ✅ |
| Gallery data (Room KMP), settings (DataStore KMP), file layout | `shared/commonMain` | ✅ |
| ViewModels / `UiState` / screen logic | `shared/commonMain` | ✅ |
| Strings and design tokens | `design/` sources → generated per platform | ✅ source, generated output per platform |
| AR session, camera, depth input → `FrameMetrics` | `androidApp` (ARCore) / `iosApp` (ARKit) | ❌ per platform |
| Live AR rendering (camera feed, mesh overlay, rings) | ARCore + OpenGL ES / ARKit + Metal or RealityKit | ❌ |
| 3D viewer | Filament on Android; RealityKit / SceneKit or Filament on iOS | ❌ (decide in Phase 9) |
| Haptics | `VibrationEffect` / Core Haptics, behind `HapticsPlayer` | ❌ (same patterns) |
| Background processing + notifications | WorkManager / `BGProcessingTask` + local notifications | ❌ |
| Save & share | MediaStore + sharesheet / Files + share sheet | ❌ |
| UI | Jetpack Compose (Android). iOS: **SwiftUI or Compose Multiplatform — decide before Phase 9** | ❌ for now |

## Repo layout

```
/
├─ design/                  ← this handoff package
├─ native/                  ← C++ core (CMake), C API in include/facet_mesh.h, unit tests
├─ shared/
│  └─ src/
│     ├─ commonMain/kotlin/ ← scan logic, exporters, data, ViewModels, interfaces
│     ├─ commonTest/kotlin/
│     ├─ androidMain/kotlin/← actuals: JNI MeshEngine, haptics, storage, file export
│     └─ iosMain/kotlin/    ← actuals: cinterop MeshEngine, Core Haptics bridge, storage, file export
├─ androidApp/              ← Compose UI, ARCore, Filament, WorkManager, theme from design/android/theme
├─ iosApp/
│  ├─ project.yml           ← XcodeGen spec (the .xcodeproj is generated and git-ignored)
│  └─ iosApp/               ← SwiftUI entry point; ARKit layer later
├─ DEV_TODO.md              ← git-ignored
└─ Makefile                 ← `make android`, `make ios`, `make test`
```

## Interfaces in `shared` (expect/actual or injected)

- `MeshEngine` — create volume for a box, integrate depth frame, extract preview mesh, extract final mesh, cleanup options, free.
- `HapticsPlayer` — `play(GuidanceMessage)`, `play(ScanFeedback)`, `cancel()`. Patterns defined once (see `android/scan/Haptics.kt`); iOS maps them to Core Haptics events.
- `DeviceCapabilities` — depth supported? needs install/update? LiDAR?
- `StorageInfo` — free bytes, app usage split (models vs saved photos).
- `FileExporter` — save to user-visible storage, share.
- `BackgroundProcessor` — start / observe / cancel High detail jobs.

The guidance enums (`GuidanceMessage`, `ScanFeedback`) belong in `shared/commonMain`; `Haptics.kt` in this package is the Android `HapticsPlayer` implementation.

## iOS notes for later (Phase 9)

- **Depth:** ARKit scene depth needs a LiDAR iPhone (Pro models). Supporting non-LiDAR iPhones would need a different depth source — decide scope then.
- **UI choice:** Compose Multiplatform reuses the Android screens (less work, one UI codebase); SwiftUI feels most native and integrates most easily with ARKit/RealityKit. The scan screen's AR view is native either way.
- **Design tokens:** generate a Swift `Theme` from `tokens.json` (small Gradle or script task) instead of hand-copying values.
- **Fonts:** bundle the same Inter Tight and JetBrains Mono files.
- **Accounts:** a free Apple ID can run builds on your own iPhone (re-sign every 7 days); TestFlight and the App Store need the paid Apple Developer Program.
