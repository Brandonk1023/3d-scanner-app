package app.facet.android

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.facet.android.nav.FacetNavHost
import app.facet.android.ui.theme.Facet
import app.facet.android.ui.theme.FacetTheme
import app.facet.android.ui.theme.isDarkTheme
import app.facet.settings.AppSettings
import app.facet.settings.SettingsRepository
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settingsRepo = appContainer.settings
        setContent {
            val settings by settingsRepo.settings.collectAsStateWithLifecycle(initialValue = null as AppSettings?)
            // DataStore takes a few ms on cold start; only the window background shows meanwhile.
            settings?.let { FacetContent(it, settingsRepo) }
        }
    }

    @Composable
    private fun FacetContent(settings: AppSettings, settingsRepo: SettingsRepository) {
        val dark = isDarkTheme(settings.themeMode)
        DisposableEffect(dark) {
            // Status/navigation bar icons follow the app theme, not just the system setting.
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
                navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
            )
            onDispose {}
        }
        // Decided once per launch: changing NavHost's start destination later would reset the graph.
        val startAtGallery = rememberSaveable { settings.onboardingDone }
        val scope = rememberCoroutineScope()
        FacetTheme(themeMode = settings.themeMode) {
            Box(Modifier.fillMaxSize().background(Facet.colors.bg)) {
                FacetNavHost(
                    startAtGallery = startAtGallery,
                    onOnboardingComplete = { scope.launch { settingsRepo.update { it.copy(onboardingDone = true) } } },
                )
            }
        }
    }

    private companion object {
        // Same as androidx.activity's defaults for 3-button navigation.
        val LIGHT_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
