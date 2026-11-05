package app.facet.android

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.facet.android.platform.AndroidStorageInfo
import app.facet.android.platform.ArCoreDeviceCapabilities
import app.facet.data.FakeScanRepository
import app.facet.data.ScanRepository
import app.facet.mesh.AndroidMeshEngine
import app.facet.mesh.MeshEngine
import app.facet.platform.DeviceCapabilities
import app.facet.platform.StorageInfo
import app.facet.settings.DataStoreSettingsRepository
import app.facet.settings.SETTINGS_FILE_NAME
import app.facet.settings.SettingsRepository
import app.facet.settings.createSettingsDataStore

class FacetApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** Process-wide singletons (manual DI). */
class AppContainer(context: Context) {
    val settings: SettingsRepository =
        DataStoreSettingsRepository(createSettingsDataStore(context.filesDir.resolve(SETTINGS_FILE_NAME).absolutePath))

    // Phase 1: sample scans in memory. Phase 5 swaps in the Room-backed repository.
    val scans: ScanRepository = FakeScanRepository()

    val storage: StorageInfo = AndroidStorageInfo(context)
    val device: DeviceCapabilities = ArCoreDeviceCapabilities(context)
    val meshEngine: MeshEngine by lazy { AndroidMeshEngine() }
}

val Context.appContainer: AppContainer get() = (applicationContext as FacetApp).container

@Composable
fun appContainer(): AppContainer = LocalContext.current.appContainer
