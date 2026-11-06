package app.facet.android.ui.viewer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.facet.android.R
import app.facet.android.appContainer
import app.facet.android.ui.components.DeleteScanDialog
import app.facet.android.ui.components.FacetTopBar
import app.facet.android.ui.components.OutlineButton
import app.facet.android.ui.components.PrimaryButton
import app.facet.android.ui.components.RenameDialog
import app.facet.android.ui.components.SegmentedControl
import app.facet.android.ui.components.isLargeFont
import app.facet.android.ui.components.resultSubtitle
import app.facet.android.ui.components.unitLabel
import app.facet.android.ui.mesh.DimensionLabels
import app.facet.android.ui.mesh.MeshView
import app.facet.android.ui.mesh.rememberOrbitCamera
import app.facet.android.ui.theme.Facet
import app.facet.android.ui.theme.Radius
import app.facet.android.ui.theme.Sizes
import app.facet.android.ui.theme.Space
import app.facet.format.formatLength
import app.facet.settings.LengthUnit
import app.facet.ui.viewer.ViewerViewModel

/** A-Viewer.png */
@Composable
fun ViewerScreen(
    scanId: String,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onCheckScale: () -> Unit,
    onHighDetail: () -> Unit,
) {
    val container = appContainer()
    val vm: ViewerViewModel = viewModel(key = scanId) { ViewerViewModel(scanId, container.scans, container.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var wireframe by rememberSaveable { mutableStateOf(false) }
    val camera = rememberOrbitCamera()

    if (state.missing) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val scan = state.scan ?: return

    Scaffold(
        containerColor = Facet.colors.bg,
        topBar = {
            FacetTopBar(scan.name, onBack = onBack, subtitle = resultSubtitle(scan)) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.action_more))
                    }
                    DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }, containerColor = Facet.colors.surface) {
                        MenuItem(R.string.act_rename, R.drawable.ic_edit) { menuOpen = false; renaming = true }
                        MenuItem(R.string.act_export_again, R.drawable.ic_upload) { menuOpen = false; onExport() }
                        MenuItem(R.string.act_delete, R.drawable.ic_delete, danger = true) { menuOpen = false; deleting = true }
                    }
                }
            }
        },
    ) { padding ->
        // At large font sizes the tiles and buttons need the room: the page scrolls and the
        // viewport keeps a fixed height instead of taking what's left.
        val largeFont = isLargeFont()
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .then(if (largeFont) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .navigationBarsPadding()
                .padding(horizontal = Space.m)
                .padding(bottom = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            // Viewport
            val shape = RoundedCornerShape(Radius.panel)
            Box(
                Modifier
                    .then(if (largeFont) Modifier.height(360.dp) else Modifier.weight(1f).heightIn(min = 220.dp))
                    .fillMaxWidth()
                    .clip(shape)
                    .background(Facet.colors.surface)
                    .border(1.dp, Facet.colors.border, shape),
            ) {
                val mesh = state.mesh
                if (mesh != null) {
                    val unit = state.units
                    MeshView(
                        mesh,
                        Modifier.fillMaxSize().semantics { contentDescription = scan.name },
                        camera = camera,
                        wireframe = wireframe,
                        interactive = true,
                        showAxes = true,
                        dimensions = DimensionLabels(formatLength(scan.size.widthMm, unit), formatLength(scan.size.heightMm, unit)),
                    )
                }
                SegmentedControl(
                    options = listOf(stringResource(R.string.viewer_shaded), stringResource(R.string.viewer_wireframe)),
                    selectedIndex = if (wireframe) 1 else 0,
                    onSelect = { wireframe = it == 1 },
                    container = Facet.colors.bg,
                    modifier = Modifier.align(Alignment.TopStart).padding(Space.s),
                )
                IconButton(
                    onClick = camera::reset,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Space.s)
                        .size(Sizes.touchMin + 4.dp)
                        .clip(CircleShape)
                        .background(Facet.colors.bg)
                        .border(1.dp, Facet.colors.border, CircleShape),
                ) {
                    Icon(painterResource(R.drawable.ic_cube), stringResource(R.string.cd_reset_view), tint = Facet.colors.text)
                }
                if (!largeFont) {
                    Text(
                        stringResource(R.string.viewer_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = Facet.colors.textMuted,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(Space.m),
                    )
                }
            }
            // Large text would cover the dimension labels and axes, so the hint moves under the viewport.
            if (largeFont) {
                Text(stringResource(R.string.viewer_hint), style = MaterialTheme.typography.bodySmall, color = Facet.colors.textMuted)
            }

            // W / D / H tiles
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                DimTile(stringResource(R.string.dim_width), scan.size.widthMm, state.units, Modifier.weight(1f))
                DimTile(stringResource(R.string.dim_depth), scan.size.depthMm, state.units, Modifier.weight(1f))
                DimTile(stringResource(R.string.dim_height), scan.size.heightMm, state.units, Modifier.weight(1f))
            }

            // Check scale
            Surface(
                color = Facet.colors.surface,
                shape = RoundedCornerShape(Radius.card),
                border = BorderStroke(1.dp, Facet.colors.border),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.card)).clickable(role = Role.Button, onClick = onCheckScale),
            ) {
                Row(
                    Modifier.heightIn(min = 64.dp).padding(horizontal = Space.m, vertical = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    val verified = scan.scaleReference != null
                    Icon(
                        painterResource(if (verified) R.drawable.ic_check else R.drawable.ic_ruler),
                        null,
                        tint = if (verified) Facet.colors.success else Facet.colors.accentInk,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.check_scale),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = Facet.colors.text,
                        )
                        Text(
                            if (verified) stringResource(R.string.scale_verified_against, scan.scaleReference!!)
                            else stringResource(R.string.scale_not_verified),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Facet.colors.textMuted,
                        )
                    }
                    Icon(painterResource(R.drawable.ic_chevron_right), null, tint = Facet.colors.textMuted)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (scan.canReprocess) {
                    OutlineButton(
                        stringResource(R.string.btn_high_detail),
                        onClick = onHighDetail,
                        icon = R.drawable.ic_refresh,
                        modifier = Modifier.weight(1f),
                    )
                }
                PrimaryButton(
                    stringResource(R.string.btn_export),
                    onClick = onExport,
                    icon = R.drawable.ic_upload,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    if (renaming) RenameDialog(scan.name, onRename = vm::rename, onDismiss = { renaming = false })
    if (deleting) DeleteScanDialog(scan.name, onDelete = vm::delete, onDismiss = { deleting = false })
}

@Composable
private fun MenuItem(label: Int, icon: Int, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) Facet.colors.danger else Facet.colors.text
    DropdownMenuItem(
        text = { Text(stringResource(label), color = color, style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(painterResource(icon), null, tint = color) },
        onClick = onClick,
    )
}

@Composable
private fun DimTile(label: String, mm: Double, unit: LengthUnit, modifier: Modifier) {
    val value = formatLength(mm, unit)
    val suffix = unitLabel(unit)
    Surface(
        color = Facet.colors.surface,
        shape = RoundedCornerShape(Radius.card),
        border = BorderStroke(1.dp, Facet.colors.border),
        modifier = modifier.semantics(mergeDescendants = true) {},
    ) {
        Column(Modifier.padding(horizontal = Space.s, vertical = Space.s)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Facet.colors.textMuted)
            Text(
                buildAnnotatedString {
                    append(value)
                    withStyle(SpanStyle(fontSize = Facet.type.dataMono.fontSize, color = Facet.colors.textMuted)) { append(" $suffix") }
                },
                style = Facet.type.valueMono,
                color = Facet.colors.text,
                modifier = Modifier.clearAndSetSemantics { contentDescription = "$value $suffix" },
            )
        }
    }
}
