package app.facet.android.ui.processing

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.facet.android.R
import app.facet.android.ui.components.BusyIndicator
import app.facet.android.appContainer
import app.facet.android.processing.ProcessingNotifications
import app.facet.android.ui.components.FacetSnackbarHost
import app.facet.android.ui.components.FacetTopBar
import app.facet.android.ui.components.FitText
import app.facet.android.ui.components.Headline
import app.facet.android.ui.components.IconTile
import app.facet.android.ui.components.OutlineButton
import app.facet.android.ui.components.PrimaryButton
import app.facet.android.ui.components.TextLinkButton
import app.facet.android.ui.mesh.MeshView
import app.facet.android.ui.theme.Facet
import app.facet.android.ui.theme.Radius
import app.facet.android.ui.theme.Space
import app.facet.format.formatBytes
import app.facet.format.formatBytesAbout
import app.facet.mesh.Mesh
import app.facet.platform.FailureReason
import app.facet.platform.ProcessingStage
import app.facet.platform.ProcessingStatus
import app.facet.ui.processing.LowStorage
import app.facet.ui.processing.ProcessingPhase
import app.facet.ui.processing.ProcessingUiState
import app.facet.ui.processing.ProcessingViewModel
import app.facet.ui.processing.StageDetail
import app.facet.ui.processing.StageRow
import app.facet.ui.processing.StageState
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * `processing/{scanId}`: High detail progress (Processing.png), failure (Processing-Failed.png) and
 * the low-storage check before starting (Low-Storage.png). Follows the app theme.
 */
@Composable
fun ProcessingScreen(
    scanId: String,
    start: Boolean,
    onBack: () -> Unit,
    /** Finished, cancelled, or the user kept the Quick result. */
    onOpenViewer: () -> Unit,
    /** Notify me and leave: back to the gallery while it runs. */
    onLeave: () -> Unit,
    onFreeUpSpace: () -> Unit,
) {
    val container = appContainer()
    val vm: ProcessingViewModel = viewModel(key = "processing-$scanId") {
        ProcessingViewModel(scanId, start, container.scans, container.backgroundProcessor, container.storage)
    }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.openViewer) {
        if (state.openViewer) {
            vm.consumeOpenViewer()
            onOpenViewer()
        }
    }
    LaunchedEffect(state.missing) { if (state.missing) onBack() }

    // While this screen is in front, the worker skips its "ready" / "failed" notification.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, scanId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> container.visibleProcessingScan = scanId
                Lifecycle.Event.ON_PAUSE -> if (container.visibleProcessingScan == scanId) container.visibleProcessingScan = null
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (container.visibleProcessingScan == scanId) container.visibleProcessingScan = null
        }
    }

    val preview by produceState<Mesh?>(null, scanId) { value = container.scans.loadThumbnail(scanId) }
    val snackbar = remember { SnackbarHostState() }

    Box(Modifier.fillMaxSize().background(Facet.colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            val scan = state.scan
            val subtitle = when (state.phase) {
                ProcessingPhase.LOW_STORAGE -> stringResource(R.string.mode_quick)
                else -> stringResource(R.string.proc_subtitle)
            }
            FacetTopBar(scan?.name.orEmpty(), onBack = onBack, subtitle = subtitle)
            when (state.phase) {
                ProcessingPhase.LOADING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    BusyIndicator(color = Facet.colors.accentInk)
                }
                ProcessingPhase.RUNNING -> RunningPage(state, preview, onLeave = onLeave, onCancel = vm::cancel)
                ProcessingPhase.FAILED -> FailedPage(state.failure!!, onRetry = vm::retry, onUseQuick = vm::useQuick, snackbar = snackbar)
                ProcessingPhase.LOW_STORAGE -> LowStoragePage(state.lowStorage!!, onFreeUpSpace, onKeepQuick = vm::useQuick, onCancel = onBack)
            }
        }
        FacetSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

/**
 * Scrolls when the content is taller than the screen (large font), and is at least screen-tall
 * otherwise, so a weighted spacer still pushes the buttons to the bottom.
 */
@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screenHeight = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = screenHeight)
                    .navigationBarsPadding()
                    .padding(horizontal = Space.l, vertical = Space.m),
                content = content,
            )
        }
    }
}

@Composable
private fun RunningPage(state: ProcessingUiState, preview: Mesh?, onLeave: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    // Notify me and leave: ask for notifications first (Android 13+); leave whatever the answer.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onLeave() }
    val notifyAndLeave = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ProcessingNotifications.canPost(context)) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            onLeave()
        }
    }
    PageColumn {
        PreviewWithRing(state, preview)
        Spacer(Modifier.height(Space.l))
        state.stages.forEach { StageRowView(it) }
        Spacer(Modifier.height(Space.l))
        PrimaryButton(stringResource(R.string.proc_notify), onClick = notifyAndLeave, icon = R.drawable.ic_bell, modifier = Modifier.fillMaxWidth())
        TextLinkButton(
            stringResource(R.string.proc_cancel), onClick = onCancel,
            color = Facet.colors.accentInk, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = Space.xs),
        )
        Text(
            stringResource(R.string.proc_footer),
            style = MaterialTheme.typography.bodyMedium,
            color = Facet.colors.textMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = Space.s),
        )
    }
}

@Composable
private fun PreviewWithRing(state: ProcessingUiState, preview: Mesh?) {
    val shape = RoundedCornerShape(Radius.panel)
    val percent = state.progress.percent
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 300.dp)
            .height(320.dp)
            .clip(shape)
            .background(Facet.colors.surface)
            .border(1.dp, Facet.colors.border, shape),
        contentAlignment = Alignment.Center,
    ) {
        // The Quick result, faded, while the fine one is built.
        if (preview != null) MeshView(preview, Modifier.fillMaxSize().alpha(0.35f), interactive = false)
        val track = Facet.colors.border
        // accentInk, not the lime fill: the arc must reach 3:1 on the light surface.
        val arc = Facet.colors.accentInk
        val description = stringResource(R.string.cd_proc_progress, percent)
        Box(
            Modifier.size(176.dp).clip(CircleShape).background(Facet.colors.surface.copy(alpha = 0.6f))
                .clearAndSetSemantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize().padding(8.dp)) {
                val stroke = 10.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                drawArc(arc, -90f, 360f * state.progress.overall, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                // Shrinks rather than clipping "100%" at large font sizes.
                FitText("$percent%", Facet.type.displayMono, Facet.colors.text, minSize = (20f / LocalDensity.current.fontScale).sp)
                // Wraps inside the ring rather than running under it.
                Text(timeLeft(state), style = Facet.type.dataMono, color = Facet.colors.textMuted, textAlign = TextAlign.Center, maxLines = 2)
            }
        }
    }
}

@Composable
private fun timeLeft(state: ProcessingUiState): String {
    val s = state.progress.remainingSeconds
    return when {
        s == null -> if (state.progress.percent == 0) stringResource(R.string.proc_starting) else ""
        s < 60 -> stringResource(R.string.proc_time_left_short)
        else -> stringResource(R.string.proc_time_left, (s + 59) / 60)
    }
}

@Composable
private fun StageRowView(row: StageRow) {
    val label = stringResource(ProcessingNotifications.stageLabel(row.stage))
    val description = stringResource(
        when (row.state) {
            StageState.DONE -> R.string.cd_stage_done
            StageState.CURRENT -> R.string.cd_stage_current
            StageState.TODO -> R.string.cd_stage_todo
        },
        label,
    )
    val detail = when (val d = row.detail) {
        is StageDetail.Photos -> pluralStringResource(R.plurals.proc_photos, d.count, d.count)
        is StageDetail.Elapsed -> elapsed(d.ms)
        is StageDetail.Items -> stringResource(R.string.stage_of, d.done, d.total)
        null -> null
    }
    // One item: "Building fine mesh, in progress, 8 of 36".
    val spoken = listOfNotNull(description, detail).joinToString(", ")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StageIcon(row.state)
        Spacer(Modifier.width(Space.m))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = if (row.state == StageState.CURRENT) FontWeight.Bold else FontWeight.Normal),
            color = if (row.state == StageState.TODO) Facet.colors.textMuted else Facet.colors.text,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) Text(detail, style = Facet.type.dataMono, color = Facet.colors.textMuted)
    }
}

@Composable
private fun StageIcon(state: StageState) {
    val size = 32.dp
    when (state) {
        StageState.DONE -> Box(Modifier.size(size).clip(CircleShape).background(Facet.colors.accent), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Facet.colors.onAccent, modifier = Modifier.size(18.dp))
        }
        StageState.CURRENT -> BusyIndicator(Facet.colors.accentInk, Modifier.size(size), strokeWidth = 3.dp)
        StageState.TODO -> {
            val color = Facet.colors.textMuted
            Canvas(Modifier.size(size)) {
                val w = 2.dp.toPx()
                drawCircle(color, radius = size.toPx() / 2 - w, style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
            }
        }
    }
}

/** "0:48", "12:05". */
private fun elapsed(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

@Composable
private fun FailedPage(failure: ProcessingStatus.Failed, onRetry: () -> Unit, onUseQuick: () -> Unit, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val step = (failure.stage?.ordinal ?: 0) + 1
    val total = ProcessingStage.entries.size
    // Without usable photos, trying again can't help.
    val canRetry = failure.reason != FailureReason.NO_PHOTOS && failure.reason != FailureReason.OLD_PHOTOS
    val copied = stringResource(R.string.error_copied)
    PageColumn {
        Spacer(Modifier.height(Space.xl))
        IconTile(R.drawable.ic_warning, container = Facet.colors.dangerContainer, tint = Facet.colors.danger)
        Spacer(Modifier.height(Space.l))
        Headline(stringResource(R.string.fail_title))
        Spacer(Modifier.height(Space.m))
        Text(
            when (failure.reason) {
                FailureReason.OUT_OF_MEMORY -> stringResource(R.string.fail_body_memory, step, total)
                FailureReason.NO_PHOTOS -> stringResource(R.string.fail_body_no_photos)
                FailureReason.OLD_PHOTOS -> stringResource(R.string.fail_body_old_photos)
                FailureReason.EMPTY_RESULT -> stringResource(R.string.fail_body_empty, step, total)
                FailureReason.STORAGE -> stringResource(R.string.fail_body_storage, step, total)
                FailureReason.INTERRUPTED -> stringResource(R.string.fail_body_interrupted, step, total)
                FailureReason.UNKNOWN -> stringResource(R.string.fail_body_unknown, step, total)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = Facet.colors.textMuted,
        )
        if (canRetry) {
            Spacer(Modifier.height(Space.l))
            val cardShape = RoundedCornerShape(Radius.card)
            Column(
                Modifier.fillMaxWidth().clip(cardShape).background(Facet.colors.surface).border(1.dp, Facet.colors.border, cardShape)
                    .padding(Space.m),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Text(stringResource(R.string.fail_help_title), style = MaterialTheme.typography.titleMedium, color = Facet.colors.text, modifier = Modifier.semantics { heading() })
                Text(
                    stringResource(if (failure.reason == FailureReason.STORAGE) R.string.fail_help_storage else R.string.fail_help_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Facet.colors.textMuted,
                )
            }
        }
        // A fixed gap, then whatever is left: weight alone collapses to 0 once the page scrolls.
        Spacer(Modifier.height(Space.xl))
        Spacer(Modifier.weight(1f))
        if (canRetry) {
            PrimaryButton(stringResource(R.string.try_again), onRetry, icon = R.drawable.ic_refresh, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.s))
            OutlineButton(stringResource(R.string.use_quick), onUseQuick, modifier = Modifier.fillMaxWidth())
        } else {
            PrimaryButton(stringResource(R.string.use_quick), onUseQuick, modifier = Modifier.fillMaxWidth())
        }
        TextLinkButton(
            stringResource(R.string.copy_error),
            onClick = {
                copyToClipboard(context, failure.technicalLog)
                // Android 13+ shows its own confirmation.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) scope.launch { snackbar.showSnackbar(copied) }
            },
            color = Facet.colors.accentInk,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = Space.xs),
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Facet error details", text))
}

@Composable
private fun LowStoragePage(low: LowStorage, onFreeUpSpace: () -> Unit, onKeepQuick: () -> Unit, onCancel: () -> Unit) {
    PageColumn {
        Spacer(Modifier.height(Space.xl))
        IconTile(R.drawable.ic_storage, container = Facet.colors.warningContainer, tint = Facet.colors.warning)
        Spacer(Modifier.height(Space.l))
        Headline(stringResource(R.string.low_storage_title))
        Spacer(Modifier.height(Space.m))
        Text(
            stringResource(R.string.low_storage_body, formatBytesAbout(low.neededBytes), formatBytes(low.freeBytes)),
            style = MaterialTheme.typography.bodyLarge,
            color = Facet.colors.textMuted,
        )
        Spacer(Modifier.height(Space.l))
        val cardShape = RoundedCornerShape(Radius.card)
        Column(
            Modifier.fillMaxWidth().clip(cardShape).background(Facet.colors.surface).border(1.dp, Facet.colors.border, cardShape)
                .semantics(mergeDescendants = true) {}
                .padding(Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            // "1.2 GB free" and "needs ~2.0 GB" share a line when they fit, else wrap whole.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(Space.xxs),
            ) {
                Text(stringResource(R.string.low_storage_free, formatBytes(low.freeBytes)), style = Facet.type.dataMono, color = Facet.colors.text, modifier = Modifier.padding(end = Space.s))
                Text(stringResource(R.string.low_storage_needs, formatBytes(low.neededBytes)), style = Facet.type.dataMono, color = Facet.colors.textMuted)
            }
            StorageBar(low.freeBytes.toFloat() / low.neededBytes)
            if (low.reclaimableBytes > 0) {
                Text(
                    stringResource(R.string.low_storage_photos, formatBytes(low.reclaimableBytes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Facet.colors.textMuted,
                )
            }
        }
        // A fixed gap, then whatever is left: weight alone collapses to 0 once the page scrolls.
        Spacer(Modifier.height(Space.xl))
        Spacer(Modifier.weight(1f))
        PrimaryButton(stringResource(R.string.free_up_space), onFreeUpSpace, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.s))
        OutlineButton(stringResource(R.string.keep_quick), onKeepQuick, modifier = Modifier.fillMaxWidth())
        TextLinkButton(
            stringResource(R.string.action_cancel), onClick = onCancel,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = Space.xs),
        )
    }
}

/** Free space as a share of what's needed, with a mark at "needed". */
@Composable
private fun StorageBar(fraction: Float) {
    val fill = Facet.colors.warning
    val track = Facet.colors.surface2
    val mark = Facet.colors.text
    Canvas(Modifier.fillMaxWidth().height(24.dp)) {
        val h = 12.dp.toPx()
        val top = (size.height - h) / 2
        val end = size.width - 3.dp.toPx()
        drawRoundRect(track, Offset(0f, top), Size(end, h), androidx.compose.ui.geometry.CornerRadius(h / 2))
        drawRoundRect(fill, Offset(0f, top), Size(end * fraction.coerceIn(0f, 1f), h), androidx.compose.ui.geometry.CornerRadius(h / 2))
        drawLine(mark, Offset(end + 1.dp.toPx(), 0f), Offset(end + 1.dp.toPx(), size.height), strokeWidth = 2.dp.toPx())
    }
}
