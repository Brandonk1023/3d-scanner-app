package app.facet.android.ui.export

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.facet.android.R
import app.facet.android.appContainer
import app.facet.android.ui.components.FacetSnackbarHost
import app.facet.android.ui.components.FacetTopBar
import app.facet.android.ui.components.OutlineButton
import app.facet.android.ui.components.PrimaryButton
import app.facet.android.ui.components.RadioCard
import app.facet.android.ui.mesh.MeshView
import app.facet.android.ui.theme.Facet
import app.facet.android.ui.theme.Radius
import app.facet.android.ui.theme.Space
import app.facet.data.ResultQuality
import app.facet.export.ExportFormat
import app.facet.format.formatBytes
import app.facet.ui.export.ExportAction
import app.facet.ui.export.ExportEvent
import app.facet.ui.export.ExportViewModel
import kotlinx.coroutines.launch

/** Export.png: Save → Downloads/Facet (snackbar with Open), Share → the system share sheet. */
@Composable
fun ExportScreen(scanId: String, onBack: () -> Unit) {
    val container = appContainer()
    val vm: ExportViewModel = viewModel(key = scanId) {
        ExportViewModel(
            scanId, container.scans, container.settings, container.exportWriter, container.fileExporter,
            okio.FileSystem.SYSTEM, container.exportDir,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val res = context.resources

    // Consume first, then show from the screen's scope: the snackbar outlives the state change.
    // A new result replaces the one on screen instead of queueing behind it.
    LaunchedEffect(state.event) {
        val event = state.event ?: return@LaunchedEffect
        vm.consumeEvent()
        snackbar.currentSnackbarData?.dismiss()
        scope.launch {
            when (event) {
                is ExportEvent.Saved -> {
                    val result = snackbar.showSnackbar(
                        res.getString(R.string.saved_to, event.file.location),
                        actionLabel = res.getString(R.string.open),
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed && !vm.openSaved(event.file)) {
                        snackbar.showSnackbar(res.getString(R.string.export_no_app, event.file.location.substringAfterLast('.')))
                    }
                }
                is ExportEvent.Failed -> snackbar.showSnackbar(
                    res.getString(if (event.action == ExportAction.SAVE) R.string.export_save_failed else R.string.export_share_failed),
                )
            }
        }
    }

    // Android 8–9 write to the public Downloads folder, which needs the storage permission.
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.save() else scope.launch { snackbar.showSnackbar(res.getString(R.string.export_storage_denied)) }
    }
    fun save() {
        val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else vm.save()
    }

    val scan = state.scan ?: return
    val ready = state.mesh != null && state.busy == null

    Scaffold(
        containerColor = Facet.colors.bg,
        snackbarHost = { FacetSnackbarHost(snackbar) },
        topBar = {
            FacetTopBar(
                stringResource(R.string.export_title),
                onBack = onBack,
                subtitle = stringResource(
                    R.string.export_subtitle,
                    scan.name,
                    stringResource(if (scan.quality == ResultQuality.HIGH_DETAIL) R.string.mode_high else R.string.mode_quick),
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
        ) {
            val previewShape = RoundedCornerShape(Radius.panel)
            Box(
                Modifier
                    .padding(horizontal = Space.m)
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(previewShape)
                    .background(Facet.colors.surface)
                    .border(1.dp, Facet.colors.border, previewShape),
            ) {
                state.mesh?.let { MeshView(it, Modifier.fillMaxSize().padding(Space.s)) }
            }
            Spacer(Modifier.height(Space.l))

            // Sheet-styled panel
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet))
                    .background(Facet.colors.surface)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Text(stringResource(R.string.file_name), style = MaterialTheme.typography.bodyMedium, color = Facet.colors.textMuted)
                FileNameField(state.fileName, "." + state.format.extension, vm::setFileName)
                Spacer(Modifier.height(Space.xxs))
                FormatCard(ExportFormat.STL, R.string.fmt_stl, R.string.fmt_stl_body, state.format, state.sizes[ExportFormat.STL], vm::setFormat)
                FormatCard(ExportFormat.THREE_MF, R.string.fmt_3mf, R.string.fmt_3mf_body, state.format, state.sizes[ExportFormat.THREE_MF], vm::setFormat)
                ComingLater()
                Spacer(Modifier.height(Space.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    OutlineButton(
                        stringResource(if (state.busy == ExportAction.SAVE) R.string.export_saving else R.string.save),
                        onClick = ::save,
                        icon = R.drawable.ic_download,
                        modifier = Modifier.weight(1f),
                        enabled = ready,
                    )
                    PrimaryButton(
                        stringResource(if (state.busy == ExportAction.SHARE) R.string.export_preparing else R.string.share),
                        onClick = vm::share,
                        icon = R.drawable.ic_share,
                        modifier = Modifier.weight(1f),
                        enabled = ready,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileNameField(value: String, extension: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(Radius.card)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Facet.colors.text),
        cursorBrush = SolidColor(Facet.colors.text),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        decorationBox = { field ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(shape)
                    .background(Facet.colors.surface2)
                    .border(1.dp, Facet.colors.border, shape)
                    .padding(horizontal = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { field() }
                Text(extension, style = Facet.type.dataMono, color = Facet.colors.textMuted)
            }
        },
    )
}

@Composable
private fun FormatCard(
    format: ExportFormat,
    title: Int,
    body: Int,
    selected: ExportFormat,
    bytes: Long?,
    onSelect: (ExportFormat) -> Unit,
) {
    RadioCard(selected = format == selected, onClick = { onSelect(format) }) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = Facet.type.valueMono, color = Facet.colors.text)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = Facet.colors.textMuted)
        }
        if (bytes != null) {
            val size = formatBytes(bytes)
            val cd = stringResource(R.string.cd_file_size, size)
            Spacer(Modifier.width(Space.s))
            Text(size, style = Facet.type.dataMono, color = Facet.colors.textMuted, modifier = Modifier.semantics { contentDescription = cd })
        }
    }
}

@Composable
private fun ComingLater() {
    val border = Facet.colors.border
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .drawBehind {
                drawRoundRect(
                    border,
                    cornerRadius = CornerRadius(Radius.card.toPx()),
                    style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.fmt_more), style = Facet.type.dataMono, color = Facet.colors.textMuted, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.fmt_more_later),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Normal),
            color = Facet.colors.textMuted,
        )
    }
}
