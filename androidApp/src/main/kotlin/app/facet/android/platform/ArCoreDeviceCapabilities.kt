package app.facet.android.platform

import android.content.Context
import android.os.Build
import app.facet.platform.ArServicesStatus
import app.facet.platform.DepthStatus
import app.facet.platform.DeviceCapabilities
import app.facet.platform.DeviceCheckResult
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * ARCore availability + Depth API support. Depth can only be queried through a [Session], which
 * needs Google Play Services for AR installed and up to date; until then depth is UNKNOWN.
 * Creating the session doesn't open the camera, so this works before the camera permission.
 */
class ArCoreDeviceCapabilities(context: Context) : DeviceCapabilities {
    private val app = context.applicationContext

    override suspend fun check(): DeviceCheckResult = withContext(Dispatchers.Main) {
        val model = deviceModel()
        val ar = when (availability()) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArServicesStatus.READY
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> ArServicesStatus.NEEDS_UPDATE
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> ArServicesStatus.NEEDS_INSTALL
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> ArServicesStatus.UNSUPPORTED
            else -> ArServicesStatus.UNKNOWN
        }
        if (ar != ArServicesStatus.READY) return@withContext DeviceCheckResult(ar, DepthStatus.UNKNOWN, model)

        try {
            val session = Session(app)
            try {
                val depth = if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) DepthStatus.SUPPORTED else DepthStatus.UNSUPPORTED
                DeviceCheckResult(ArServicesStatus.READY, depth, model)
            } finally {
                session.close()
            }
        } catch (_: UnavailableApkTooOldException) {
            DeviceCheckResult(ArServicesStatus.NEEDS_UPDATE, DepthStatus.UNKNOWN, model)
        } catch (_: UnavailableArcoreNotInstalledException) {
            DeviceCheckResult(ArServicesStatus.NEEDS_INSTALL, DepthStatus.UNKNOWN, model)
        } catch (_: UnavailableDeviceNotCompatibleException) {
            DeviceCheckResult(ArServicesStatus.UNSUPPORTED, DepthStatus.UNKNOWN, model)
        } catch (_: UnavailableException) {
            DeviceCheckResult(ArServicesStatus.UNKNOWN, DepthStatus.UNKNOWN, model)
        }
    }

    /** checkAvailability may answer UNKNOWN_CHECKING first while it queries the Play Store data. */
    private suspend fun availability(): ArCoreApk.Availability {
        var result = ArCoreApk.getInstance().checkAvailability(app)
        var waited = 0L
        while (result.isTransient && waited < AVAILABILITY_TIMEOUT_MS) {
            delay(AVAILABILITY_POLL_MS)
            waited += AVAILABILITY_POLL_MS
            result = ArCoreApk.getInstance().checkAvailability(app)
        }
        return result
    }

    private fun deviceModel(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }

    private companion object {
        const val AVAILABILITY_POLL_MS = 200L
        const val AVAILABILITY_TIMEOUT_MS = 5_000L
    }
}
