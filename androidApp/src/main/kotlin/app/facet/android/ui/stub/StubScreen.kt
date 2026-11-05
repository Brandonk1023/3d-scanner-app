package app.facet.android.ui.stub

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.facet.android.R
import app.facet.android.ui.theme.Facet
import app.facet.android.ui.theme.FacetTheme
import app.facet.android.ui.theme.Sizes
import app.facet.android.ui.theme.Space

/** A navigation link on a stub screen. [primary] = the screen's one accent-filled action. */
data class StubLink(@StringRes val label: Int, val primary: Boolean = false, val onClick: () -> Unit)

/**
 * Phase 0 placeholder for every destination: title, the reference image it will match, optional
 * detail text, and buttons to the screens it leads to. Uses the real theme so fonts/colours can
 * be checked on the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StubScreen(
    title: String,
    reference: String,
    onBack: (() -> Unit)? = null,
    detailLabel: String? = null,
    detail: String? = null,
    links: List<StubLink> = emptyList(),
) {
    Scaffold(
        containerColor = Facet.colors.bg,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Facet.colors.bg),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l, vertical = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(
                text = stringResource(R.string.stub_note, reference).uppercase(),
                style = Facet.type.overline,
                color = Facet.colors.textMuted,
            )
            if (detail != null) {
                Surface(
                    color = Facet.colors.surface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        if (detailLabel != null) {
                            Text(detailLabel, style = MaterialTheme.typography.titleSmall, color = Facet.colors.text)
                        }
                        Text(detail, style = Facet.type.dataMono, color = Facet.colors.text)
                    }
                }
            }
            links.forEach { link ->
                val modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.button)
                if (link.primary) {
                    Button(onClick = link.onClick, modifier = modifier) {
                        Text(stringResource(link.label), style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    OutlinedButton(onClick = link.onClick, modifier = modifier) {
                        Text(
                            stringResource(link.label),
                            style = MaterialTheme.typography.labelLarge,
                            color = Facet.colors.text,
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun StubScreenPreview() {
    FacetTheme {
        StubScreen(
            title = "Facet",
            reference = "Gallery-Empty.png",
            detailLabel = "Wiring check",
            detail = "shared 0.1.0 · core 0.1.0",
            links = listOf(StubLink(R.string.new_scan, primary = true) {}, StubLink(R.string.settings_title) {}),
        )
    }
}
