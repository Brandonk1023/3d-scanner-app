package app.facet.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Colours for everything drawn over the camera. Identical in light and dark app themes.
 * Every line, ring and handle drawn over the camera gets a [halo] stroke underneath,
 * [haloExtra] wider on each side, so it reads on bright, dark and busy backgrounds.
 */
object ArColors {
    val glass = Color(0xFF0E0F10).copy(alpha = 0.78f)
    val glassBorder = Color.White.copy(alpha = 0.14f)
    val glassBlur = 12.dp

    val halo = Color.Black.copy(alpha = 0.45f)
    val haloExtra = 3.dp

    val guide = Color(0xFFD4F54A)
    val onGuide = Color(0xFF12140A)
    val advice = Color(0xFFFFFFFF)
    val onAdvice = Color(0xFF111214)
    val warning = Color(0xFFFFB547)
    val onWarning = Color(0xFF1F1300)
    val critical = Color(0xFFFF6B5E)
    val onCritical = Color(0xFF1A0503)

    /** Missing coverage: thin dashed outline. */
    val missing = Color.White.copy(alpha = 0.75f)
    val textSecondary = Color(0xFFC9CDD2)
    val recording = Color(0xFFFF5A4E)

    /** Captured coverage stroke width / missing stroke width on the table ring. */
    val ringCapturedWidth = 7.dp
    val ringMissingWidth = 1.5.dp
}
