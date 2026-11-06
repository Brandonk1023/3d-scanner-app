package app.facet.android.ui.onboarding

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import app.facet.android.R
import app.facet.android.ui.components.Headline
import app.facet.android.ui.components.IconTile
import app.facet.android.ui.components.PageDots
import app.facet.android.ui.components.PrimaryButton
import app.facet.android.ui.components.TextLinkButton
import app.facet.android.ui.theme.Facet
import app.facet.android.ui.theme.Radius
import app.facet.android.ui.theme.Space

const val ONBOARDING_PAGES = 3

/**
 * Onboarding pages 1–3 (Onboarding-1/2/3.png). [tipsOnly] = page 2 opened from the gallery's
 * "Scanning tips" link: no dots, no Skip, and the button just closes it.
 */
@Composable
fun OnboardingScreen(
    page: Int,
    tipsOnly: Boolean,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
) {
    val appName = stringResource(R.string.app_name)
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = Space.l),
    ) {
        // Top row: logo (page 1) or back, Skip on the right.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (page == 1) {
                Icon(painterResource(R.drawable.ic_logo), null, tint = Facet.colors.text, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(Space.s))
                Text(appName, style = MaterialTheme.typography.titleLarge, color = Facet.colors.text)
            } else {
                IconButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) {
                    Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back), tint = Facet.colors.text)
                }
            }
            Spacer(Modifier.weight(1f))
            if (!tipsOnly && page < ONBOARDING_PAGES) {
                TextLinkButton(stringResource(R.string.action_skip), onSkip, color = Facet.colors.textMuted)
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            when (page) {
                1 -> {
                    MugIllustration(Modifier.fillMaxWidth().aspectRatio(1.25f).padding(vertical = Space.m))
                    PageDots(1, ONBOARDING_PAGES, Modifier.dotsDescription(1))
                    Headline(stringResource(R.string.onb1_title))
                    Text(
                        stringResource(R.string.onb1_body, appName),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Facet.colors.textMuted,
                    )
                    Text(
                        stringResource(R.string.onb1_privacy),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Facet.colors.text,
                    )
                }
                2 -> {
                    Spacer(Modifier.height(Space.s))
                    if (!tipsOnly) PageDots(2, ONBOARDING_PAGES, Modifier.dotsDescription(2))
                    Headline(stringResource(R.string.onb2_title))
                    Tip(R.drawable.ic_light, R.string.tip_light_title, stringResource(R.string.tip_light_body))
                    Tip(R.drawable.ic_drop, R.string.tip_matte_title, stringResource(R.string.tip_matte_body))
                    Tip(R.drawable.ic_speed, R.string.tip_slow_title, stringResource(R.string.tip_slow_body, appName))
                    Tip(R.drawable.ic_orbit, R.string.tip_around_title, stringResource(R.string.tip_around_body))
                }
                else -> {
                    Spacer(Modifier.height(Space.s))
                    PageDots(3, ONBOARDING_PAGES, Modifier.dotsDescription(3))
                    Headline(stringResource(R.string.onb3_title))
                    Step(1, R.string.step1_title, R.string.step1_body)
                    Step(2, R.string.step2_title, R.string.step2_body)
                    Step(3, R.string.step3_title, R.string.step3_body)
                    Step(4, R.string.step4_title, R.string.step4_body)
                }
            }
            Spacer(Modifier.height(Space.m))
        }

        val label = when {
            tipsOnly -> R.string.action_done
            page < ONBOARDING_PAGES -> R.string.action_next
            else -> R.string.onb_get_started
        }
        PrimaryButton(
            stringResource(label),
            onClick = onNext,
            modifier = Modifier.fillMaxWidth().padding(top = Space.s, bottom = Space.l),
        )
    }
}

@Composable
private fun Modifier.dotsDescription(page: Int): Modifier {
    val text = stringResource(R.string.onb_step_cd, page, ONBOARDING_PAGES)
    return this.semantics { contentDescription = text }
}

@Composable
private fun Tip(@DrawableRes icon: Int, @StringRes title: Int, body: String) {
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        IconTile(icon, size = 48.dp, iconSize = 24.dp, radius = Radius.card, tint = Facet.colors.accentInk)
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, color = Facet.colors.text)
            Text(body, style = MaterialTheme.typography.bodyLarge, color = Facet.colors.textMuted)
        }
    }
}

@Composable
private fun Step(number: Int, @StringRes title: Int, @StringRes body: Int) {
    // One item: "1, Place the box, Point at a table…". The circle grows with the number's font size.
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Box(
            Modifier.sizeIn(minWidth = 44.dp, minHeight = 44.dp).aspectRatio(1f).border(2.dp, Facet.colors.accentInk, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = Facet.type.valueMono,
                color = Facet.colors.accentInk,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = Facet.colors.text)
            Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = Facet.colors.textMuted)
        }
    }
}

/** Page 1 hero: wireframe mug standing in a coverage ring, with a dimension marker. Decorative. */
@Composable
private fun MugIllustration(modifier: Modifier) {
    val accent = Facet.colors.accentInk
    val muted = Facet.colors.textMuted
    val text = Facet.colors.text
    val tm = rememberTextMeasurer()
    val labelStyle = Facet.type.dataMono.copy(color = text)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val stroke = 2.5.dp.toPx()
        // Ring on the table: captured (solid, lime) in front, missing (dashed, muted) at the back.
        val ringCenter = Offset(cx, h * 0.68f)
        val ringSize = Size(w * 0.68f, h * 0.24f)
        val ringTopLeft = ringCenter - Offset(ringSize.width / 2, ringSize.height / 2)
        drawArc(muted, 180f, 180f, false, ringTopLeft, ringSize,
            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))))
        drawArc(accent, 0f, 180f, false, ringTopLeft, ringSize,
            style = Stroke(7.dp.toPx(), cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(28f, 8f))))

        // Mug body
        val bw = w * 0.26f
        val left = cx - bw / 2 - w * 0.03f
        val right = left + bw
        val top = h * 0.12f
        val bottom = h * 0.70f
        val rimH = h * 0.06f
        val body = Path().apply {
            moveTo(left, top)
            lineTo(left, bottom - 18.dp.toPx())
            quadraticTo(left, bottom, left + 18.dp.toPx(), bottom)
            lineTo(right - 18.dp.toPx(), bottom)
            quadraticTo(right, bottom, right, bottom - 18.dp.toPx())
            lineTo(right, top)
            close()
        }
        // Triangle mesh hatch inside the body.
        clipPath(body) {
            val rows = 12
            val cols = 8
            val cw = bw / cols
            val rh = (bottom - top) / rows
            for (r in 0 until rows) for (c in 0 until cols) {
                val x0 = left + c * cw
                val y0 = top + r * rh
                val tri = Path().apply {
                    moveTo(x0, y0); lineTo(x0 + cw / 2, y0 + rh); lineTo(x0 + cw, y0)
                }
                drawPath(tri, accent, style = Stroke(1.dp.toPx()))
            }
        }
        drawPath(body, accent, style = Stroke(stroke))
        drawOval(accent, Offset(left, top - rimH / 2), Size(bw, rimH), style = Stroke(stroke))
        // Handle
        drawArc(accent, -90f, 180f, false, Offset(right - bw * 0.35f, top + (bottom - top) * 0.15f),
            Size(bw * 0.7f, (bottom - top) * 0.45f), style = Stroke(stroke))

        // Dimension marker
        val dy = ringCenter.y + ringSize.height / 2 + 14.dp.toPx() // below the ring, not on it
        val dx0 = cx + w * 0.10f
        val dx1 = cx + w * 0.27f
        drawLine(text, Offset(dx0, dy), Offset(dx1, dy), 2.dp.toPx())
        drawLine(text, Offset(dx0, dy - 8.dp.toPx()), Offset(dx0, dy + 8.dp.toPx()), 2.dp.toPx())
        drawLine(text, Offset(dx1, dy - 8.dp.toPx()), Offset(dx1, dy + 8.dp.toPx()), 2.dp.toPx())
        val label = tm.measure("96.0 mm", labelStyle)
        drawText(label, topLeft = Offset((dx0 + dx1) / 2 - label.size.width / 2f, dy + 10.dp.toPx()))
    }
}
