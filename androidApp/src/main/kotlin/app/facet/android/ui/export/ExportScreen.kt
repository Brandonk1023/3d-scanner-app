package app.facet.android.ui.export

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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.facet.android.R
import app.facet.android.appContainer
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
import app.facet.ui.export.ExportViewModel
import kotlinx.coroutines.launch

/** Export.png — UI only in Phase 1; Save and Share write real files in Phase 5. */
@Composable
fun ExportScreen(scanId: String, onBack: () -> Unit) {
    val container = appContainer()
    val vm: ExportViewModel = viewModel(key = scanId) { ExportViewModel(scanId, container.scans, container.settings) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scan = state.scan ?: return
    val later = stringResource(R.string.stub_export_later)

    Scaffold(
        containerColor = Facet.colors.bg,
        snackbarHost = { SnackbarHost(snackbar) },
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
                        stringResource(R.string.save),
                        onClick = { scope.launch { snackbar.showSnackbar(later) } },
                        icon = R.drawable.ic_download,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(
                        stringResource(R.string.share),
                        onClick = { scope.launch { snackbar.showSnackbar(later) } },
                        icon = R.drawable.ic_share,
                        modifier = Modifier.weight(1f),
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
