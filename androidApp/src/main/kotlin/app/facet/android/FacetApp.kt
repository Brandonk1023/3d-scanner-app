package app.facet.android

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.facet.android.platform.AndroidFileExporter
import app.facet.android.platform.AndroidStorageInfo
import app.facet.android.platform.ArCoreDeviceCapabilities
import app.facet.android.processing.AndroidBackgroundProcessor
import app.facet.data.ScanFolder
import app.facet.data.RoomScanRepository
import app.facet.data.ScanRepository
import app.facet.data.db.createFacetDatabase
import app.facet.mesh.AndroidMeshEngine
import app.facet.mesh.MeshEngine
import app.facet.mesh.ScanPipeline
import app.facet.platform.DeviceCapabilities
import app.facet.processing.HighDetailProcessor
import app.facet.platform.FileExporter
import app.facet.platform.StorageInfo
import app.facet.settings.DataStoreSettingsRepository
import app.facet.settings.SETTINGS_FILE_NAME
import app.facet.settings.SettingsRepository
import app.facet.settings.createSettingsDataStore
import app.facet.ui.export.ExportWriter
import android.os.SystemClock
import kotlinx.coroutines.flow.first
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
    private val roomScans = RoomScanRepository(
        dao = createFacetDatabase(context).scans(),
        fs = FileSystem.SYSTEM,
        scansDir = context.filesDir.resolve(SCANS_DIR).toOkioPath(),
        makeThumbnail = { mesh ->
            val result = runCatching { meshEngine.decimate(mesh, RoomScanRepository.THUMBNAIL_TRIANGLES, 0.02f) }.getOrNull()
            result?.mesh?.takeIf { result.ok && it.triangleCount > 0 } ?: mesh
        },
    )
    val scans: ScanRepository = roomScans

    /** A scan's capture.json and keyframes. */
    fun scanFolder(id: String): ScanFolder = roomScans.folder(id)

    /** Debug builds: Settings › Developer switches (in memory, reset on restart). */
    val debug = DebugFlags()

    private val deviceStorage = AndroidStorageInfo(context)
    val storage: StorageInfo = object : StorageInfo {
        override fun freeBytes(): Long =
            deviceStorage.freeBytes().let { if (debug.pretendLowStorage) minOf(it, DebugFlags.LOW_STORAGE_BYTES) else it }
    }
    val device: DeviceCapabilities = ArCoreDeviceCapabilities(context)
    val fileExporter: FileExporter = AndroidFileExporter(context)

    /** Native STL / 3MF writers. */
    val exportWriter = ExportWriter { mesh, format, path, name -> ScanPipeline(meshEngine).export(mesh, format, path, name) }

    /** Export scratch folder; must match the cache-path in res/xml/file_paths.xml. */
    val exportDir: Path = context.cacheDir.resolve("exports").toOkioPath()

    /** The High detail pass itself (shared); run by HighDetailWorker. */
    val highDetail: HighDetailProcessor by lazy {
        HighDetailProcessor(
            engine = meshEngine,
            repository = scans,
            folder = ::scanFolder,
            settings = { settings.settings.first() },
            now = SystemClock::elapsedRealtime,
        )
    }

    val backgroundProcessor = AndroidBackgroundProcessor(context.applicationContext, scans)

    /** Set while the Processing screen for that scan is on screen: its "ready" notification is skipped. */
    @Volatile var visibleProcessingScan: String? = null

    companion object {
        /** files/scans/<scan id>/ */
        const val SCANS_DIR = "scans"
    }
}

/** Debug-only switches for testing High detail on the emulator. */
class DebugFlags {
    /** The next High detail run fails at "Building fine mesh" (as if out of memory). */
    @Volatile var failNextHighDetail = false

    /** Free space reads as at most [LOW_STORAGE_BYTES]: below High detail's need, above a scan's. */
    @Volatile var pretendLowStorage = false

    fun consumeFailNext(): Boolean = failNextHighDetail.also { failNextHighDetail = false }

    companion object {
        const val LOW_STORAGE_BYTES = 1_200_000_000L // decimal, like ScanTuning.Storage
    }
}

val Context.appContainer: AppContainer get() = (applicationContext as FacetApp).container

@Composable
fun appContainer(): AppContainer = LocalContext.current.appContainer
