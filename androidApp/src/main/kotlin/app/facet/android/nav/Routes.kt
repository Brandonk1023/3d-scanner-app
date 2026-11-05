package app.facet.android.nav

import kotlinx.serialization.Serializable

// Type-safe Navigation Compose routes — one per route in design/docs/02-user-flow.md.

/** `onboarding/{page}`, page 1..3. */
@Serializable data class Onboarding(val page: Int = 1) {
    init { require(page in 1..PAGES) }
    companion object { const val PAGES = 3 }
}

/** `permission/camera` */
@Serializable data object CameraPermission

/** `device-check` — ARCore checks, install/update, and the Unsupported device state. */
@Serializable data object DeviceCheck

/** `gallery` — home. */
@Serializable data object Gallery

/** `settings` */
@Serializable data object Settings

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
