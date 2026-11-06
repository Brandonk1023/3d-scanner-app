package app.facet.android.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.facet.FacetInfo
import app.facet.android.R
import app.facet.android.appContainer
import app.facet.android.ui.components.ChoiceDialog
import app.facet.android.ui.components.ConfirmDialog
import app.facet.android.ui.components.FacetTopBar
import app.facet.android.ui.components.InfoDialog
import app.facet.android.ui.components.ListDivider
import app.facet.android.ui.components.ListGroup
import app.facet.android.ui.components.NavRow
import app.facet.android.ui.components.SectionLabel
import app.facet.android.ui.components.SegmentedControl
import app.facet.android.ui.components.SwitchRow
import app.facet.android.ui.theme.Facet
import app.facet.android.ui.theme.FacetTypography
import app.facet.android.ui.theme.facetColorScheme
import app.facet.android.ui.theme.Space
import android.content.pm.ApplicationInfo
import androidx.compose.runtime.rememberCoroutineScope
import app.facet.data.addSampleScans
import app.facet.data.addSimulatedScan
import app.facet.format.formatBytes
import kotlinx.coroutines.launch
import app.facet.mesh.Smoothing
import app.facet.settings.DefaultResult
import app.facet.settings.LengthUnit
import app.facet.settings.ThemeMode
import app.facet.ui.settings.SettingsViewModel
import com.google.android.gms.oss.licenses.v2.OssLicensesMenuActivity

private enum class SettingsDialog { APPEARANCE, UNITS, DEFAULT_RESULT, CLEAR_PHOTOS, PRIVACY }

/** Settings.png */
@Composable
fun SettingsScreen(onBack: () -> Unit, onNativeLibraries: () -> Unit, onOpenScan: (String) -> Unit = {}) {
    val container = appContainer()
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container.settings, container.scans, container.storage) }
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    var dialog by rememberSaveable { mutableStateOf<SettingsDialog?>(null) }

    val themeOptions = listOf(
        ThemeMode.SYSTEM to stringResource(R.string.appearance_system),
        ThemeMode.LIGHT to stringResource(R.string.appearance_light),
        ThemeMode.DARK to stringResource(R.string.appearance_dark),
    )
    val unitOptions = listOf(
        LengthUnit.MILLIMETRES to stringResource(R.string.units_mm),
        LengthUnit.INCHES to stringResource(R.string.units_in),
    )
    val resultOptions = listOf(
        DefaultResult.ASK to stringResource(R.string.default_result_ask),
        DefaultResult.QUICK to stringResource(R.string.mode_quick),
        DefaultResult.HIGH_DETAIL to stringResource(R.string.mode_high),
    )
    val smoothingOptions = listOf(
        Smoothing.OFF to stringResource(R.string.smoothing_off),
        Smoothing.LOW to stringResource(R.string.smoothing_low),
        Smoothing.MEDIUM to stringResource(R.string.smoothing_medium),
        Smoothing.HIGH to stringResource(R.string.smoothing_high),
    )

    Scaffold(
        containerColor = Facet.colors.bg,
        topBar = { FacetTopBar(stringResource(R.string.settings_title), onBack = onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Space.m)
                .padding(bottom = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Section(R.string.settings_general) {
                ListGroup {
                    NavRow(stringResource(R.string.settings_appearance), themeOptions.label(s.themeMode), { dialog = SettingsDialog.APPEARANCE })
                    ListDivider()
                    NavRow(stringResource(R.string.settings_units), unitOptions.label(s.units), { dialog = SettingsDialog.UNITS })
                }
            }

            Section(R.string.settings_scanning) {
                ListGroup {
                    NavRow(stringResource(R.string.settings_default_result), resultOptions.label(s.defaultResult), { dialog = SettingsDialog.DEFAULT_RESULT })
                    ListDivider()
                    SwitchRow(
                        stringResource(R.string.settings_watertight),
                        stringResource(R.string.settings_watertight_sub),
                        s.watertight,
                    ) { on -> vm.update { it.copy(watertight = on) } }
                    ListDivider()
                    Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text(stringResource(R.string.settings_smoothing), style = MaterialTheme.typography.bodyLarge, color = Facet.colors.text)
                        SegmentedControl(
                            options = smoothingOptions.map { it.second },
                            selectedIndex = smoothingOptions.indexOfFirst { it.first == s.smoothing },
                            onSelect = { i -> vm.update { it.copy(smoothing = smoothingOptions[i].first) } },
                            modifier = Modifier.fillMaxWidth(),
                            equalWidth = true,
                        )
                    }
                }
            }

            Section(R.string.settings_guidance) {
                ListGroup {
                    SwitchRow(
                        stringResource(R.string.settings_haptics),
                        stringResource(R.string.settings_haptics_sub),
                        s.hapticGuidance,
                    ) { on -> vm.update { it.copy(hapticGuidance = on) } }
                }
            }

            Section(R.string.settings_storage) {
                ListGroup {
                    Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                pluralStringResource(R.plurals.storage_scans, state.usage.scanCount, state.usage.scanCount),
                                style = MaterialTheme.typography.bodyLarge,
                                color = Facet.colors.text,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(R.string.storage_summary, formatBytes(state.usage.totalBytes), formatBytes(state.freeBytes)),
                                style = Facet.type.dataMono,
                                color = Facet.colors.textMuted,
                            )
                        }
                        StorageBar(state.modelsFraction, state.photosFraction)
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                            Legend(Facet.colors.text, stringResource(R.string.storage_models, formatBytes(state.usage.modelBytes)))
                            Legend(Facet.colors.textMuted, stringResource(R.string.storage_photos, formatBytes(state.usage.savedPhotoBytes)))
                        }
                    }
                    ListDivider()
                    NavRow(
                        stringResource(R.string.storage_clear_photos),
                        formatBytes(state.usage.savedPhotoBytes),
                        { if (state.usage.savedPhotoBytes > 0) dialog = SettingsDialog.CLEAR_PHOTOS },
                    )
                }
            }

            Section(R.string.settings_about) {
                ListGroup {
                    val context = LocalContext.current
                    val version = remember {
                        val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: FacetInfo.SHARED_VERSION
                        val core = runCatching { container.meshEngine.coreVersion() }.getOrDefault("?")
                        name to core
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Space.m),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.about_version), style = MaterialTheme.typography.bodyLarge, color = Facet.colors.text, modifier = Modifier.weight(1f))
                        Text(stringResource(R.string.about_version_value, version.first, version.second), style = Facet.type.dataMono, color = Facet.colors.textMuted)
                    }
                    ListDivider()
                    val licencesTitle = stringResource(R.string.about_licences)
                    NavRow(licencesTitle, null, { openOssLicences(context, licencesTitle, s.themeMode) })
                    ListDivider()
                    NavRow(stringResource(R.string.about_native_libraries), null, onNativeLibraries)
                    ListDivider()
                    NavRow(stringResource(R.string.about_privacy), null, { dialog = SettingsDialog.PRIVACY })
                }
                AboutFootnotes()
            }

            // Debug builds only: the emulator can't scan, so this fills the gallery for testing.
            val context = LocalContext.current
            val debuggable = remember { context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
            if (debuggable) {
                val scope = rememberCoroutineScope()
                Section(R.string.settings_developer) {
                    ListGroup {
                        NavRow(
                            stringResource(R.string.dev_add_samples),
                            stringResource(R.string.dev_add_samples_sub),
                            { scope.launch { container.scans.addSampleScans() } },
                        )
                        ListDivider()
                        var simulating by remember { mutableStateOf(false) }
                        NavRow(
                            stringResource(if (simulating) R.string.dev_simulate_scan_running else R.string.dev_simulate_scan),
                            stringResource(R.string.dev_simulate_scan_sub),
                            {
                                if (!simulating) {
                                    simulating = true
                                    scope.launch {
                                        val id = container.scans.addSimulatedScan(container.meshEngine, s)
                                        simulating = false
                                        if (id != null) onOpenScan(id)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    when (dialog) {
        SettingsDialog.APPEARANCE -> ChoiceDialog(
            stringResource(R.string.settings_appearance), themeOptions, s.themeMode,
            onSelect = { m -> vm.update { it.copy(themeMode = m) } }, onDismiss = { dialog = null },
        )
        SettingsDialog.UNITS -> ChoiceDialog(
            stringResource(R.string.settings_units), unitOptions, s.units,
            onSelect = { u -> vm.update { it.copy(units = u) } }, onDismiss = { dialog = null },
        )
        SettingsDialog.DEFAULT_RESULT -> ChoiceDialog(
            stringResource(R.string.settings_default_result), resultOptions, s.defaultResult,
            onSelect = { r -> vm.update { it.copy(defaultResult = r) } }, onDismiss = { dialog = null },
        )
        SettingsDialog.CLEAR_PHOTOS -> ConfirmDialog(
            title = stringResource(R.string.clear_photos_title),
            body = stringResource(R.string.clear_photos_body),
            confirmLabel = stringResource(R.string.action_clear),
            onConfirm = vm::clearSavedPhotos,
            onDismiss = { dialog = null },
        )
        SettingsDialog.PRIVACY -> InfoDialog(
            stringResource(R.string.about_privacy),
            body = {
                Text(
                    stringResource(R.string.about_privacy_body, stringResource(R.string.app_name)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Facet.colors.textMuted,
                )
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private fun <T> List<Pair<T, String>>.label(value: T): String = first { it.first == value }.second

@Composable
private fun Section(title: Int, content: @Composable () -> Unit) {
    SectionLabel(stringResource(title), Modifier.padding(top = Space.m, start = Space.xxs))
    content()
}

@Composable
private fun StorageBar(models: Float, photos: Float) {
    // Tiny shares still get a visible sliver so the bar never looks empty when something is stored.
    fun visible(f: Float) = if (f > 0f) f.coerceAtLeast(0.015f) else 0f
    Row(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(CircleShape)
            .background(Facet.colors.surface2)
            .clearAndSetSemantics {},
    ) {
        val m = visible(models)
        val p = visible(photos)
        if (m > 0f) Box(Modifier.fillMaxHeight().weight(m).background(Facet.colors.text))
        if (p > 0f) Box(Modifier.fillMaxHeight().weight(p).background(Facet.colors.textMuted))
        val rest = 1f - m - p
        if (rest > 0f) Spacer(Modifier.weight(rest))
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Facet.colors.textMuted)
    }
}

/**
 * Google's open-source licences screen (Gradle dependencies, collected by the oss-licenses plugin),
 * in the app's colours. Follows the app's Appearance setting rather than only the system's.
 */
private fun openOssLicences(context: Context, title: String, themeMode: ThemeMode) {
    val light = facetColorScheme(dark = themeMode == ThemeMode.DARK)
    val dark = facetColorScheme(dark = themeMode != ThemeMode.LIGHT)
    OssLicensesMenuActivity.setTheme(light, dark, FacetTypography)
    OssLicensesMenuActivity.setActivityTitle(title)
    context.startActivity(Intent(context, OssLicensesMenuActivity::class.java))
}

/** Required ARCore disclosure (Google APIs Terms of Service) and third-party credits, under About. */
@Composable
private fun AboutFootnotes() {
    val full = stringResource(R.string.about_arcore_disclosure)
    val link = stringResource(R.string.about_arcore_policy_link)
    val url = stringResource(R.string.about_arcore_policy_url)
    val linkStyle = SpanStyle(color = Facet.colors.accentInk, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)
    val disclosure = buildAnnotatedString {
        append(full)
        val start = full.indexOf(link)
        if (start >= 0) addLink(LinkAnnotation.Url(url, TextLinkStyles(style = linkStyle)), start, start + link.length)
    }
    Column(
        Modifier.padding(horizontal = Space.xxs, vertical = Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(disclosure, style = MaterialTheme.typography.bodySmall, color = Facet.colors.textMuted)
        Text(stringResource(R.string.about_credit_meshoptimizer), style = MaterialTheme.typography.bodySmall, color = Facet.colors.textMuted)
    }
}
