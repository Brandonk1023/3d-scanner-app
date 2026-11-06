package app.facet.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.x contrast for every text and icon colour pair the app draws (design/docs/03 › Accessibility).
 * Targets: 4.5:1 body text, 3:1 large text (≥ 24 sp, or ≥ 18.5 sp bold) and UI parts/graphics needed
 * to understand something. The table lists where each pair is used; keep it in step with the screens.
 * The full table is written to build/reports/contrast.txt.
 */
class ContrastTest {

    private enum class Kind(val min: Double, val label: String) {
        TEXT(4.5, "text"),
        LARGE(3.0, "large text"),
        UI(3.0, "UI part / icon"),

        /** Listed for the record, not asserted: the part is also identified by its text or icon. */
        DECORATIVE(0.0, "decorative"),
    }

    private data class Check(val palette: String, val fg: String, val bg: String, val kind: Kind, val where: String, val fgColor: Color, val bgColor: Color) {
        val ratio = contrast(over(fgColor, bgColor), bgColor.opaque())
        val passes = ratio + 1e-9 >= kind.min
    }

    /** Pairs drawn with the app theme, checked in light and in dark. */
    private fun themePairs(name: String, c: FacetColors): List<Check> {
        fun p(fg: String, bg: String, kind: Kind, where: String, f: Color, b: Color) = Check(name, fg, bg, kind, where, f, b)
        return listOf(
            p("text", "bg", Kind.TEXT, "Headlines, body, app bar, icons on screens", c.text, c.bg),
            p("text", "surface", Kind.TEXT, "Cards, list rows, sheets", c.text, c.surface),
            p("text", "surface2", Kind.TEXT, "Tonal tiles, thumbnails area, dialog text, Processing chip", c.text, c.surface2),
            p("textMuted", "bg", Kind.TEXT, "Secondary text, section labels, Skip", c.textMuted, c.bg),
            p("textMuted", "surface", Kind.TEXT, "Row values, subtitles, placeholder, chevrons", c.textMuted, c.surface),
            p("textMuted", "surface2", Kind.TEXT, "Dialog Cancel, file-name extension, info card icon", c.textMuted, c.surface2),
            p("accentInk", "bg", Kind.TEXT, "Text links (Scanning tips, Cancel processing, Copy error details), step numbers", c.accentInk, c.bg),
            p("accentInk", "surface", Kind.TEXT, "Android Settings link, ruler icon, selected radio dot", c.accentInk, c.surface),
            p("accentInk", "surface2", Kind.UI, "Tip icons, selected radio dot on selected card, Processing chip arc", c.accentInk, c.surface2),
            p("onAccent", "accent", Kind.TEXT, "Primary buttons, FAB, High detail chip, switch thumb, done-stage check", c.onAccent, c.accent),
            p("accent", "onAccent", Kind.UI, "Check icon on the switch thumb", c.accent, c.onAccent),
            p("bg", "text", Kind.TEXT, "Selected segment and filter chip labels, snackbar text", c.bg, c.text),
            p("snackbarAction", "text", Kind.TEXT, "Snackbar action (accent on dark container, olive ink on light)", if (c.isDark) LightFacetColors.accentInk else c.accent, c.text),
            p("textMuted", "surface2", Kind.UI, "Unchecked switch thumb and outline on its track", c.textMuted, c.surface2),
            p("success", "surface", Kind.UI, "Scale verified check (viewer)", c.success, c.surface),
            p("success", "successContainer", Kind.UI, "Device check OK mark", c.success, c.successContainer),
            p("warning", "surface", Kind.TEXT, "Device check warning status text", c.warning, c.surface),
            p("warning", "warningContainer", Kind.UI, "Low storage icon tile", c.warning, c.warningContainer),
            p("warning", "surface2", Kind.UI, "Low storage bar fill on its track", c.warning, c.surface2),
            p("danger", "surface", Kind.TEXT, "Delete row, Delete menu item, device check error text", c.danger, c.surface),
            p("danger", "surface2", Kind.TEXT, "Destructive dialog button (dialog container)", c.danger, c.surface2),
            p("danger", "dangerContainer", Kind.UI, "Processing failed icon tile, device check error mark", c.danger, c.dangerContainer),
            p("text", "bg", Kind.UI, "Quick chip outline, selected filter chip, low-storage 'needed' mark", c.text, c.bg),
            p("accentInk", "bg", Kind.UI, "Processing: current-stage spinner", c.accentInk, c.bg),
            p("accentInk", "surface", Kind.UI, "Processing: progress ring arc", c.accentInk, c.surface),
            // Decorative by decision (Phase 8): outlines of cards, search field, segmented track and
            // filter chips, each also identified by text or icon. Never use border for text or icons.
            p("border", "surface", Kind.DECORATIVE, "Card and list-group outlines, dividers", c.border, c.surface),
            p("border", "bg", Kind.DECORATIVE, "Search field, filter chips, segmented track, page dots", c.border, c.bg),
        )
    }

    /**
     * Over the camera, always dark. Glass panels are 78 % #0E0F10 over a camera image that can be
     * anything: checked against the worst case, glass over pure white.
     */
    private fun arPairs(): List<Check> {
        val glassOnWhite = over(ArColors.glass, Color.White)
        val glassOnBlack = over(ArColors.glass, Color.Black)
        val segment = over(Color.White.copy(alpha = 0.08f), glassOnWhite)
        val sheet = DarkFacetColors.surface
        fun p(fg: String, bg: String, kind: Kind, where: String, f: Color, b: Color) = Check("ar", fg, bg, kind, where, f, b)
        return listOf(
            p("onGuide", "guide", Kind.LARGE, "Guidance banner: Move slower, Higher / Lower angles (22 sp bold)", ArColors.onGuide, ArColors.guide),
            p("onAdvice", "advice", Kind.LARGE, "Guidance banner: More light, Tap the object; Finish label; active round buttons", ArColors.onAdvice, ArColors.advice),
            p("onWarning", "warning", Kind.LARGE, "Guidance banner: Too close / Too far", ArColors.onWarning, ArColors.warning),
            p("onCritical", "critical", Kind.LARGE, "Guidance banner: Tracking lost", ArColors.onCritical, ArColors.critical),
            p("onGuide", "guide", Kind.UI, "Surface found check", ArColors.onGuide, ArColors.guide),
            p("white", "glass/white", Kind.TEXT, "Glass pills, panels, timer, coverage %, round-button icons", Color.White, glassOnWhite),
            p("textSecondary", "glass/white", Kind.TEXT, "Coverage label, band labels, panel tips", ArColors.textSecondary, glassOnWhite),
            p("guide", "glass/white", Kind.UI, "Band complete check, ring-complete toast check, captured coverage", ArColors.guide, glassOnWhite),
            p("guide", "glass/black", Kind.UI, "Same, over a dark scene", ArColors.guide, glassOnBlack),
            p("white", "segment/glass", Kind.TEXT, "Scan type segments (unselected)", Color.White, segment),
            p("warning", "dark surface", Kind.UI, "Incomplete-coverage warning icon in the Finish sheet", ArColors.warning, sheet),
        )
    }

    private val allChecks: List<Check> by lazy {
        themePairs("light", LightFacetColors) + themePairs("dark", DarkFacetColors) + arPairs()
    }

    @Test
    fun everyUsedPairMeetsWcagAa() {
        val report = buildString {
            appendLine("palette  fg on bg  ratio  target  result  where")
            allChecks.forEach {
                appendLine(
                    String.format(
                        Locale.ROOT, "%-6s %-15s on %-17s %5.2f:1  %-15s %-4s  %s",
                        it.palette, it.fg, it.bg, it.ratio, "${it.kind.min}:1 ${it.kind.label}", if (it.kind == Kind.DECORATIVE) "-" else if (it.passes) "ok" else "FAIL", it.where,
                    ),
                )
            }
        }
        File("build/reports").mkdirs()
        File("build/reports/contrast.txt").writeText(report)
        val failures = allChecks.filterNot { it.passes }
        assertTrue(
            "Contrast below WCAG AA:\n" + failures.joinToString("\n") {
                String.format(Locale.ROOT, "  %s %s on %s = %.2f:1 (needs %.1f:1) — %s", it.palette, it.fg, it.bg, it.ratio, it.kind.min, it.where)
            },
            failures.isEmpty(),
        )
    }

    /** Color.kt and ArColors.kt must mirror design/tokens/tokens.json, the shared source of truth. */
    @Test
    fun kotlinColoursMatchTokens() {
        val path = System.getProperty("facet.tokens") ?: "../design/tokens/tokens.json"
        val color = Json.parseToJsonElement(File(path).readText()).jsonObject.getValue("color").jsonObject
        fun check(palette: String, tokens: JsonObject, kotlin: Map<String, Color>) {
            kotlin.forEach { (name, value) ->
                val token = tokens[name] ?: error("$palette.$name missing from tokens.json")
                val expected = when (token) {
                    is JsonPrimitive -> parse(token.content)
                    else -> {
                        val o = token.jsonObject
                        parse(o.getValue("value").jsonPrimitive.content).copy(alpha = o["alpha"]?.jsonPrimitive?.double?.toFloat() ?: 1f)
                    }
                }
                assertEquals("$palette.$name", expected.toArgb(), value.toArgb())
            }
        }
        check("light", color.getValue("light").jsonObject, LightFacetColors.asMap())
        check("dark", color.getValue("dark").jsonObject, DarkFacetColors.asMap())
        check(
            "ar", color.getValue("ar").jsonObject,
            mapOf(
                "glass" to ArColors.glass, "glassBorder" to ArColors.glassBorder, "halo" to ArColors.halo,
                "guide" to ArColors.guide, "onGuide" to ArColors.onGuide, "advice" to ArColors.advice, "onAdvice" to ArColors.onAdvice,
                "warning" to ArColors.warning, "onWarning" to ArColors.onWarning, "critical" to ArColors.critical, "onCritical" to ArColors.onCritical,
                "missing" to ArColors.missing, "textSecondary" to ArColors.textSecondary, "recording" to ArColors.recording,
            ),
        )
    }

    private fun FacetColors.asMap() = mapOf(
        "bg" to bg, "surface" to surface, "surface2" to surface2, "border" to border, "text" to text, "textMuted" to textMuted,
        "accent" to accent, "accentInk" to accentInk, "onAccent" to onAccent, "success" to success, "successContainer" to successContainer,
        "warning" to warning, "warningContainer" to warningContainer, "danger" to danger, "dangerContainer" to dangerContainer,
    )

    private companion object {
        fun parse(hex: String): Color = Color(("FF" + hex.removePrefix("#")).toLong(16))

        fun Color.opaque() = copy(alpha = 1f)

        /** [fg] (possibly translucent) composited over [bg]. */
        fun over(fg: Color, bg: Color): Color {
            val a = fg.alpha
            return Color(fg.red * a + bg.red * (1 - a), fg.green * a + bg.green * (1 - a), fg.blue * a + bg.blue * (1 - a))
        }

        fun luminance(c: Color): Double {
            fun ch(v: Float): Double = if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
        }

        fun contrast(a: Color, b: Color): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }
    }
}
