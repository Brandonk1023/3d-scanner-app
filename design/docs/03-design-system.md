# 03 · Design system — Direction A "Instrument"

Reference images: `screens/DS-Foundations.png`, `screens/DS-Components.png`, `screens/DS-AR.png`.
Code: `android/theme/`. Tokens: `tokens/tokens.json`.

## Colour

App screens support **Light** and **Dark**; default is **follow system**. Disable Material dynamic colour.

| Token | Light | Dark | Use |
|---|---|---|---|
| `bg` | `#F6F7F5` | `#0E0F10` | Screen background |
| `surface` | `#FFFFFF` | `#17191B` | Cards, sheets, list groups |
| `surface2` | `#ECEEEA` | `#1F2224` | Tonal buttons, inputs, thumbnails area |
| `border` | `#D3D8D1` | `#2D3135` | 1 dp outlines, dividers |
| `text` | `#121416` | `#F2F3F4` | Primary text and icons |
| `textMuted` | `#4E555C` | `#A9AFB6` | Secondary text (passes 4.5:1 on bg and surface) |
| `accent` | `#D4F54A` | `#D4F54A` | **Fill only**: primary button, FAB, selected toggles, captured coverage |
| `accentInk` | `#4D6B00` | `#D4F54A` | Accent used as **text or icon** (lime fails contrast as text on light) |
| `onAccent` | `#12140A` | `#12140A` | Text/icons on accent fill |
| `success` | `#1E7A3C` | `#4ADE80` | Scale verified, checks |
| `warning` | `#9A5B00` | `#FFB547` | Low storage, unchecked scale |
| `danger` | `#C2281B` | `#FF7A6E` | Delete, failures |

Rule: accent fill is reserved for **the one main action on a screen**.

### AR overlay colours (same in both themes)

| Token | Value | Use |
|---|---|---|
| `arGlass` | `#0E0F10` @ 78–80% + 12–14 dp blur | Panels and round buttons over the camera |
| `arHalo` | `#000000` @ 45%, 3–4 dp wider than the stroke | Behind every line, ring and handle |
| `arGuide` / on | `#D4F54A` / `#12140A` | Positive guidance, captured coverage, mesh |
| `arAdvice` / on | `#FFFFFF` / `#111214` | Neutral advice (more light, tap the object) |
| `arWarning` / on | `#FFB547` / `#1F1300` | Too close / too far |
| `arCritical` / on | `#FF6B5E` / `#1A0503` | Tracking lost (also gets a 3 dp inner outline in the on-colour) |

## Typography

Fonts: **Inter Tight** (UI) and **JetBrains Mono** (every number with a unit, file names/extensions, timers, percentages). Both SIL OFL; bundle in `res/font`. Sizes in sp.

| Style | Font | Size / line | Weight | Example |
|---|---|---|---|---|
| displayMono | Mono | 36 / 40 | 700 | `118.6 mm`, coverage `49%` |
| headline | Inter Tight | 28 / 34 | 700 | Screen titles on full-page screens |
| guidance | Inter Tight | 22 / 28 | 700 | AR guidance banner |
| title | Inter Tight | 18–19 / 24 | 600–700 | App bar title, card titles |
| body | Inter Tight | 16 / 24 | 400 | Paragraphs |
| bodySmall | Inter Tight | 14 / 20 | 400 | Secondary lines |
| label | Inter Tight | 14–16 / 20 | 600 | Buttons |
| dataMono | Mono | 13 / 16 | 500 | `W 118.6 · D 92.4 · H 98.0 mm` |
| overline | Mono | 12 / 16, caps, +0.06em | 500 | Section labels (GENERAL, TODAY) |

Must reflow at 200% font scale: no fixed heights on text containers; long buttons wrap to two lines.

## Spacing, shape, size

- Spacing scale (dp): **4, 8, 12, 16, 24, 32, 48**. Screen side margin 16 (lists) or 24 (full-page text).
- Radius: **8** chips, **14** cards / list groups / inputs, **20** panels and FAB, **28** bottom sheets (top corners), **pill** buttons.
- Buttons: 56 dp tall (48 for text buttons); FAB 60–64 dp tall, radius 20.
- Touch targets: **≥ 48 dp** app, **≥ 60 dp** on the scan screen.
- Scan controls live in the bottom ~200 dp (one-handed thumb zone).

## Components (see `DS-Components.png`)

- **Buttons:** Primary (accent fill, onAccent text, bold), Outline (1 dp textMuted border), Tonal (surface2), Text (underlined in light). Disabled: surface2 fill, muted text.
- **Extended FAB "New scan":** accent, plus icon, radius 18–20.
- **Segmented control:** pill track on surface with 1 dp border; selected segment = `text` fill with `bg` text. Used for Shaded / Wireframe, Full object / One side, Smoothing.
- **Status chips (shape + colour, never colour alone):**
  - High detail: accent fill + check icon
  - Quick: 1 dp outline
  - Processing: surface2 + small spinner + percentage
  - Scale verified: success tint + check
  - Scale unchecked: dashed warning outline
- **Gallery card:** thumbnail (surface2, 112 dp), name (15/600), dims in dataMono, status chip.
- **Search field:** 48 dp pill, search icon, placeholder "Search scans".
- **Filter chips:** All / High detail / Quick / Processing; selected = text fill + check.
- **Settings rows:** 56 dp nav rows (label, value, chevron); 64 dp switch rows with subtitle. Switch on = accent track with dark thumb + check.
- **Banner (in-app):** warning tint background, icon, title + body, text action.
- **Staged progress:** 4 segments; done segments full accentInk, current partially filled.
- **Bottom sheet:** surface, 28 dp top radius, drag handle, 20 dp padding.
- **Radio cards:** used for result choice and export format; selected = 2 dp accent border + surface2 fill + filled radio.

## Icons

Line icons, 2 dp stroke, rounded caps, 24 dp. Use **Material Symbols Rounded (outlined, weight 400)** in code; the designs use equivalent custom glyphs. Mapping: close, arrow_back, chevron_right, check, add, more_vert, search, settings, light_mode, speed (move slower), view_in_ar (mesh / cube), crop_free (box), ios_share / upload (export), refresh (reprocess), straighten (ruler), download (save), share, notifications, warning, gps_off (tracking lost), storage, delete, edit, play_arrow, pause, smartphone, info, arrow_upward / arrow_downward.

## Accessibility

- WCAG AA contrast for all text (4.5:1; 3:1 for ≥ 24 sp bold). Token pairs above are chosen to pass; don't put lime text on light backgrounds.
- **Never colour alone**: coverage uses solid vs dashed; chips use icon/outline differences; guidance has unique icon + haptic per message.
- Every icon-only button has a content description (see `strings.xml`).
- Respect system "remove animations": no pulsing/spinning — use static states.
- TalkBack: announce guidance changes as a polite live region; announce coverage milestones ("Low angles done").
