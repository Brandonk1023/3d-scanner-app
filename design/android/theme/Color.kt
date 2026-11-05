package app.facet.ui.theme // TODO: change to your package

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Facet colour tokens. Mirrors design/tokens/tokens.json. */
@Immutable
data class FacetColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val text: Color,
    val textMuted: Color,
    /** Fill only (buttons, FAB, selected states, captured coverage). */
    val accent: Color,
    /** Accent used as text or icon colour. Lime fails contrast as text on light, so light uses a dark olive. */
    val accentInk: Color,
    val onAccent: Color,
    val success: Color,
    val successContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val isDark: Boolean,
)

val LightFacetColors = FacetColors(
    bg = Color(0xFFF6F7F5),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFECEEEA),
    border = Color(0xFFD3D8D1),
    text = Color(0xFF121416),
    textMuted = Color(0xFF4E555C),
    accent = Color(0xFFD4F54A),
    accentInk = Color(0xFF4D6B00),
    onAccent = Color(0xFF12140A),
    success = Color(0xFF1E7A3C),
    successContainer = Color(0xFFE3F3E8),
    warning = Color(0xFF9A5B00),
    warningContainer = Color(0xFFFFF1DC),
    danger = Color(0xFFC2281B),
    dangerContainer = Color(0xFFFDE7E4),
    isDark = false,
)

val DarkFacetColors = FacetColors(
    bg = Color(0xFF0E0F10),
    surface = Color(0xFF17191B),
    surface2 = Color(0xFF1F2224),
    border = Color(0xFF2D3135),
    text = Color(0xFFF2F3F4),
    textMuted = Color(0xFFA9AFB6),
    accent = Color(0xFFD4F54A),
    accentInk = Color(0xFFD4F54A),
    onAccent = Color(0xFF12140A),
    success = Color(0xFF4ADE80),
    successContainer = Color(0xFF14271B),
    warning = Color(0xFFFFB547),
    warningContainer = Color(0xFF2B2010),
    danger = Color(0xFFFF7A6E),
    dangerContainer = Color(0xFF2E1513),
    isDark = true,
)

val LocalFacetColors = staticCompositionLocalOf { DarkFacetColors }
