# 06 · Open questions and placeholders

Resolve these during development. Anything numeric lives in `ScanTuning`.

## Still to decide
- **App name and logo.** "Facet" and the hexagon mark are placeholders; keep the name in one string resource and the logo in one vector drawable.
- **High detail method.** Finer TSDF re-fusion from keyframes is the baseline. Evaluate on-device multi-view stereo / depth refinement as a spike and report quality, time and thermals on the S24 Ultra before committing.
- **Ruler auto-detection.** Coins are auto-detected in v1; ruler detection is a stretch goal (manual two-point covers rulers meanwhile).
- **Inches.** Display-only conversion; confirm how the scale-check input and export should behave when units = inches (exports stay in mm).
- **Supported phones link.** Point to Google's ARCore supported devices page, or ship an in-app explanation only (the app has no internet permission; a link opens the browser).

- **iOS UI:** SwiftUI or Compose Multiplatform (decide before Phase 9).
- **iPhone support scope:** LiDAR-only, or also non-LiDAR iPhones with a different depth source.

## Placeholder values to tune on device
- Coverage bands (0–30° / 30–55° / 55–80°), 24 sectors, frames per sector (3), One-side span (±60°).
- Finish thresholds: enabled at 15%; incomplete warning under 70% overall or any band under 50%.
- Guidance triggers: distance range formula, speed limits (30°/s, 0.25 m/s), light threshold (0.15), timings (0.5 s on, 1.5 s min, 4 s haptic cooldown).
- Voxel sizes for Quick and High detail; keyframe spacing (10° / 5 cm) and cap (300).
- Processing time estimates ("~5 s", "About 4 min") — compute from box size and keyframe count once measured.
- Storage thresholds (500 MB to start a scan; ~2 GB for High detail).
- File sizes shown in the export sheet — compute from the real mesh.

## Content placeholders in the designs
- `[Device model]` on the unsupported screen → `Build.MODEL` / marketing name.
- Sample data (Mug_01, Bracket_L, Sneaker_R, Gear_32T, sizes, triangle counts) is illustrative.
