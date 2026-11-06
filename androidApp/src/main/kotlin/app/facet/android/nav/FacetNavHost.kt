package app.facet.android.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.facet.android.appContainer
import app.facet.android.ui.components.LocalReducedMotion
import app.facet.android.ui.device.DeviceCheckScreen
import app.facet.android.ui.export.ExportScreen
import app.facet.android.ui.gallery.GalleryScreen
import app.facet.android.ui.onboarding.ONBOARDING_PAGES
import app.facet.android.ui.onboarding.OnboardingScreen
import app.facet.android.ui.permission.CameraPermissionScreen
import app.facet.android.ui.permission.hasCameraPermission
import app.facet.android.processing.DeepLink
import app.facet.android.processing.DeepLinkTarget
import app.facet.android.ui.processing.ProcessingScreen
import app.facet.android.ui.scan.ScanScreen
import app.facet.android.ui.settings.NativeLibrariesScreen
import app.facet.android.ui.scale.ScaleCheckScreen
import app.facet.android.ui.settings.SettingsScreen
import app.facet.android.ui.theme.FacetTheme
import app.facet.android.ui.viewer.ViewerScreen
import app.facet.data.ScanStatus
import app.facet.scan.ResultMode
import app.facet.platform.DeviceSupport
import app.facet.settings.ThemeMode

/**
 * Navigation graph (design/docs/02-user-flow.md). [deepLink]: a notification tap (High detail ready
 * → viewer, failed → processing), handled once via [onDeepLinkHandled].
 */
@Composable
fun FacetNavHost(
    startAtGallery: Boolean,
    onOnboardingComplete: () -> Unit,
    deepLink: DeepLink? = null,
    onDeepLinkHandled: () -> Unit = {},
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

    // Notification taps (only once onboarding is done: the gallery is then the start screen).
    LaunchedEffect(deepLink) {
        val link = deepLink ?: return@LaunchedEffect
        onDeepLinkHandled()
        if (!startAtGallery) return@LaunchedEffect
        val route: Any = when (link.target) {
            DeepLinkTarget.VIEWER -> Viewer(link.scanId)
            DeepLinkTarget.PROCESSING -> Processing(link.scanId)
        }
        nav.navigate(route) { popUpTo(Gallery) }
    }

    // Remove animations: screens switch without the default cross-fade.
    val reducedMotion = LocalReducedMotion.current
    NavHost(
        navController = nav,
        startDestination = if (startAtGallery) Gallery else Onboarding(1),
        enterTransition = { if (reducedMotion) EnterTransition.None else fadeIn(tween(NAV_FADE_MS)) },
        exitTransition = { if (reducedMotion) ExitTransition.None else fadeOut(tween(NAV_FADE_MS)) },
    ) {

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
                onReprocess = { nav.navigate(Processing(it.id, start = true)) },
                onExport = { nav.navigate(Export(it.id)) },
                onSettings = { nav.navigate(Settings) },
                onTips = { nav.navigate(Onboarding(page = 2, tipsOnly = true)) },
            )
        }

        composable<Settings> {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onNativeLibraries = { nav.navigate(NativeLibraries) },
                onOpenScan = { id -> nav.navigate(Viewer(id)) },
            )
        }

        composable<NativeLibraries> {
            NativeLibrariesScreen(onBack = { nav.popBackStack() })
        }

        composable<Scan> {
            // Camera / AR screens are always dark. Placement → capture → finish.
            FacetTheme(themeMode = ThemeMode.DARK) {
                ScanScreen(
                    onClose = { nav.popBackStack() },
                    onResult = { scanId, mode ->
                        // The Quick result is saved either way; High detail then upgrades it.
                        if (mode == ResultMode.HIGH_DETAIL) {
                            nav.navigate(Processing(scanId, start = true)) { popUpTo(Gallery) }
                        } else {
                            nav.navigate(Viewer(scanId)) { popUpTo(Gallery) }
                        }
                    },
                )
            }
        }

        composable<Processing> { entry ->
            val route = entry.toRoute<Processing>()
            ProcessingScreen(
                scanId = route.scanId,
                start = route.start,
                onBack = { nav.popBackStack() },
                onOpenViewer = { nav.navigate(Viewer(route.scanId)) { popUpTo(Gallery) } },
                onLeave = { if (!nav.popBackStack(Gallery, inclusive = false)) nav.navigate(Gallery) },
                onFreeUpSpace = { nav.navigate(Settings) },
            )
        }

        composable<Viewer> { entry ->
            val scanId = entry.toRoute<Viewer>().scanId
            ViewerScreen(
                scanId = scanId,
                onBack = { nav.popBackStack() },
                onExport = { nav.navigate(Export(scanId)) },
                onCheckScale = { nav.navigate(ScaleCheck(scanId)) },
                onHighDetail = { nav.navigate(Processing(scanId, start = true)) },
            )
        }

        composable<ScaleCheck> { entry ->
            // Manual flow. Coin auto-detection (Scale-Auto.png) needs real keyframes: still to do.
            ScaleCheckScreen(entry.toRoute<ScaleCheck>().scanId, onBack = { nav.popBackStack() })
        }

        composable<Export> { entry ->
            ExportScreen(entry.toRoute<Export>().scanId, onBack = { nav.popBackStack() })
        }
    }
}

/** Navigation Compose's default cross-fade length. */
private const val NAV_FADE_MS = 700
