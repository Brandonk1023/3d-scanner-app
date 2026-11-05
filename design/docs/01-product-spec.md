# 01 · Product spec

## Summary

Facet (placeholder name) scans real objects into accurate, true-to-scale 3D models, entirely on the phone. The main use is making models for **3D printing and CAD**, so accurate shape matters more than realistic texture.

- **Objects:** small (coin to mug), medium (shoes, tools), large (furniture, car parts).
- **Platform:** Android first (Material 3 / Jetpack Compose; reference device Galaxy S24 Ultra), any phone with the ARCore Depth API. iPhone next, from the same Kotlin Multiplatform codebase with a shared C++ reconstruction core.
- **Audience:** makers, hobbyists, engineers. Personal use first, public release later, so built to release quality.
- **Privacy:** nothing is uploaded. No account, no network.

## How a scan works

1. Point the phone at a table; the app detects the surface.
2. Tap to place a 3D bounding box over the object and resize it to fit. Choose **Full object** or **One side**.
3. Press Start scan and walk slowly around the object. A live mesh builds over the object in AR, and the coverage rings fill as angles are captured (low, mid, high).
4. During the scan the app quietly saves sharp photos (keyframes) for High detail processing.
5. On Finish, the user picks **Quick result** (seconds) or **High detail** (a few minutes on-device, finer detail, best for small parts).
6. Review the model in a 3D viewer (orbit, zoom, shaded / wireframe, dimensions in mm), optionally run a **scale check**, then export.
7. Export **STL (mm)** or **3MF** in v1. More formats later, so the export UI has room to grow.
8. Every scan is saved in the in-app **gallery** to reopen, reprocess in High detail, or export again.

## Decisions made during design

| Topic | Decision |
|---|---|
| Visual direction | **A · Instrument**: graphite surfaces, one lime accent (`#D4F54A`), monospace numbers. Precise, tool-like, not gamified. |
| Theme | Light and Dark, **default: follow system**, override in Settings › Appearance. Camera / AR screens are always dark. |
| Coverage display | **Rings on the table** around the object (the ring for the current angle) **plus a mini top-down dial** (outer = low, middle = mid, inner = high). |
| Guidance | **One message at a time**, highest priority wins. |
| Haptics | **Distinct vibration pattern per guidance message**; tick per captured segment; double pulse when a ring completes. User can turn haptics off. |
| Scale check | **Both in v1:** automatic reference detection (coin; ruler later if feasible) and manual two-point measurement with a typed real length. |
| Large objects | Scan type chosen at placement: **Full object** or **One side**. Full-object scans under the coverage threshold can **Finish anyway** after a warning. |
| Gallery | **Date-sorted list with search**, filter chips: All / High detail / Quick / Processing. |
| Exports (v1) | STL (binary, millimetres) and 3MF. Future formats (OBJ, PLY, GLB, USDZ) tracked in a dev-only `DEV_TODO.md` excluded from Git. |
| Code sharing | **Kotlin Multiplatform from day one** + shared C++ core. Shared: scan logic, exporters, data, ViewModels. Per platform: AR, rendering, haptics, OS integration, UI (iOS UI choice made later). |
| Name & logo | Placeholders: "Facet" and a hexagon/cube line mark (`assets/logo-placeholder.svg`). |

## Feel

A precise, trustworthy instrument for makers. Clean and technical. Measurements always in a monospace face. Lime is used sparingly: the one main action, captured coverage, and positive guidance.
