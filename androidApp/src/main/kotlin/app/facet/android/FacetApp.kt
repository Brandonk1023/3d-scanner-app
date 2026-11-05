package app.facet.android

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.facet.android.platform.AndroidFileExporter
import app.facet.android.platform.AndroidStorageInfo
import app.facet.android.platform.ArCoreDeviceCapabilities
import app.facet.data.RoomScanRepository
import app.facet.data.ScanRepository
import app.facet.data.db.createFacetDatabase
import app.facet.mesh.AndroidMeshEngine
import app.facet.mesh.MeshEngine
import app.facet.mesh.ScanPipeline
import app.facet.platform.DeviceCapabilities
import app.facet.platform.FileExporter
import app.facet.platform.StorageInfo
import app.facet.settings.DataStoreSettingsRepository
import app.facet.settings.SETTINGS_FILE_NAME
import app.facet.settings.SettingsRepository
import app.facet.settings.createSettingsDataStore
import app.facet.ui.export.ExportWriter
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

class FacetApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

/** Process-wide singletons (manual DI). */
class AppContainer(context: Context) {
    val settings: SettingsRepository =
        DataStoreSettingsRepository(createSettingsDataStore(context.filesDir.resolve(SETTINGS_FILE_NAME).absolutePath))

    val meshEngine: MeshEngine by lazy { AndroidMeshEngine() }

    /** Records in Room; meshes and keyframes in files/scans/<id>/ (capture writes there too). */
    val scans: ScanRepository = RoomScanRepository(
        dao = createFacetDatabase(context).scans(),
        fs = FileSystem.SYSTEM,
        scansDir = context.filesDir.resolve(SCANS_DIR).toOkioPath(),
        makeThumbnail = { mesh ->
            val result = runCatching { meshEngine.decimate(mesh, RoomScanRepository.THUMBNAIL_TRIANGLES, 0.02f) }.getOrNull()
            result?.mesh?.takeIf { result.ok && it.triangleCount > 0 } ?: mesh
        },
    )

    val storage: StorageInfo = AndroidStorageInfo(context)
    val device: DeviceCapabilities = ArCoreDeviceCapabilities(context)
    val fileExporter: FileExporter = AndroidFileExporter(context)

    /** Native STL / 3MF writers. */
    val exportWriter = ExportWriter { mesh, format, path, name -> ScanPipeline(meshEngine).export(mesh, format, path, name) }

    /** Export scratch folder; must match the cache-path in res/xml/file_paths.xml. */
    val exportDir: Path = context.cacheDir.resolve("exports").toOkioPath()

    companion object {
        /** files/scans/<scan id>/ */
        const val SCANS_DIR = "scans"
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as FacetApp).container

@Composable
fun appContainer(): AppContainer = LocalContext.current.appContainer
