package app.facet.android.ui.components

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Phase 8 accessibility plumbing: reduced motion, large fonts, haptics gate.

/**
 * True while the system "Remove animations" setting is on (animator duration scale 0).
 * Provided once at the app root; anything that would pulse, spin or slide shows a static state instead.
 */
val LocalReducedMotion = compositionLocalOf { false }

/** Reads and follows Settings.Global.ANIMATOR_DURATION_SCALE (the "Remove animations" switch). */
@Composable
fun rememberSystemReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    var reduced by remember { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = read()
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/** Font scale at which layouts switch to their roomier variants (one column, stacked rows). */
const val LARGE_FONT_SCALE = 1.5f

/** Whether the user's font size is large enough that side-by-side text no longer fits. */
@Composable
fun isLargeFont(): Boolean = LocalDensity.current.fontScale >= LARGE_FONT_SCALE

/**
 * "Working on it" indicator. Spins normally; with reduced motion it is a still three-quarter arc,
 * always next to text that says what is happening.
 */
@Composable
fun BusyIndicator(color: Color, modifier: Modifier = Modifier, strokeWidth: Dp = 4.dp, trackColor: Color = Color.Transparent) {
    if (LocalReducedMotion.current) {
        CircularProgressIndicator(progress = { 0.75f }, modifier = modifier, color = color, strokeWidth = strokeWidth, trackColor = trackColor)
    } else {
        CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = strokeWidth, trackColor = trackColor)
    }
}

/**
 * Settings › Guidance › Haptic guidance gate for Compose's own haptics (box placed, long-press).
 * Scan guidance and scan feedback are gated in the shared CaptureViewModel.
 */
class GatedHapticFeedback(private val inner: HapticFeedback, private val enabled: Boolean) : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
        if (enabled) inner.performHapticFeedback(hapticFeedbackType)
    }
}
