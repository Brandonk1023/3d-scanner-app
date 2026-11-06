package app.facet.android.ui.gallery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.sp
import app.facet.android.ui.components.FitText
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.facet.android.R
import app.facet.android.appContainer
import app.facet.android.ui.components.DeleteScanDialog
import app.facet.android.ui.components.ListDivider
import app.facet.android.ui.components.PrimaryButton
import app.facet.android.ui.components.RenameDialog
import app.facet.android.ui.components.SectionLabel
import app.facet.android.ui.components.StatusChip
import app.facet.android.ui.components.TextLinkButton
import app.facet.android.ui.components.dimsText
import app.facet.android.ui.mesh.MeshView
import app.facet.android.ui.theme.Facet
import app.facet.android.ui.theme.Radius
import app.facet.android.ui.theme.Sizes
import app.facet.android.ui.theme.Space
import app.facet.data.Scan
import app.facet.format.formatCount
import app.facet.mesh.Mesh
import app.facet.settings.LengthUnit
import app.facet.ui.gallery.GalleryFilter
import app.facet.ui.gallery.GalleryViewModel
import app.facet.ui.gallery.SectionHeader
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun GalleryScreen(
    onNewScan: () -> Unit,
    onOpenScan: (Scan) -> Unit,
    onReprocess: (Scan) -> Unit,
    onExport: (Scan) -> Unit,
    onSettings: () -> Unit,
    onTips: () -> Unit,
) {
    val container = appContainer()
    val vm: GalleryViewModel = viewModel { GalleryViewModel(container.scans, container.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    var actionsFor by rememberSaveable { mutableStateOf<String?>(null) }

    val hasScans = state.hasAnyScans
    Scaffold(
        containerColor = Facet.colors.bg,
        topBar = { GalleryTopBar(onSettings) },
        floatingActionButton = {
            if (hasScans) {
                val newScan = stringResource(R.string.new_scan)
                ExtendedFloatingActionButton(
                    onClick = onNewScan,
                    containerColor = Facet.colors.accent,
                    contentColor = Facet.colors.onAccent,
                    shape = RoundedCornerShape(Radius.fab),
                    // Material's extended FAB doesn't expose its text to accessibility; say it here.
                    modifier = Modifier.heightIn(min = Sizes.fab).semantics { contentDescription = newScan },
                    icon = { Icon(painterResource(R.drawable.ic_add), null) },
                    text = { Text(stringResource(R.string.new_scan), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding))
            !hasScans -> EmptyGallery(Modifier.padding(padding), onNewScan, onTips)
            else -> LazyVerticalGrid(
                // Cards widen with the font size: two columns at 100 %, one at large sizes so names, sizes and chips fit.
                columns = GridCells.Adaptive(minSize = 160.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = Space.m, end = Space.m, bottom = 112.dp),
                horizontalArrangement = Arrangement.spacedBy(Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, contentType = "search") {
                    SearchField(state.query, vm::setQuery)
                }
                item(span = { GridItemSpan(maxLineSpan) }, contentType = "filters") {
                    FilterChips(state.filter, vm::setFilter)
                }
                if (state.sections.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, contentType = "none") { NoMatches() }
                }
                state.sections.forEach { section ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = section.header.toString(), contentType = "header") {
                        SectionLabel(sectionTitle(section.header), Modifier.padding(top = Space.xs))
                    }
                    items(section.scans, key = { it.id }, contentType = { "card" }) { scan ->
                        ScanCard(
                            scan = scan,
                            units = state.units,
                            loadMesh = vm::mesh,
                            onClick = { onOpenScan(scan) },
                            onLongClick = { actionsFor = scan.id },
                        )
                    }
                }
            }
        }
    }

    val selected = state.sections.flatMap { it.scans }.firstOrNull { it.id == actionsFor }
    if (selected != null) {
        ScanActionsSheet(
            scan = selected,
            units = state.units,
            highDetailMinutes = state.highDetailEstimateMin,
            loadMesh = vm::mesh,
            onDismiss = { actionsFor = null },
            onOpen = { onOpenScan(selected) },
            onReprocess = { onReprocess(selected) },
            onExport = { onExport(selected) },
            onRename = { vm.rename(selected.id, it) },
            onDelete = { vm.delete(selected.id) },
        )
    }
}

@Composable
private fun GalleryTopBar(onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 72.dp)
            .padding(start = Space.m, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_logo), null, tint = Facet.colors.text, modifier = Modifier.size(30.dp))
        Spacer(Modifier.width(Space.s))
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            color = Facet.colors.text,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSettings, modifier = Modifier.size(Sizes.touchMin)) {
            Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.action_settings), tint = Facet.colors.text)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Facet.colors.text),
        cursorBrush = SolidColor(Facet.colors.text),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = Sizes.touchMin)
                    .clip(CircleShape)
                    .background(Facet.colors.surface)
                    .border(1.dp, Facet.colors.border, CircleShape)
                    .padding(start = Space.m, end = Space.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_search), null, tint = Facet.colors.textMuted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(Space.s))
                Box(Modifier.weight(1f).padding(vertical = Space.s)) {
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.gallery_search), style = MaterialTheme.typography.bodyLarge, color = Facet.colors.textMuted)
                    }
                    field()
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onChange("") }) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.cd_clear_search), tint = Facet.colors.textMuted)
                    }
                }
            }
        },
    )
}

@Composable
private fun FilterChips(selected: GalleryFilter, onSelect: (GalleryFilter) -> Unit) {
    val labels = listOf(
        GalleryFilter.ALL to R.string.filter_all,
        GalleryFilter.HIGH_DETAIL to R.string.filter_high,
        GalleryFilter.QUICK to R.string.filter_quick,
        GalleryFilter.PROCESSING to R.string.filter_processing,
    )
    val scroll = rememberScrollState()
    val fadeColor = Facet.colors.bg
    Row(
        Modifier
            .fillMaxWidth()
            // Fade the edge that has more chips behind it, so it reads as scrollable.
            .drawWithContent {
                drawContent()
                val fade = 40.dp.toPx()
                if (scroll.canScrollForward) {
                    drawRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, fadeColor), startX = size.width - fade, endX = size.width),
                        topLeft = Offset(size.width - fade, 0f), size = Size(fade, size.height),
                    )
                }
                if (scroll.canScrollBackward) {
                    drawRect(
                        Brush.horizontalGradient(listOf(fadeColor, Color.Transparent), startX = 0f, endX = fade),
                        size = Size(fade, size.height),
                    )
                }
            }
            .horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        labels.forEach { (filter, label) ->
            val isSelected = filter == selected
            val shape = RoundedCornerShape(Radius.chip + 2.dp)
            Row(
                Modifier
                    .heightIn(min = Sizes.touchMin)
                    .clip(shape)
                    .background(if (isSelected) Facet.colors.text else Facet.colors.surface)
                    .border(1.dp, if (isSelected) Facet.colors.text else Facet.colors.border, shape)
                    .selectable(isSelected, onClick = { onSelect(filter) }, role = Role.Tab)
                    .padding(horizontal = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSelected) {
                    Icon(painterResource(R.drawable.ic_check), null, tint = Facet.colors.bg, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    stringResource(label),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (isSelected) Facet.colors.bg else Facet.colors.text,
                )
            }
        }
    }
}

@Composable
private fun sectionTitle(header: SectionHeader): String = when (header) {
    SectionHeader.Today -> stringResource(R.string.section_today)
    SectionHeader.ThisWeek -> stringResource(R.string.section_this_week)
    is SectionHeader.Month -> {
        val ym = YearMonth.of(header.year, header.month)
        val pattern = if (header.year == LocalDate.now().year) "LLLL" else "LLLL yyyy"
        ym.format(DateTimeFormatter.ofPattern(pattern))
    }
}

@Composable
private fun NoMatches() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(stringResource(R.string.gallery_no_matches), style = MaterialTheme.typography.titleMedium, color = Facet.colors.text)
        Text(stringResource(R.string.gallery_no_matches_body), style = MaterialTheme.typography.bodyMedium, color = Facet.colors.textMuted)
    }
}

@Composable
fun ScanThumbnail(scanId: String, loadMesh: suspend (String) -> Mesh?, modifier: Modifier = Modifier) {
    val mesh by produceState<Mesh?>(null, scanId) { value = loadMesh(scanId) }
    Box(modifier.background(Facet.colors.surface2)) {
        mesh?.let { MeshView(it, Modifier.fillMaxSize().padding(Space.xs), showGround = false) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScanCard(
    scan: Scan,
    units: LengthUnit,
    loadMesh: suspend (String) -> Mesh?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.card)
    Surface(
        color = Facet.colors.surface,
        shape = shape,
        border = BorderStroke(1.dp, Facet.colors.border),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.cd_scan_actions),
            ),
    ) {
        Column {
            ScanThumbnail(scan.id, loadMesh, Modifier.fillMaxWidth().height(Sizes.galleryThumb))
            Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(
                    scan.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = Facet.colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FitText(dimsText(scan.size, units), Facet.type.dataMono, Facet.colors.textMuted, minSize = 9.sp)
                StatusChip(scan.status, scan.processingPercent)
            }
        }
    }
}

/** Gallery-Actions.png */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanActionsSheet(
    scan: Scan,
    units: LengthUnit,
    highDetailMinutes: Int,
    loadMesh: suspend (String) -> Mesh?,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onReprocess: () -> Unit,
    onExport: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }

    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss(); action() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Facet.colors.surface,
        shape = RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet),
        // No Material handle: it is a 32 dp-wide TalkBack target whose only action (expand/collapse)
        // does nothing on a sheet that is always fully open. Back, the scrim or a drag close it.
        dragHandle = null,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = Space.m)) {
            // Decorative handle, drawn as part of the sheet.
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = Space.m)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Facet.colors.border),
            )
            Row(
                Modifier.padding(horizontal = 20.dp).padding(bottom = Space.m),
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                ScanThumbnail(scan.id, loadMesh, Modifier.size(64.dp).clip(RoundedCornerShape(Radius.card)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    Text(scan.name, style = MaterialTheme.typography.titleLarge, color = Facet.colors.text)
                    Text(
                        dimsText(scan.size, units) + " · " + stringResource(R.string.tris_format, formatCount(scan.triangleCount)),
                        style = Facet.type.dataMono,
                        color = Facet.colors.textMuted,
                    )
                    StatusChip(scan.status, scan.processingPercent)
                }
            }
            ListDivider()
            Spacer(Modifier.height(Space.xs))
            ActionRow(R.drawable.ic_cube, stringResource(R.string.act_open)) { closeThen(onOpen) }
            if (scan.canReprocess) {
                ActionRow(
                    R.drawable.ic_refresh,
                    stringResource(R.string.act_reprocess),
                    stringResource(R.string.act_reprocess_sub, highDetailMinutes),
                ) { closeThen(onReprocess) }
            }
            ActionRow(R.drawable.ic_upload, stringResource(R.string.act_export_again), stringResource(R.string.act_export_again_sub)) {
                closeThen(onExport)
            }
            ActionRow(R.drawable.ic_edit, stringResource(R.string.act_rename)) { renaming = true }
            ActionRow(R.drawable.ic_delete, stringResource(R.string.act_delete), color = Facet.colors.danger) { deleting = true }
        }
    }

    if (renaming) RenameDialog(scan.name, onRename = { onRename(it) }, onDismiss = { renaming = false })
    if (deleting) {
        DeleteScanDialog(scan.name, onDelete = { closeThen(onDelete) }, onDismiss = { deleting = false })
    }
}

@Composable
private fun ActionRow(icon: Int, title: String, subtitle: String? = null, color: Color = Facet.colors.text, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Icon(painterResource(icon), null, tint = color, modifier = Modifier.size(26.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = color)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Facet.colors.textMuted)
        }
    }
}

/** Gallery-Empty.png */
@Composable
private fun EmptyGallery(modifier: Modifier, onNewScan: () -> Unit, onTips: () -> Unit) {
    Column(modifier.fillMaxSize().padding(horizontal = Space.l)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.CenterVertically),
        ) {
            EmptyIllustration(Modifier.size(width = 200.dp, height = 150.dp))
            Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall, color = Facet.colors.text, modifier = Modifier.semantics { heading() })
            Text(
                stringResource(R.string.empty_body),
                style = MaterialTheme.typography.bodyLarge,
                color = Facet.colors.textMuted,
                textAlign = TextAlign.Center,
            )
            TextLinkButton(stringResource(R.string.empty_tips), onTips, color = Facet.colors.accentInk)
        }
        PrimaryButton(
            stringResource(R.string.new_scan),
            onClick = onNewScan,
            icon = R.drawable.ic_add,
            modifier = Modifier.fillMaxWidth().padding(top = Space.s, bottom = Space.l),
        )
    }
}

@Composable
private fun EmptyIllustration(modifier: Modifier) {
    val outline = Facet.colors.textMuted
    val ring = Facet.colors.border
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
        drawOval(ring, Offset(0f, h * 0.62f), Size(w, h * 0.32f), style = Stroke(5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 4.dp.toPx()))))
        val cw = w * 0.42f
        val left = (w - cw) / 2
        val stroke = Stroke(1.5.dp.toPx(), pathEffect = dash)
        drawOval(outline, Offset(left, 0f), Size(cw, h * 0.2f), style = stroke)
        drawLine(outline, Offset(left, h * 0.1f), Offset(left, h * 0.70f), 1.5.dp.toPx(), pathEffect = dash)
        drawLine(outline, Offset(left + cw, h * 0.1f), Offset(left + cw, h * 0.70f), 1.5.dp.toPx(), pathEffect = dash)
        drawArc(outline, 0f, 180f, false, Offset(left, h * 0.60f), Size(cw, h * 0.2f), style = stroke)
    }
}
