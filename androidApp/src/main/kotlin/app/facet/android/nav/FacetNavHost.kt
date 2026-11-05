package app.facet.android.nav

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.facet.FacetInfo
import app.facet.android.R
import app.facet.android.ui.stub.StubLink
import app.facet.android.ui.stub.StubScreen
import app.facet.android.ui.theme.FacetTheme
import app.facet.mesh.AndroidMeshEngine
import app.facet.settings.ThemeMode

/**
 * Navigation graph. Phase 0: every destination is a stub you can click through on the phone.
 * Phase 1 replaces stubs with real screens (onboarding/permission/device check/gallery/settings/viewer UI).
 */
@Composable
fun FacetNavHost(nav: NavHostController = rememberNavController()) {
    // Phase 1: start at Gallery when AppSettings.onboardingDone, else Onboarding(1).
    NavHost(navController = nav, startDestination = Onboarding(1)) {

        composable<Onboarding> { entry ->
            val page = entry.toRoute<Onboarding>().page
            val title = when (page) {
                1 -> R.string.onb1_title
                2 -> R.string.onb2_title
                else -> R.string.onb3_title
            }
            val next = if (page < Onboarding.PAGES) {
                StubLink(R.string.action_next, primary = true) { nav.navigate(Onboarding(page + 1)) }
            } else {
                StubLink(R.string.onb_get_started, primary = true) { nav.navigate(CameraPermission) }
            }
            StubScreen(
                title = stringResource(title),
                reference = "Onboarding-$page.png",
                onBack = if (page > 1) ({ nav.popBackStack() }) else null,
                links = listOf(next, StubLink(R.string.action_skip) { nav.navigate(CameraPermission) }),
            )
        }

        composable<CameraPermission> {
            StubScreen(
                title = stringResource(R.string.perm_cam_title, stringResource(R.string.app_name)),
                reference = "Permission-Camera.png",
                onBack = { nav.popBackStack() },
                links = listOf(
                    StubLink(R.string.perm_cam_allow, primary = true) { nav.navigate(DeviceCheck) },
                    StubLink(R.string.perm_cam_later) {},
                ),
            )
        }

        composable<DeviceCheck> {
            val activity = LocalContext.current as? Activity
            var unsupported by rememberSaveable { mutableStateOf(false) }
            if (unsupported) {
                StubScreen(
                    title = stringResource(R.string.unsupported_title),
                    reference = "Device-Unsupported.png",
                    onBack = { unsupported = false },
                    links = listOf(StubLink(R.string.unsupported_close) { activity?.finish() }),
                )
            } else {
                StubScreen(
                    title = stringResource(R.string.device_title),
                    reference = "Device-Check.png",
                    onBack = { nav.popBackStack() },
                    links = listOf(
                        StubLink(R.string.stub_device_supported, primary = true) {
                            nav.navigate(Gallery) { popUpTo(nav.graph.id) { inclusive = true } }
                        },
                        StubLink(R.string.stub_device_unsupported) { unsupported = true },
                    ),
                )
            }
        }

        composable<Gallery> {
            // Proves the chain Compose → shared (Kotlin) → JNI → C++ core.
            val failedFormat = stringResource(R.string.stub_wiring_failed)
            val wiring = remember {
                runCatching { FacetInfo.wiringCheck(AndroidMeshEngine()) }
                    .getOrElse { String.format(failedFormat, it.message) }
            }
            StubScreen(
                title = stringResource(R.string.app_name),
                reference = "Gallery-Empty.png, Gallery-Light.png, Gallery-Dark.png, Gallery-Actions.png",
                detailLabel = stringResource(R.string.stub_wiring),
                detail = wiring,
                links = listOf(
                    StubLink(R.string.new_scan, primary = true) { nav.navigate(Scan) },
                    StubLink(R.string.stub_open_sample) { nav.navigate(Viewer("sample")) },
                    StubLink(R.string.stub_open_processing) { nav.navigate(Processing("sample")) },
                    StubLink(R.string.settings_title) { nav.navigate(Settings) },
                ),
            )
        }

        composable<Settings> {
            StubScreen(
                title = stringResource(R.string.settings_title),
                reference = "Settings.png",
                onBack = { nav.popBackStack() },
            )
        }

        composable<Scan> {
            // Camera / AR screens are always dark.
            FacetTheme(themeMode = ThemeMode.DARK) {
                StubScreen(
                    title = stringResource(R.string.stub_scan_title),
                    reference = "Placement-Surface.png, Placement-Box.png, A-Capture.png, Capture-TrackingLost.png, " +
                        "Finish-Incomplete.png, Finish-Mode.png, Scan-Interrupted.png",
                    detail = stringResource(R.string.stub_scan_states),
                    onBack = { nav.popBackStack() },
                    links = listOf(
                        StubLink(R.string.mode_quick, primary = true) {
                            nav.navigate(Viewer("new")) { popUpTo(Gallery) }
                        },
                        StubLink(R.string.mode_high) {
                            nav.navigate(Processing("new")) { popUpTo(Gallery) }
                        },
                        StubLink(R.string.discard_scan) { nav.popBackStack() },
                    ),
                )
            }
        }

        composable<Processing> { entry ->
            val scanId = entry.toRoute<Processing>().scanId
            StubScreen(
                title = stringResource(R.string.stub_processing_title, scanId),
                reference = "Processing.png, Processing-Failed.png, Low-Storage.png",
                onBack = { nav.popBackStack() },
                links = listOf(
                    StubLink(R.string.stub_processing_done, primary = true) {
                        nav.navigate(Viewer(scanId)) { popUpTo(Gallery) }
                    },
                    StubLink(R.string.proc_notify) { nav.popBackStack(Gallery, inclusive = false) },
                ),
            )
        }

        composable<Viewer> { entry ->
            val scanId = entry.toRoute<Viewer>().scanId
            StubScreen(
                title = stringResource(R.string.stub_viewer_title, scanId),
                reference = "A-Viewer.png",
                onBack = { nav.popBackStack() },
                links = listOf(
                    StubLink(R.string.btn_export, primary = true) { nav.navigate(Export(scanId)) },
                    StubLink(R.string.check_scale) { nav.navigate(ScaleCheck(scanId)) },
                    StubLink(R.string.mode_high) { nav.navigate(Processing(scanId)) },
                ),
            )
        }

        composable<ScaleCheck> {
            StubScreen(
                title = stringResource(R.string.check_scale),
                reference = "Scale-Auto.png, Scale-Manual.png",
                onBack = { nav.popBackStack() },
                links = listOf(StubLink(R.string.apply_scale, primary = true) { nav.popBackStack() }),
            )
        }

        composable<Export> {
            StubScreen(
                title = stringResource(R.string.export_title),
                reference = "Export.png",
                onBack = { nav.popBackStack() },
                links = listOf(
                    StubLink(R.string.share, primary = true) { nav.popBackStack(Gallery, inclusive = false) },
                    StubLink(R.string.save) { nav.popBackStack(Gallery, inclusive = false) },
                ),
            )
        }
    }
}
