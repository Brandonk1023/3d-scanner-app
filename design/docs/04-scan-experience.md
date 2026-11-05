# 04 · Scan experience (the heart of the app)

Reference images: `A-Capture.png`, `Placement-Surface.png`, `Placement-Box.png`, `Capture-TrackingLost.png`, `Finish-Incomplete.png`, `Finish-Mode.png`, `Scan-Interrupted.png`, `DS-AR.png`.

The user walks around the object holding the phone in one hand, often looking at the object rather than the screen. So: glanceable guidance, big thumb-reachable controls, minimal text, strong visual + haptic cues, readable over any camera background.

**Every number marked _placeholder_ belongs in a single `ScanTuning` object** so it can be tuned on the S24 Ultra.

## Scan session state machine

```
FindingSurface → PlacingBox → Capturing ⇄ Paused → Finishing → (Quick | HighDetail)
                                  ↓
                          TrackingLost (sub-state; fusion frozen)
```

## 1. Placement

**Find surface** (`Placement-Surface.png`)
- ARCore horizontal plane detection. Draw detected plane as a dotted grid with a faint lime tint and dashed edge.
- Advice banner (white): "Tap the object". A reticle follows the screen centre's hit on the plane.
- Card: "Surface found" + tip "Leave a hand's width of clear space around the object."

**Fit box** (`Placement-Box.png`)
- On tap: place a box anchored to the plane, auto-sized from depth points above the plane near the tap (cluster within _placeholder_ 30 cm radius).
- Handles (white 22 dp circles with halo, 48 dp touch area): top = height, left/right = width, bottom = move along the plane. Two-finger rotate on the plane.
- Box limits: _placeholder_ 20 mm – 2.5 m per side.
- Readout: `W × D × H mm` in mono. **Fit to object** re-runs auto-size.
- **Scan type** segmented: **Full object** (default; "Walk all the way around. Best for printing.") / **One side** ("For big or wall-mounted things. Back stays open.").
- Primary **Start scan** (accent, 64 dp). Check free storage first (see Low storage).

## 2. Capture

Layout (`A-Capture.png`, top to bottom):
- Top row: Cancel (glass, 48 dp) · status pill `● SCANNING 01:12` (mono) · mini dial · Torch.
- Guidance banner, centred, ~124 dp from top (one at a time).
- AR: live mesh over the object (lime triangle wireframe + 16% lime fill, clipped to the object) and the coverage ring on the table.
- Coverage panel (glass): big mono `49%` + rows HIGH / MID / LOW with bars.
- Controls (bottom): Pause (60 dp glass) · **Finish** (white pill 184 × 64) · Mesh on/off (60 dp glass).

### Coverage model

- 3 elevation bands × 24 azimuth sectors (15° each) around the box's vertical axis.
  - **Low** 0–30°, **Mid** 30–55°, **High** 55–80° camera elevation above the table plane, measured to the box centre. _placeholder_
- A sector is **captured** when it has ≥ _placeholder_ 3 accepted frames: tracking OK, speed OK, ≥ 60% of the box in view, mean depth confidence ≥ _placeholder_ 0.5.
- Coverage % = captured sectors / counted sectors.
- **One side** mode: only sectors within ±60° of the heading at Start scan count. _placeholder_

### Coverage display — rings + mini dial

- **Table ring:** an ellipse around the box base for the band the user is currently in. Captured sectors are **thick and solid** (7 dp, accent); missing ones are **thin and dashed** (1.5 dp white @ 75%). Both have a 10–11 dp black @ 45% halo underneath. A white triangle marks the user's current heading.
- **Mini dial** (top right, glass, ~88–112 dp): top-down map; outer ring = Low, middle = Mid, inner = High; 12 wedges per ring (a wedge fills when both of its 15° sectors are captured); white needle points to the user. Missing wedges are dashed outlines.
- **Coverage panel:** big percentage + per-band bars: complete = solid + check icon; partial = outlined bar with fill + %; empty = dashed outline + 0%.

### Guidance engine — one message at a time

| Priority | Message | Style | Icon | Shown when (_placeholder_ values) | Haptic |
|---|---|---|---|---|---|
| 1 | **Tracking lost** · sub-line "Point back at the object" | Critical `#FF6B5E` + inner outline | gps_off / crosshair-x | ARCore not TRACKING, or box out of view > 1 s. Fusion frozen, mesh dimmed to 40%. | 400 ms buzz, repeat every 2 s while shown |
| 2 | **Too close** / **Too far** | Warning `#FFB547` | arrow down / arrow up | Camera-to-box-centre distance outside `[max(0.20 m, 0.8·D), max(0.60 m, 2.5·D)]`, D = box diagonal | 2 short pulses |
| 3 | **Move slower** | Guide `#D4F54A` | speed gauge | Orbit speed > 30°/s, or linear speed > 0.25 m/s, or blur score above threshold | 3 quick ticks |
| 4 | **More light needed** | Advice white | sun | ARCore light estimate pixel intensity < 0.15 for > 1 s. Torch button highlights once. | 1 soft pulse |
| 5 | **Capture higher angles** (mirror: **lower**) | Guide `#D4F54A` | arrow up | Current band ≥ 90% and a higher band < 50% after > 5 s at the same elevation | rising ramp, soft → strong |
| — | Segment captured | (no banner) | — | sector becomes captured | light tick |
| — | Ring complete | toast 1.5 s "Low angles done" | check | band reaches 100% | double strong pulse |

Rules:
- A condition must hold **0.5 s** before its banner shows; a banner stays at least **1.5 s**; a higher priority replaces a lower one **immediately**.
- The same message's haptic doesn't repeat within **4 s** (except Tracking lost's 2 s repeat).
- Haptics respect Settings › Haptic guidance. Patterns are in `android/scan/Haptics.kt`.
- Each message has a unique icon and haptic, so colour is never the only cue.
- Announce banner text to TalkBack (polite live region).

### Pause, interruption, tracking loss

- **Pause** freezes fusion and keyframe capture; Pause becomes Resume.
- **Tracking lost**: keep everything captured, freeze fusion until tracking returns, show the critical banner (`Capture-TrackingLost.png`).
- **App backgrounded / interrupted** (`Scan-Interrupted.png`): persist the TSDF volume, coverage and keyframes to disk. On return, show the sheet: **Resume scan** (only if the ARCore session survived and relocalises; otherwise hide it), **Finish with what I have**, **Discard scan**.

### Keyframes for High detail

Save a keyframe when: sharpness (variance of Laplacian) above threshold, pose stable, and ≥ 10° or ≥ 5 cm from the previous keyframe. Store JPEG + depth + confidence + pose + intrinsics. Cap ~300 frames. _placeholder_

## 3. Finish

- Finish is enabled once coverage ≥ _placeholder_ 15%.
- **Full object** under _placeholder_ 70% overall, or any band < 50% → **Incomplete coverage** sheet (`Finish-Incomplete.png`): dial + band bars, "Keep scanning" (primary) / "Finish anyway" (outline), tip about One side. One-side scans skip this check.
- Then **Choose your result** sheet (`Finish-Mode.png`): radio cards **Quick result** ("Ready in ~5 s") and **High detail** ("About N min on this phone"; badge "Best for small parts" when the box diagonal < _placeholder_ 200 mm). "Remember my choice" checkbox. Note: "Photos are kept, so a Quick result can be upgraded later." Respect Settings › Default result (skip the sheet unless "Ask each time").

## 4. Reconstruction

- **Live:** TSDF fusion of ARCore raw depth (weighted by confidence) inside the box; coarse marching-cubes preview at ≥ 5 Hz.
- **Quick result:** final extraction at voxel size `clamp(D/250, 1 mm, 10 mm)`. _placeholder_
- **High detail:** re-fuse from saved keyframes at `clamp(D/600, 0.4 mm, 4 mm)` with depth refinement; stages shown to the user: Aligning photos → Depth maps → Building fine mesh → Cleanup & watertight. _placeholder_ (treat the exact High detail method as a research spike; report findings before committing.)
- **Cleanup:** remove table plane, keep the largest connected component, optional watertight hole fill (Settings), smoothing level Off / Low / Medium / High.
- Output in **millimetres**, Z-up, object resting on Z = 0, centred on X/Y.

## 5. Scale check

- **Auto** (`Scale-Auto.png`): look for a coin lying on the table near the object in the keyframes (circle/ellipse detection on the plane-rectified image). Highlight it with a lime ring + "Coin found". User confirms the reference from a list (US quarter 24.26 mm, nickel 21.21, penny 19.05, dime 17.91, €1 23.25, €2 25.75, …; default by locale). Show measured vs known and the correction %, plus the new W × D × H. Warn if correction is > ±10%.
- **Manual** (`Scale-Manual.png`): tap two points on the mesh (raycast), show the distance, type the real length (mm/in by setting), show the correction %, Apply.
- Ruler auto-detection is a stretch goal; until then "Ruler" in the list switches to the manual flow.
- Applying scale is a uniform scale on the mesh; store `scaleVerified=true` and the reference used.
