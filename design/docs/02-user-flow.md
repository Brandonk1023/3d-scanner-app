# 02 · User flow and navigation

Reference image: `screens/Main.png` (full flow map).

```mermaid
flowchart LR
  subgraph First launch
    A[Onboarding 1-3] --> B[Camera permission] --> C{Device check}
    C -- needs update --> D[Install / update ARCore] --> C
    C -- not supported --> E[Unsupported device]
  end
  C -- supported --> G[Gallery]
  G --> S[Settings]
  G -- New scan --> P1[Placement: find surface] --> P2[Placement: fit box + scan type] --> CAP[Capture]
  CAP -- Finish, coverage below threshold --> INC[Incomplete coverage sheet]
  INC -- Keep scanning --> CAP
  INC -- Finish anyway --> M
  CAP -- Finish --> M{Choose result}
  M -- Quick --> V[3D viewer]
  M -- High detail --> PR[Processing] --> V
  PR -- leave --> N[Notification: ready] --> V
  PR -- fails --> PF[Processing failed]
  V --> SC[Scale check: auto / manual] --> V
  V --> X[Export sheet] --> SH[Save / Share] --> G
  G -- open scan --> V
```

## Routes

| Route | Screen(s) | Reference images |
|---|---|---|
| `onboarding/{page}` | 3 pages | `Onboarding-1/2/3.png` |
| `permission/camera` | Camera permission | `Permission-Camera.png` |
| `device-check` | ARCore checks + update | `Device-Check.png`, `Device-Unsupported.png` |
| `gallery` | Home | `Gallery-Empty.png`, `Gallery-Light.png`, `Gallery-Dark.png`, `Gallery-Actions.png` |
| `settings` | Settings | `Settings.png` |
| `scan` | Placement → capture → finish (one route, internal state machine) | `Placement-Surface.png`, `Placement-Box.png`, `A-Capture.png`, `Capture-TrackingLost.png`, `Finish-Incomplete.png`, `Finish-Mode.png`, `Scan-Interrupted.png` |
| `processing/{scanId}` | High detail progress / failure | `Processing.png`, `Processing-Failed.png`, `Low-Storage.png` |
| `viewer/{scanId}` | 3D viewer | `A-Viewer.png` |
| `viewer/{scanId}/scale` | Scale check | `Scale-Auto.png`, `Scale-Manual.png` |
| `viewer/{scanId}/export` | Export sheet | `Export.png` |

## Launch logic

- First launch: onboarding → camera permission → device check → gallery. Store `onboardingDone` in DataStore.
- Every launch: re-run the device check silently; only show it if something changed (e.g. ARCore needs an update).
- Notification tap for a finished High detail scan deep-links to `viewer/{scanId}`.
- If the app was killed mid-scan, the next launch offers the **Scan interrupted** sheet.

## Edge and error states (where they appear)

| State | Where | Image |
|---|---|---|
| Low storage | Before Start scan and before High detail | `Low-Storage.png` |
| Processing failed | Processing route | `Processing-Failed.png` |
| Incomplete coverage | Finish on a Full-object scan below threshold | `Finish-Incomplete.png` |
| Interrupted mid-scan | Returning to the app / next launch | `Scan-Interrupted.png` |
| Tracking lost and other live guidance | Capture | `Capture-TrackingLost.png`, `DS-AR.png` |
