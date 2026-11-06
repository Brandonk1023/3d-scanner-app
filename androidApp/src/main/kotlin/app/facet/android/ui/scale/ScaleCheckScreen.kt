package app.facet.android.ui.scale

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.facet.android.R
import app.facet.android.appContainer
import app.facet.android.ui.components.FacetTopBar
import app.facet.android.ui.components.PrimaryButton
import app.facet.android.ui.components.TextLinkButton
import app.facet.android.ui.components.dimsText
import app.facet.android.ui.components.unitLabel
import app.facet.android.ui.mesh.MeasureOverlay
import app.facet.android.ui.mesh.MeshView
import app.facet.android.ui.mesh.rememberOrbitCamera
import app.facet.android.ui.theme.Facet
import app.facet.android.ui.theme.Radius
import app.facet.android.ui.theme.Space
import app.facet.format.formatLength
import app.facet.format.formatSignedPercent
import app.facet.ui.scale.ScaleCheckViewModel

/** Scale-Manual.png: two points on a known edge, the real length, Apply scale. */
@Composable
fun ScaleCheckScreen(scanId: String, onBack: () -> Unit) {
    val container = appContainer()
    val vm: ScaleCheckViewModel = viewModel(key = "scale-$scanId") {
        ScaleCheckViewModel(scanId, container.scans, container.settings)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val camera = rememberOrbitCamera()
    val focus = LocalFocusManager.current

    LaunchedEffect(state.applied, state.missing) { if (state.applied || state.missing) onBack() }
    val scan = state.scan ?: return
    val unit = state.units
    val unitText = unitLabel(unit)

    Scaffold(
        containerColor = Facet.colors.bg,
        topBar = { FacetTopBar(stringResource(R.string.check_scale), onBack = onBack, subtitle = scan.name) },
        // The sheet handles the bottom insets itself, so its surface runs under the navigation bar.
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // Viewport
            val shape = RoundedCornerShape(Radius.panel)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 160.dp)
                    .fillMaxWidth()
                    .padding(horizontal = Space.m)
                    .clip(shape)
                    .background(Facet.colors.surface)
                    .border(1.dp, Facet.colors.border, shape),
            ) {
                val mesh = state.mesh
                if (mesh != null) {
                    MeshView(
                        mesh,
                        Modifier.fillMaxSize().semantics { contentDescription = scan.name },
                        camera = camera,
                        interactive = true,
                        measure = MeasureOverlay(
                            points = state.points,
                            label = state.measuredMm?.let { formatLength(it, unit) },
                            onTap = { focus.clearFocus(); vm.tap(it) },
                            onDrag = vm::movePoint,
                        ),
                    )
                }
                val hint = when (state.points.size) {
                    0 -> R.string.scale_tap_two
                    1 -> R.string.scale_tap_second
                    else -> R.string.scale_drag_points
                }
                Text(
                    stringResource(hint),
                    style = MaterialTheme.typography.labelLarge,
                    color = Facet.colors.text,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = Space.s)
                        .clip(RoundedCornerShape(50))
                        .background(Facet.colors.bg)
                        .border(1.dp, Facet.colors.border, RoundedCornerShape(50))
                        .padding(horizontal = Space.m, vertical = Space.xs)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            // Sheet-styled panel
            Column(
                Modifier
                    .padding(top = Space.m)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet))
                    .background(Facet.colors.surface)
                    .navigationBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Text(stringResource(R.string.scale_real_length), style = MaterialTheme.typography.bodyMedium, color = Facet.colors.textMuted)
                LengthField(state.input, unitText, vm::setInput, onDone = { focus.clearFocus() })

                val measured = state.measuredMm
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (measured != null) stringResource(R.string.scale_measured, "${formatLength(measured, unit)} $unitText") else "",
                        style = Facet.type.dataMono,
                        color = Facet.colors.textMuted,
                        modifier = Modifier.weight(1f),
                    )
                    state.correction?.let { c ->
                        val text = formatSignedPercent(c)
                        Text(
                            text,
                            style = Facet.type.dataMono,
                            color = if (state.warnLarge) Facet.colors.warning else Facet.colors.accentInk,
                        )
                    }
                }
                state.newSize?.let {
                    Text(stringResource(R.string.scale_new_size, dimsText(it, unit)), style = Facet.type.dataMono, color = Facet.colors.textMuted)
                }
                val message = when {
                    state.tooShort -> R.string.scale_too_short
                    state.outOfRange -> R.string.scale_out_of_range
                    state.warnLarge -> R.string.scale_warn_large
                    else -> null
                }
                if (message != null) {
                    Text(
                        stringResource(message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Facet.colors.warning,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                PrimaryButton(
                    stringResource(R.string.apply_scale),
                    onClick = { focus.clearFocus(); vm.apply() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canApply,
                )
                TextLinkButton(
                    stringResource(R.string.reset_points),
                    onClick = vm::resetPoints,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = Facet.colors.accentInk,
                )
            }
        }
    }
}

@Composable
private fun LengthField(value: String, unit: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    val shape = RoundedCornerShape(Radius.card)
    val label = stringResource(R.string.scale_real_length)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = inputStyle().copy(color = Facet.colors.text),
        cursorBrush = SolidColor(Facet.colors.text),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.semantics { contentDescription = label },
        decorationBox = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clip(shape)
                    .background(Facet.colors.surface2)
                    .border(2.dp, Facet.colors.accentInk, shape)
                    .padding(horizontal = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(stringResource(R.string.scale_input_hint), style = inputStyle(), color = Facet.colors.textMuted)
                    }
                    field()
                }
                Text(unit, style = Facet.type.dataMono, color = Facet.colors.textMuted)
            }
        },
    )
}

@Composable
private fun inputStyle() = Facet.type.valueMono.copy(fontSize = 24.sp, lineHeight = 30.sp)
