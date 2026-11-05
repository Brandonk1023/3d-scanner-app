package app.facet.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.facet.android.nav.FacetNavHost
import app.facet.android.ui.theme.FacetTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Phase 1: themeMode comes from AppSettings (DataStore); default follows the system.
            FacetTheme {
                FacetNavHost()
            }
        }
    }
}
