package app.facet.android.ui.device

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.facet.android.R
import app.facet.android.ui.components.BusyIndicator
import app.facet.android.appContainer
import app.facet.android.ui.components.FacetTopBar
import app.facet.android.ui.components.Headline
import app.facet.android.ui.components.IconTile
import app.facet.android.ui.components.ListDivider
import app.facet.android.ui.components.ListGroup
import app.facet.android.ui.components.OutlineButton
import app.facet.android.ui.components.PrimaryButton
import app.facet.android.ui.components.TextLinkButton
import app.facet.android.ui.permission.hasCameraPermission
import app.facet.android.ui.theme.Facet
import app.facet.android.ui.theme.Radius
import app.facet.android.ui.theme.Space
import app.facet.platform.ArServicesStatus
import app.facet.platform.DepthStatus
import app.facet.platform.DeviceCheckResult
import app.facet.platform.DeviceSupport
import app.facet.ui.device.DeviceCheckUiState
import app.facet.ui.device.DeviceCheckViewModel
import com.google.ar.core.ArCoreApk
import com.google.ar.core.exceptions.UnavailableException
import kotlinx.coroutines.delay

/** Device-Check.png / Device-Unsupported.png. Continues on its own when everything is OK. */
@Composable
fun DeviceCheckScreen(onSupported: () -> Unit, onBack: () -> Unit) {
    val container = appContainer()
    val vm: DeviceCheckViewModel = viewModel { DeviceCheckViewModel(container.device) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cameraAllowed = remember(state) { context.hasCameraPermission() }

    // Coming back from the Play Store: check again.
    var sentToStore by remember { mutableStateOf(false) }
    LifecycleResumeEffect(sentToStore) {
        if (sentToStore) { sentToStore = false; vm.check() }
        onPauseOrDispose {}
    }

    val done = state as? DeviceCheckUiState.Done
    if (done?.result?.support == DeviceSupport.SUPPORTED) {
        LaunchedEffect(Unit) {
            delay(READY_PAUSE_MS) // long enough to read the green checklist
            onSupported()
        }
    }

    if (done?.result?.support == DeviceSupport.UNSUPPORTED) {
        UnsupportedScreen(done.result.deviceModel)
        return
    }

    Scaffold(
        containerColor = Facet.colors.bg,
        topBar = { FacetTopBar(stringResource(R.string.device_title), onBack = onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = Space.l)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.l),
            ) {
                Spacer(Modifier.height(Space.s))
                val appName = stringResource(R.string.app_name)
                val result = done?.result
                val (title, body) = when (result?.support) {
                    null -> stringResource(R.string.device_checking_title) to null
                    DeviceSupport.SUPPORTED -> stringResource(R.string.device_ready_title) to null
                    DeviceSupport.NEEDS_INSTALL_OR_UPDATE -> stringResource(
                        if (result.arServices == ArServicesStatus.NEEDS_INSTALL) R.string.device_install_title else R.string.device_update_title,
                    ) to stringResource(R.string.device_update_body, appName)
                    else -> stringResource(R.string.device_failed_title) to stringResource(R.string.device_failed_body)
                }
                Headline(title, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (body != null) {
                    Text(body, style = MaterialTheme.typography.bodyLarge, color = Facet.colors.textMuted)
                }
                Checklist(result, cameraAllowed)
            }

            val activity = context as? Activity
            when (done?.result?.support) {
                DeviceSupport.NEEDS_INSTALL_OR_UPDATE -> {
                    val install = done.result.arServices == ArServicesStatus.NEEDS_INSTALL
                    PrimaryButton(
                        stringResource(if (install) R.string.device_install_action else R.string.device_update_action),
                        onClick = {
                            if (activity != null) {
                                try {
                                    // Opens the Play Store page for Google Play Services for AR.
                                    ArCoreApk.getInstance().requestInstall(activity, true)
                                    sentToStore = true
                                } catch (_: UnavailableException) {
                                    vm.check()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = Space.s),
                    )
                    CheckAgain(vm::check)
                }
                DeviceSupport.CHECK_FAILED -> {
                    PrimaryButton(
                        stringResource(R.string.device_check_again),
                        onClick = vm::check,
                        modifier = Modifier.fillMaxWidth().padding(top = Space.s, bottom = Space.l),
                    )
                }
                else -> Spacer(Modifier.height(Space.l))
            }
        }
    }
}

@Composable
private fun CheckAgain(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = Space.s), contentAlignment = Alignment.Center) {
        TextLinkButton(stringResource(R.string.device_check_again), onClick)
    }
}

private enum class RowState { OK, WAITING, WARNING, ERROR }

@Composable
private fun Checklist(result: DeviceCheckResult?, cameraAllowed: Boolean) {
    val checking = stringResource(R.string.device_checking)
    ListGroup {
        CheckRow(
            stringResource(R.string.device_check_camera),
            if (cameraAllowed) stringResource(R.string.device_check_camera_ok) else stringResource(R.string.device_check_camera_no),
            if (cameraAllowed) RowState.OK else RowState.WARNING,
        )
        ListDivider()
        val (depthText, depthState) = when {
            result == null -> checking to RowState.WAITING
            result.depth == DepthStatus.SUPPORTED -> stringResource(R.string.device_check_depth_ok) to RowState.OK
            result.depth == DepthStatus.UNSUPPORTED -> stringResource(R.string.device_check_depth_no) to RowState.ERROR
            result.arServices == ArServicesStatus.NEEDS_INSTALL || result.arServices == ArServicesStatus.NEEDS_UPDATE ->
                stringResource(R.string.device_check_depth_pending) to RowState.WAITING
            else -> stringResource(R.string.device_check_unknown) to RowState.WARNING
        }
        CheckRow(stringResource(R.string.device_check_depth), depthText, depthState, spinning = result == null)
        ListDivider()
        val (arText, arState) = when (result?.arServices) {
            null -> checking to RowState.WAITING
            ArServicesStatus.READY -> stringResource(R.string.device_check_arcore_ok) to RowState.OK
            ArServicesStatus.NEEDS_UPDATE -> stringResource(R.string.device_check_arcore_update) to RowState.WARNING
            ArServicesStatus.NEEDS_INSTALL -> stringResource(R.string.device_check_arcore_install) to RowState.WARNING
            ArServicesStatus.UNSUPPORTED -> stringResource(R.string.device_check_depth_no) to RowState.ERROR
            ArServicesStatus.UNKNOWN -> stringResource(R.string.device_check_unknown) to RowState.WARNING
        }
        CheckRow(stringResource(R.string.device_check_arcore), arText, arState, spinning = result == null)
    }
}

@Composable
private fun CheckRow(title: String, status: String, state: RowState, spinning: Boolean = false) {
    Row(
        // One item per check: "Camera, Not allowed yet · needed to scan".
        Modifier.fillMaxWidth().heightIn(min = 68.dp).semantics(mergeDescendants = true) {}.padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        StatusMark(state, spinning)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Facet.colors.text)
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = when (state) {
                    RowState.WARNING -> Facet.colors.warning
                    RowState.ERROR -> Facet.colors.danger
                    else -> Facet.colors.textMuted
                },
            )
        }
    }
}

/** Shape differs per state (check, dashed !, ×, spinner) so colour is never the only signal. */
@Composable
private fun StatusMark(state: RowState, spinning: Boolean) {
    val size = 28.dp
    when {
        spinning -> BusyIndicator(Facet.colors.textMuted, Modifier.size(size).padding(3.dp), strokeWidth = 2.5.dp)
        state == RowState.OK -> Box(
            Modifier.size(size).background(Facet.colors.successContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_check), null, tint = Facet.colors.success, modifier = Modifier.size(18.dp)) }
        state == RowState.ERROR -> Box(
            Modifier.size(size).background(Facet.colors.dangerContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_close), null, tint = Facet.colors.danger, modifier = Modifier.size(18.dp)) }
        else -> {
            val color = if (state == RowState.WARNING) Facet.colors.warning else Facet.colors.textMuted
            DashedBang(color, Modifier.size(size), bang = state == RowState.WARNING)
        }
    }
}

@Composable
private fun DashedBang(color: Color, modifier: Modifier, bang: Boolean) {
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        drawCircle(
            color, radius = size.minDimension / 2 - stroke,
            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
        )
        if (bang) {
            val cx = size.width / 2
            drawLine(color, Offset(cx, size.height * 0.30f), Offset(cx, size.height * 0.56f), stroke, StrokeCap.Round)
            drawCircle(color, stroke * 0.7f, Offset(cx, size.height * 0.70f))
        }
    }
}

/** Device-Unsupported.png */
@Composable
private fun UnsupportedScreen(deviceModel: String) {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val model = deviceModel.ifBlank { stringResource(R.string.device_this_phone) }
    val url = stringResource(R.string.device_supported_url)
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = Space.l)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Spacer(Modifier.height(96.dp))
            IconTile(R.drawable.ic_smartphone, container = Facet.colors.dangerContainer, tint = Facet.colors.danger)
            Headline(stringResource(R.string.unsupported_title))
            Text(
                stringResource(R.string.unsupported_body, appName, model),
                style = MaterialTheme.typography.bodyLarge,
                color = Facet.colors.textMuted,
            )
            Surface(
                color = Facet.colors.surface,
                shape = RoundedCornerShape(Radius.card),
                border = BorderStroke(1.dp, Facet.colors.border),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                    Text(stringResource(R.string.unsupported_card_title), style = MaterialTheme.typography.titleSmall, color = Facet.colors.text, modifier = Modifier.semantics { heading() })
                    Text(
                        stringResource(R.string.unsupported_card_body, appName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Facet.colors.textMuted,
                    )
                }
            }
        }
        OutlineButton(
            stringResource(R.string.unsupported_see_list),
            onClick = {
                // The app has no network access; this hands the link to the browser.
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                } catch (_: ActivityNotFoundException) {
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = Space.s),
        )
        TextLinkButton(
            stringResource(R.string.unsupported_close),
            onClick = { (context as? Activity)?.finishAffinity() },
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = Space.s),
        )
    }
}

private const val READY_PAUSE_MS = 700L
