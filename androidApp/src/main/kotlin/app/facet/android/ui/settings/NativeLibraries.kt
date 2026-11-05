package app.facet.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.facet.android.R
import app.facet.android.ui.components.FacetTopBar
import app.facet.android.ui.components.ListDivider
import app.facet.android.ui.components.ListGroup
import app.facet.android.ui.components.SectionLabel
import app.facet.android.ui.theme.Facet
import app.facet.android.ui.theme.Space

/**
 * A third-party component whose license the oss-licenses plugin can't see (it only reads Gradle
 * POMs). [asset] is the license text under src/main/assets.
 */
data class BundledLicense(val name: String, val version: String, val license: String, val asset: String)

/**
 * C++ libraries compiled into libfacet_jni.so (native/CMakeLists.txt). Each asset is the library's
 * LICENSE file followed by any other notices found in the files actually compiled (vendored code);
 * run native/scripts/scan_notices.py after a dependency bump to catch new ones. `./gradlew verifyNativeLicenses`
 * fails the build if a library there has no "licenses/<name>.txt" asset or isn't listed here.
 * Versions are the pinned tags / commits; keep them in sync with native/CMakeLists.txt.
 */
val NATIVE_LIBRARIES = listOf(
    BundledLicense("Open3D (ported marching cubes)", "v0.20.0 · b6c5e19", "MIT", "licenses/open3d.txt"),
    BundledLicense("PoissonRecon", "18.76 · 262b0f5", "MIT, BSD-3-Clause (incl. Georgia Tech PLY code)", "licenses/poissonrecon.txt"),
    BundledLicense("Manifold", "v3.5.4 · ce50d78", "Apache-2.0 (incl. MIT, Zlib parts)", "licenses/manifold.txt"),
    BundledLicense("lib3mf", "v2.5.0 · 64bb454", "BSD-2-Clause", "licenses/lib3mf.txt"),
    BundledLicense("libzip (bundled in lib3mf)", "1.10.1", "BSD-3-Clause", "licenses/libzip.txt"),
    BundledLicense("zlib (bundled in lib3mf)", "1.3.1", "Zlib", "licenses/zlib.txt"),
    BundledLicense("cpp-base64 (bundled in lib3mf)", "2.rc.08", "Zlib-style", "licenses/cpp-base64.txt"),
    BundledLicense("fast_float (bundled in lib3mf)", "6.0.0", "MIT", "licenses/fast_float.txt"),
    BundledLicense("meshoptimizer", "v1.3 · 9e1f07b", "MIT", "licenses/meshoptimizer.txt"),
)

/** Fonts and model files shipped in the APK. */
val BUNDLED_ASSETS = listOf(
    BundledLicense("Inter Tight", "font", "OFL-1.1", "licenses/inter_tight_OFL.txt"),
    BundledLicense("JetBrains Mono", "font", "OFL-1.1", "licenses/jetbrains_mono_OFL.txt"),
    BundledLicense("MediaPipe interactive segmenter", "model", "Apache-2.0", "licenses/mediapipe_APACHE-2.0.txt"),
)

/**
 * Full texts for entries that Google's open-source licences list shows only as a URL (its plugin
 * copies just the POM's license link): Apache-2.0 (most AndroidX / Google libraries) and the two
 * notice-style licenses whose copyright lines must be reproduced.
 */
val GRADLE_LICENSE_TEXTS = listOf(
    BundledLicense("Apache License 2.0", "AndroidX, Compose, Kotlin, MediaPipe and others", "Apache-2.0", "licenses/apache-2.0.txt"),
    BundledLicense("Protocol Buffers (in Preferences DataStore)", "v26.1", "BSD-3-Clause", "licenses/protobuf.txt"),
    BundledLicense("Checker Framework annotations", "2.5.3", "MIT", "licenses/checker-qual.txt"),
)

/** Settings › About › Native libraries: name, version, license and the full text of each. */
@Composable
fun NativeLibrariesScreen(onBack: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold(
        containerColor = Facet.colors.bg,
        topBar = { FacetTopBar(stringResource(R.string.native_libraries_title), onBack = onBack) },
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
            Text(
                stringResource(R.string.native_libraries_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = Facet.colors.textMuted,
            )
            LicenseGroup(R.string.native_libraries_section_core, NATIVE_LIBRARIES, expanded) { expanded = it }
            LicenseGroup(R.string.native_libraries_section_assets, BUNDLED_ASSETS, expanded) { expanded = it }
            LicenseGroup(R.string.native_libraries_section_gradle, GRADLE_LICENSE_TEXTS, expanded) { expanded = it }
        }
    }
}

@Composable
private fun LicenseGroup(title: Int, items: List<BundledLicense>, expanded: String?, onToggle: (String?) -> Unit) {
    SectionLabel(stringResource(title), Modifier.padding(top = Space.m, start = Space.xxs))
    ListGroup {
        items.forEachIndexed { i, item ->
            if (i > 0) ListDivider()
            val open = expanded == item.asset
            LicenseRow(item, open) { onToggle(if (open) null else item.asset) }
        }
    }
}

@Composable
private fun LicenseRow(item: BundledLicense, open: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val shown = stringResource(if (open) R.string.native_libraries_shown else R.string.native_libraries_hidden)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, role = Role.Button)
            .semantics { stateDescription = shown }
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyLarge, color = Facet.colors.text)
                Text(
                    stringResource(R.string.native_libraries_line, item.version, item.license),
                    style = Facet.type.dataMono,
                    color = Facet.colors.textMuted,
                )
            }
            Icon(
                painterResource(if (open) R.drawable.ic_arrow_up else R.drawable.ic_arrow_down),
                contentDescription = null,
                tint = Facet.colors.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        if (open) {
            val text = remember(item.asset) {
                runCatching { context.assets.open(item.asset).bufferedReader().use { it.readText() } }.getOrDefault("")
            }
            Text(text.trim(), style = MaterialTheme.typography.bodySmall, color = Facet.colors.textMuted)
        }
    }
}
