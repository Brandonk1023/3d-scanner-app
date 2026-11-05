package app.facet.android.ui.components

import app.facet.android.ui.theme.LightFacetColors
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.text.TextStyle
import app.facet.android.R
import app.facet.android.ui.theme.Facet
import app.facet.android.ui.theme.Radius
import app.facet.android.ui.theme.Sizes
import app.facet.android.ui.theme.Space
import app.facet.data.ScanStatus

// Building blocks from design/docs/03-design-system.md › Components.

/**
 * One line of text that shrinks (down to [minSize]) instead of wrapping or clipping when space is
 * tight: dimensions on narrow cards, chip labels, segmented control labels.
 */
@Composable
fun FitText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minSize: TextUnit = 10.sp,
) {
    BasicText(
        text = text,
        style = style.copy(color = color),
        maxLines = 1,
        modifier = modifier,
        autoSize = TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

/** The one accent-filled action on a screen. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Sizes.button),
        colors = ButtonDefaults.buttonColors(
            containerColor = Facet.colors.accent,
            contentColor = Facet.colors.onAccent,
            disabledContainerColor = Facet.colors.surface2,
            disabledContentColor = Facet.colors.textMuted,
        ),
        contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.s),
    ) { ButtonContent(text, icon) }
}

@Composable
fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = Sizes.button),
        border = BorderStroke(1.dp, Facet.colors.textMuted),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Facet.colors.text),
        contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.s),
    ) { ButtonContent(text, icon) }
}

@Composable
private fun RowScope.ButtonContent(text: String, @DrawableRes icon: Int?) {
    if (icon != null) {
        Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(Space.xs))
    }
    Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
}

/** Underlined text action (Not now, Check again, Skip, links). */
@Composable
fun TextLinkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Facet.colors.text,
) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = Sizes.buttonText)) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = color,
            textDecoration = TextDecoration.Underline,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FacetTopBar(
    title: String,
    onBack: (() -> Unit)?,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Facet.colors.textMuted, maxLines = 1)
                }
            }
        },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Facet.colors.bg,
            scrolledContainerColor = Facet.colors.bg,
            titleContentColor = Facet.colors.text,
            navigationIconContentColor = Facet.colors.text,
            actionIconContentColor = Facet.colors.text,
        ),
    )
}

/** Large rounded icon tile at the top of full-page screens (permission, unsupported, …). */
@Composable
fun IconTile(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    container: Color = Facet.colors.surface2,
    tint: Color = Facet.colors.text,
    size: Dp = 96.dp,
    iconSize: Dp = 44.dp,
    radius: Dp = 28.dp,
) {
    Box(
        modifier = modifier.size(size).clip(RoundedCornerShape(radius)).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Screen headline (28 sp bold), marked as a heading for TalkBack. */
@Composable
fun Headline(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        color = Facet.colors.text,
        modifier = modifier.semantics { heading() },
    )
}

/** Section label: mono caps (GENERAL, TODAY). */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = Facet.type.overline,
        color = Facet.colors.textMuted,
        modifier = modifier.semantics { heading() },
    )
}

/** Page indicator: current page is a pill. */
@Composable
fun PageDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val selected = i + 1 == current
            Box(
                Modifier
                    .size(width = if (selected) 24.dp else 8.dp, height = 8.dp)
                    .clip(CircleShape)
                    .background(if (selected) Facet.colors.text else Facet.colors.border),
            )
        }
    }
}

/** Card / list group: surface, 1 dp border, radius 14. Children are separated by dividers via [ListDivider]. */
@Composable
fun ListGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Facet.colors.surface,
        shape = RoundedCornerShape(Radius.card),
        border = BorderStroke(1.dp, Facet.colors.border),
    ) { Column(content = content) }
}

@Composable
fun ListDivider() = HorizontalDivider(thickness = 1.dp, color = Facet.colors.border)

/** 56 dp navigation row: label, value, chevron. */
@Composable
fun NavRow(label: String, value: String?, onClick: () -> Unit, valueMono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Facet.colors.text, modifier = Modifier.weight(1f))
        if (value != null) {
            Text(
                value,
                style = if (valueMono) Facet.type.dataMono else MaterialTheme.typography.bodyMedium,
                color = Facet.colors.textMuted,
                modifier = Modifier.padding(start = Space.s),
            )
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            tint = Facet.colors.textMuted,
            modifier = Modifier.padding(start = Space.xs).size(20.dp),
        )
    }
}

/** 64 dp switch row with subtitle. Switch on = accent track, dark thumb. */
@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, onValueChange = onCheckedChange, role = Role.Switch)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Facet.colors.text)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Facet.colors.textMuted)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = null, // the whole row toggles
            modifier = Modifier.padding(start = Space.s),
            thumbContent = if (checked) {
                { Icon(painterResource(R.drawable.ic_check), null, tint = Facet.colors.accent, modifier = Modifier.size(14.dp)) }
            } else null,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Facet.colors.accent,
                checkedThumbColor = Facet.colors.onAccent,
                checkedBorderColor = Facet.colors.accent,
                uncheckedTrackColor = Facet.colors.surface2,
                uncheckedThumbColor = Facet.colors.textMuted,
                uncheckedBorderColor = Facet.colors.textMuted,
            ),
        )
    }
}

/** Pill segmented control. Selected segment = text fill with bg-coloured label. */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Facet.colors.surface2,
    /** true = segments share the full width equally (pass a fillMaxWidth modifier). */
    equalWidth: Boolean = false,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .border(1.dp, Facet.colors.border, CircleShape)
            .padding(4.dp)
            .selectableGroup(),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                modifier = Modifier
                    .then(if (equalWidth) Modifier.weight(1f) else Modifier)
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .background(if (selected) Facet.colors.text else Color.Transparent)
                    .selectable(selected = selected, onClick = { onSelect(i) }, role = Role.Tab)
                    .padding(horizontal = if (equalWidth) Space.xxs else Space.m),
                contentAlignment = Alignment.Center,
            ) {
                FitText(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (selected) Facet.colors.bg else Facet.colors.text,
                    minSize = 12.sp,
                )
            }
        }
    }
}

/**
 * Status chip — shape + colour, never colour alone: High detail = accent fill + check,
 * Quick = outline, Processing = surface2 + progress arc + percentage.
 */
@Composable
fun StatusChip(status: ScanStatus, processingPercent: Int?, modifier: Modifier = Modifier) {
    val shape: Shape = RoundedCornerShape(Radius.chip + 2.dp)
    val textStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
    when (status) {
        ScanStatus.HIGH_DETAIL -> Row(
            modifier.clip(shape).background(Facet.colors.accent).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_check), null, tint = Facet.colors.onAccent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            FitText(stringResource(R.string.chip_high), textStyle, Facet.colors.onAccent, minSize = 11.sp)
        }
        ScanStatus.QUICK -> Text(
            stringResource(R.string.chip_quick),
            style = textStyle,
            color = Facet.colors.text,
            modifier = modifier.border(1.5.dp, Facet.colors.text, shape).padding(horizontal = 10.dp, vertical = 5.dp),
        )
        ScanStatus.PROCESSING -> Row(
            modifier.clip(shape).background(Facet.colors.surface2).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pct = processingPercent ?: 0
            // Determinate arc: a static state, so nothing spins (reduced-motion friendly).
            CircularProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.size(14.dp),
                color = Facet.colors.accentInk,
                trackColor = Color.Transparent,
                strokeWidth = 2.5.dp,
            )
            Spacer(Modifier.width(6.dp))
            FitText(stringResource(R.string.chip_processing, pct), textStyle, Facet.colors.text, minSize = 11.sp)
        }
    }
}

/** Radio card (result choice, export format). Selected = 2 dp accent border + surface2 fill + filled radio. */
@Composable
fun RadioCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radius.card)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Facet.colors.surface2 else Facet.colors.surface)
            .border(if (selected) 2.dp else 1.dp, if (selected) Facet.colors.accent else Facet.colors.border, shape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioDot(selected)
        Spacer(Modifier.width(Space.m))
        content()
    }
}

@Composable
private fun RadioDot(selected: Boolean) {
    val ring = if (selected) Facet.colors.accentInk else Facet.colors.textMuted
    Box(
        Modifier.size(24.dp).border(2.dp, ring, CircleShape).padding(5.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(14.dp).clip(CircleShape).background(Facet.colors.accentInk))
    }
}

/** Option list for a choice dialog. */
@Composable
fun <T> ChoiceList(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup()) {
        options.forEach { (value, label) ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.touchMin)
                    .clip(RoundedCornerShape(Radius.chip))
                    .selectable(selected = value == selected, onClick = { onSelect(value) }, role = Role.RadioButton)
                    .padding(horizontal = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioDot(value == selected)
                Spacer(Modifier.width(Space.m))
                Text(label, style = MaterialTheme.typography.bodyLarge, color = Facet.colors.text)
            }
        }
    }
}

/**
 * Snackbars in the app's colours: inverted surface (dark on light, light on dark) with the accent
 * for the action. Lime only reads on the dark container, so the light container uses the olive ink.
 */
@Composable
fun FacetSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val c = Facet.colors
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            data,
            shape = RoundedCornerShape(Radius.card),
            containerColor = c.text,
            contentColor = c.bg,
            actionColor = if (c.isDark) LightFacetColors.accentInk else c.accent,
            dismissActionContentColor = c.bg,
        )
    }
}
