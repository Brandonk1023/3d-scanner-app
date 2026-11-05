package app.facet.ui.theme // TODO: change to your package

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.facet.R // TODO: change to your package

/*
 * Add these files to res/font (both families are SIL Open Font License):
 *   inter_tight_regular.ttf, inter_tight_semibold.ttf, inter_tight_bold.ttf
 *   jetbrains_mono_medium.ttf, jetbrains_mono_bold.ttf
 */
val InterTight = FontFamily(
    Font(R.font.inter_tight_regular, FontWeight.Normal),
    Font(R.font.inter_tight_semibold, FontWeight.SemiBold),
    Font(R.font.inter_tight_bold, FontWeight.Bold),
)

val JetBrainsMono = FontFamily(
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/** Facet-specific styles that don't map onto Material roles. */
@Immutable
data class FacetType(
    val displayMono: TextStyle = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp),
    val guidance: TextStyle = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    val dataMono: TextStyle = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 16.sp),
    val valueMono: TextStyle = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 22.sp),
    val overline: TextStyle = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.06.em),
)

val LocalFacetType = staticCompositionLocalOf { FacetType() }

/** Material roles mapped to the Facet scale. */
val FacetTypography = Typography(
    headlineMedium = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = InterTight, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp),
)
