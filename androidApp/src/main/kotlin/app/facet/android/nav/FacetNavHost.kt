package app.facet.android.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.facet.android.R
import app.facet.android.appContainer
import app.facet.android.ui.device.DeviceCheckScreen
import app.facet.android.ui.export.ExportScreen
import app.facet.android.ui.gallery.GalleryScreen
import app.facet.android.ui.onboarding.ONBOARDING_PAGES
import app.facet.android.ui.onboarding.OnboardingScreen
import app.facet.android.ui.permission.CameraPermissionScreen
import app.facet.android.ui.permission.hasCameraPermission
import app.facet.android.ui.settings.SettingsScreen
import app.facet.android.ui.stub.StubLink
import app.facet.android.ui.stub.StubScreen
import app.facet.android.ui.theme.FacetTheme
import app.facet.android.ui.viewer.ViewerScreen
import app.facet.data.ScanStatus
import app.facet.platform.DeviceSupport
import app.facet.settings.ThemeMode

/**
 * Navigation graph (design/docs/02-user-flow.md). Phase 1: real screens for first launch, gallery,
 * settings, viewer and export. Scan, processing and scale check stay stubs until Phases 2–7.
 */
@Composable
fun FacetNavHost(
    startAtGallery: Boolean,
    onOnboardingComplete: () -> Unit,
    nav: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val container = appContainer()

    fun goToGalleryFromOnboarding() {
        onOnboardingComplete()
        nav.navigate(Gallery) { popUpTo(nav.graph.id) { inclusive = true } }
    }

    fun startNewScan() {
        if (context.hasCameraPermission()) nav.navigate(Scan) else nav.navigate(CameraPermission(fromOnboarding = false))
    }

    // Returning users: re-run the device check silently; only show it if something changed.
    if (startAtGallery) {
        LaunchedEffect(Unit) {
            val support = runCatching { container.device.check().support }.getOrNull()
            if (support == DeviceSupport.NEEDS_INSTALL_OR_UPDATE || support == DeviceSupport.UNSUPPORTED) {
                nav.navigate(DeviceCheck(fromOnboarding = false))
            }
        }
    }

    NavHost(navController = nav, startDestination = if (startAtGallery) Gallery else Onboarding(1)) {

        composable<Onboarding> { entry ->
            val route = entry.toRoute<Onboarding>()
            val afterOnboarding = {
                if (context.hasCameraPermission()) nav.navigate(DeviceCheck()) else nav.navigate(CameraPermission())
            }
            OnboardingScreen(
                page = route.page,
                tipsOnly = route.tipsOnly,
                onNext = {
                    when {
                        route.tipsOnly -> nav.popBackStack()
                        route.page < ONBOARDING_PAGES -> nav.navigate(Onboarding(route.page + 1))
                        else -> afterOnboarding()
                    }
                },
                onSkip = afterOnboarding,
                onBack = { nav.popBackStack() },
            )
        }

        composable<CameraPermission> { entry ->
            val fromOnboarding = entry.toRoute<CameraPermission>().fromOnboarding
            CameraPermissionScreen(
                onGranted = {
                    if (fromOnboarding) nav.navigate(DeviceCheck()) else nav.navigate(Scan) { popUpTo(Gallery) }
                },
                onNotNow = {
                    // The gallery works without the camera; New scan asks again.
                    if (fromOnboarding) goToGalleryFromOnboarding() else nav.popBackStack()
                },
            )
        }

        composable<DeviceCheck> { entry ->
            val fromOnboarding = entry.toRoute<DeviceCheck>().fromOnboarding
            DeviceCheckScreen(
                onSupported = { if (fromOnboarding) goToGalleryFromOnboarding() else nav.popBackStack() },
                onBack = { nav.popBackStack() },
            )
        }

        composable<Gallery> {
            GalleryScreen(
                onNewScan = ::startNewScan,
                onOpenScan = { scan ->
                    if (scan.status == ScanStatus.PROCESSING) nav.navigate(Processing(scan.id)) else nav.navigate(Viewer(scan.id))
                },
                onReprocess = { nav.navigate(Processing(it.id)) },
                onExport = { nav.navigate(Export(it.id)) },
                onSettings = { nav.navigate(Settings) },
                onTips = { nav.navigate(Onboarding(page = 2, tipsOnly = true)) },
            )
        }

        composable<Settings> {
            SettingsScreen(onBack = { nav.popBackStack() })
        }

        composable<Scan> {
            // Camera / AR screens are always dark. Phase 2 (AR placement) replaces this stub.
            FacetTheme(themeMode = ThemeMode.DARK) {
                StubScreen(
                    title = stringResource(R.string.stub_scan_title),
                    reference = "Placement-Surface.png, Placement-Box.png, A-Capture.png, Capture-TrackingLost.png, " +
                        "Finish-Incomplete.png, Finish-Mode.png, Scan-Interrupted.png",
                    detail = stringResource(R.string.stub_scan_states),
                    onBack = { nav.popBackStack() },
                    links = listOf(
                        StubLink(R.string.mode_quick, primary = true) { nav.navigate(Viewer("mug-01")) { popUpTo(Gallery) } },
                        StubLink(R.string.mode_high) { nav.navigate(Processing("mug-01")) { popUpTo(Gallery) } },
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
            ViewerScreen(
                scanId = scanId,
                onBack = { nav.popBackStack() },
                onExport = { nav.navigate(Export(scanId)) },
                onCheckScale = { nav.navigate(ScaleCheck(scanId)) },
                onHighDetail = { nav.navigate(Processing(scanId)) },
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

        composable<Export> { entry ->
            ExportScreen(entry.toRoute<Export>().scanId, onBack = { nav.popBackStack() })
        }
    }
}
