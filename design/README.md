# Facet (placeholder name) — design handoff for Claude Code

This folder is the complete UX/UI handoff for the phone 3D scanning app. Drop it into the repo as `design/` and point Claude Code at `BUILD_PROMPT.md`.

## How to use it

1. Create an empty repo (Claude Code sets up the Kotlin Multiplatform project in Phase 0) and copy this whole folder to `design/` at the repo root.
2. Open the repo in VS Code with Claude Code.
3. Paste the contents of `BUILD_PROMPT.md` as your first message (or say: *"Read design/BUILD_PROMPT.md and follow it."*).
4. Claude Code works phase by phase and stops after each phase for you to test on the Galaxy S24 Ultra.

## What's inside

| Path | What it is |
|---|---|
| `BUILD_PROMPT.md` | The prompt for Claude Code: goals, stack, architecture, phases, rules |
| `docs/01-product-spec.md` | Product summary and every decision made during design |
| `docs/02-user-flow.md` | Flow diagram, navigation routes, screen inventory |
| `docs/03-design-system.md` | Colour, type, spacing, shape, components, accessibility rules |
| `docs/04-scan-experience.md` | The scanning screen in detail: coverage model, guidance priority, haptics, thresholds |
| `docs/05-screens.md` | Screen-by-screen spec with states, actions and copy |
| `docs/06-open-questions.md` | Placeholders and decisions still to make |
| `docs/07-multiplatform.md` | Kotlin Multiplatform + shared C++ structure for Android now, iOS next |
| `tokens/tokens.json` | Design tokens (light, dark, AR overlay) — the source of truth for colours |
| `android/theme/*.kt` | Ready-to-use Jetpack Compose theme: colours, type, shapes, spacing, AR colours |
| `android/scan/Haptics.kt` | Android vibration patterns for each guidance message (iOS mirrors them) |
| `android/res/values/strings.xml` | All UI copy from the designs |
| `assets/logo-placeholder.svg` | Placeholder logo |
| `screens/*.png` | Rendered reference image of every screen and design-system board |
| `reference-html/*.dc.html` | The design canvas sources (exact px values, if a PNG is ambiguous) |

## Source of truth, in order

1. `docs/*.md` and `tokens/tokens.json` (intent and rules)
2. `screens/*.png` (how it should look)
3. `reference-html/` (exact measurements; ignore the `<x-dc>` / `support.js` wrapper — it's design-tool markup, not app code)

Screen sizes are drawn at 390 × 844 dp. Lay out with Compose constraints, not fixed pixel positions.
