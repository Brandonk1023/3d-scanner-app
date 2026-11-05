# 05 · Screens

Each section names its reference image in `screens/`. Copy is in `android/res/values/strings.xml`. Light/dark: app screens follow the theme (images show one or the other); camera screens are always dark.

---

## First launch

### Onboarding 1 · Intro — `Onboarding-1.png`
Logo + "Facet", Skip. Illustration (wireframe mug in a coverage ring with a dimension). Page dots 1/3. Headline "Turn real objects into printable models". Body + bold privacy line "Everything is processed on this phone. Nothing is uploaded." Primary **Next**.

### Onboarding 2 · Tips — `Onboarding-2.png`
Back, Skip, dots 2/3, "For the best scan". Four tips with 48 dp icon tiles: Bright, even light · Matte objects scan best (spray/powder for shiny, clear, black) · Move slowly · Walk all the way around (low, mid, high rings). **Next**.

### Onboarding 3 · How it works — `Onboarding-3.png`
Dots 3/3, "How a scan works", numbered steps 1–4 (Place the box · Walk around · Pick your result · Check scale and export). **Get started** → camera permission.

### Camera permission — `Permission-Camera.png`
Icon tile, "Facet needs your camera", privacy explanation, info card linking to Android Settings if permanently denied. **Allow camera** / Not now. If denied: stay here; "Allow" opens app settings when the system won't show the dialog again.

### Device check — `Device-Check.png`, `Device-Unsupported.png`
Checklist: Camera (Allowed) · Depth support (ARCore Depth API available) · Google Play Services for AR (OK / Update required). Update required → **Update in Play Store** (`ArCoreApk.requestInstall`) + Check again. No Depth API → Unsupported screen: "This phone can't scan yet", explanation with device model, "What you can do" card, **See supported phones** (outline), Close app.

## Gallery & settings

### Gallery · Empty — `Gallery-Empty.png`
Top bar: logo, "Facet", Settings icon. Dashed illustration, "No scans yet", body, "Scanning tips" link (re-opens onboarding page 2). Full-width primary **New scan**.

### Gallery — `Gallery-Light.png`, `Gallery-Dark.png`
Search field ("Search scans", matches name). Filter chips All / High detail / Quick / Processing. Sections by date (Today, This week, then month names). 2-column grid of cards: thumbnail, name, `W × D × H mm`, status chip. Extended FAB **New scan** bottom-right. Tap card → viewer (or processing screen if processing). Long-press / overflow → actions sheet.

### Scan actions sheet — `Gallery-Actions.png`
Header: thumbnail, name, dims + triangle count, status chip. Actions: Open · Reprocess in High detail ("About N min · uses the saved photos"; hidden if photos were cleared or already High detail) · Export again · Rename · Delete (danger, confirm dialog).

### Settings — `Settings.png`
- General: Appearance (Follow system / Light / Dark) · Units (Millimetres / Inches)
- Scanning: Default result (Ask each time / Quick / High detail) · Watertight mesh (switch, "Close holes so the model prints") · Smoothing (Off / Low / Medium / High)
- Guidance: Haptic guidance (switch)
- Storage: scan count, total size and free space, stacked bar (models vs saved photos), Clear saved photos (confirm; disables High detail reprocess for those scans)
- About (version, licences, privacy statement) — not drawn; standard list rows.

## Scan

### Placement · Find surface — `Placement-Surface.png`
### Placement · Fit box — `Placement-Box.png`
### Capture — `A-Capture.png`
### Capture · Tracking lost — `Capture-TrackingLost.png`
### Finish · Incomplete coverage — `Finish-Incomplete.png`
### Finish · Choose result — `Finish-Mode.png`
### Scan interrupted — `Scan-Interrupted.png`
All specified in `04-scan-experience.md`.

## Process, review, export

### Processing · High detail — `Processing.png`
App bar (scan name, "High detail"). Faded Quick preview with a progress ring (mono `62%`, "~2 min left"). Stage list with done (accent check), current (spinner, bold), to-do (dashed). **Notify me and leave** (primary, bell) returns to gallery with the card showing "Processing NN%". Cancel processing (text). Footer: "Runs on this phone. You can use other apps meanwhile." Foreground-service notification shows the same progress; on completion post "Mug_01 is ready" → viewer.

### Processing failed — `Processing-Failed.png`
Danger icon tile, "High detail couldn't finish", reason in plain words + "Your scan and photos are safe". "Things that help" card. **Try again** · Use Quick result (outline) · Copy error details (text; copies a technical log to the clipboard).

### 3D viewer — `A-Viewer.png`
App bar: back, name, "Quick result · 86k triangles" / "High detail · 412k triangles", overflow (rename, delete, export again). Viewport (rounded 20, surface): orbit/pinch/two-finger pan, Shaded / Wireframe segmented, Reset view, axis triad, hint "Drag to orbit · pinch to zoom", dimension lines with mono labels. Three tiles Width / Depth / Height in mm (or inches). Row **Check scale** (subtitle: "Not verified · measure a ruler or coin" or "Verified against …"). Buttons: **High detail** (outline; reprocess; hidden if already High detail) and **Export** (primary).

### Scale check · Auto — `Scale-Auto.png`
Viewer with the detected coin ringed + "Coin found". Sheet: Reference dropdown, tiles "In the scan 24.10 mm" / "Correction +0.66%", new size line, **Apply scale**, "Measure manually instead". If nothing found: go straight to manual with a one-line note.

### Scale check · Manual — `Scale-Manual.png`
Hint pill "Tap two points on a known edge". Points draggable after placing. Sheet: "Real length between the points" mono input with unit suffix, "Measured 96.4 mm · +1.66%", **Apply scale**, Reset points.

### Export — `Export.png`
Small preview. Sheet: File name (input + extension suffix) · format radio cards **STL** ("Mesh only, in millimetres. Works everywhere.", size) and **3MF** ("Keeps units and name. Best for Bambu Studio, PrusaSlicer.", size) · dashed row "OBJ · PLY · GLB · USDZ — Coming later" (not tappable) · **Save** (outline → MediaStore Downloads/Facet, snackbar "Saved to Downloads/Facet/Mug_01.3mf" + Open) and **Share** (primary → sharesheet). Remember the last format.

### Low storage — `Low-Storage.png`
Shown before Start scan if free space < _placeholder_ 500 MB and before High detail if free < estimated need (~2 GB _placeholder_). Warning icon tile, title, explanation with numbers, bar showing free vs needed, note about reclaimable saved photos. **Free up space** (→ Settings › Storage) · Keep Quick result / Scan anyway (context) · Cancel.
