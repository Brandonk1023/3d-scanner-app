package app.facet.android.nav

import kotlinx.serialization.Serializable

// Type-safe Navigation Compose routes — one per route in design/docs/02-user-flow.md.

/** `onboarding/{page}`, page 1..3. [tipsOnly] = page 2 reopened from the gallery's "Scanning tips". */
@Serializable data class Onboarding(val page: Int = 1, val tipsOnly: Boolean = false)

/** `permission/camera`. [fromOnboarding]: first-launch flow (→ device check) vs. asked from New scan (→ scan). */
@Serializable data class CameraPermission(val fromOnboarding: Boolean = true)

/** `device-check` — ARCore checks, install/update, and the Unsupported device state. */
@Serializable data class DeviceCheck(val fromOnboarding: Boolean = true)

/** `gallery` — home. */
@Serializable data object Gallery

/** `settings` */
@Serializable data object Settings

/** `settings/native-libraries` — licenses of the C++ core's libraries, fonts and models. */
@Serializable data object NativeLibraries

/** `scan` — placement → capture → finish, one route driven by the shared ScanSession state machine. */
@Serializable data object Scan

/** `processing/{scanId}` — High detail progress / failure / low storage. */
@Serializable data class Processing(val scanId: String)

/** `viewer/{scanId}` */
@Serializable data class Viewer(val scanId: String)

/** `viewer/{scanId}/scale` */
@Serializable data class ScaleCheck(val scanId: String)

/** `viewer/{scanId}/export` */
@Serializable data class Export(val scanId: String)
