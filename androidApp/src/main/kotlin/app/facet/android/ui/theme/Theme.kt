package app.facet.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import app.facet.settings.ThemeMode

// ThemeMode lives in shared (app.facet.settings) so both platforms persist the same setting.

private fun FacetColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accent,
        onPrimaryContainer = onAccent,
        secondary = text,
        onSecondary = bg,
        secondaryContainer = surface2,
        onSecondaryContainer = text,
        tertiary = accentInk,
        background = bg,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surface2,
        onSurfaceVariant = textMuted,
        surfaceContainerLowest = bg,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface2,
        surfaceContainerHighest = surface2,
        outline = textMuted,
        outlineVariant = border,
        error = danger,
        onError = if (isDark) onAccent else surface,
        errorContainer = dangerContainer,
        onErrorContainer = danger,
    )
}

/**
 * App theme. Dynamic colour is intentionally not used.
 * Camera / AR screens should call `FacetTheme(themeMode = ThemeMode.DARK)` (and use [ArColors] for overlays).
 */
@Composable
fun FacetTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(themeMode)
    val colors = if (dark) DarkFacetColors else LightFacetColors
    CompositionLocalProvider(
        LocalFacetColors provides colors,
        LocalFacetType provides FacetType(),
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = FacetTypography,
            shapes = FacetShapes,
            content = content,
        )
    }
}

/** Whether [themeMode] resolves to dark right now. */
@Composable
fun isDarkTheme(themeMode: ThemeMode): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** Access with `Facet.colors.accent`, `Facet.type.dataMono`, etc. */
object Facet {
    val colors: FacetColors
        @Composable @ReadOnlyComposable get() = LocalFacetColors.current
    val type: FacetType
        @Composable @ReadOnlyComposable get() = LocalFacetType.current
}
