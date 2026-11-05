package app.facet.android.ui.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.facet.android.R
import app.facet.android.ui.components.Headline
import app.facet.android.ui.components.IconTile
import app.facet.android.ui.components.PrimaryButton
import app.facet.android.ui.components.TextLinkButton
import app.facet.android.ui.theme.Facet
import app.facet.android.ui.theme.Radius
import app.facet.android.ui.theme.Space

fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * Permission-Camera.png. Allow → system dialog. Once Android stops showing the dialog
 * ("don't ask again"), Allow opens the app's page in Android Settings instead.
 */
@Composable
fun CameraPermissionScreen(onGranted: () -> Unit, onNotNow: () -> Unit) {
    val context = LocalContext.current
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            onGranted()
        } else {
            val activity = context as? Activity
            permanentlyDenied = activity != null &&
                !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
        }
    }

    // Returning from Android Settings with the permission switched on. Only after a trip to
    // Settings, so navigating back to this screen doesn't bounce the user forward again.
    var sentToSettings by rememberSaveable { mutableStateOf(false) }
    val openSettings = {
        sentToSettings = true
        context.openAppSettings()
    }
    LifecycleResumeEffect(sentToSettings) {
        if (sentToSettings && context.hasCameraPermission()) {
            sentToSettings = false
            onGranted()
        }
        onPauseOrDispose {}
    }

    val appName = stringResource(R.string.app_name)
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = Space.l),
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            Spacer(Modifier.height(96.dp))
            IconTile(R.drawable.ic_camera)
            Headline(stringResource(R.string.perm_cam_title, appName))
            Text(
                stringResource(R.string.perm_cam_body),
                style = MaterialTheme.typography.bodyLarge,
                color = Facet.colors.textMuted,
            )
            SettingsHintCard(onClick = openSettings)
        }
        PrimaryButton(
            stringResource(R.string.perm_cam_allow),
            onClick = {
                if (permanentlyDenied) openSettings() else launcher.launch(Manifest.permission.CAMERA)
            },
            modifier = Modifier.fillMaxWidth().padding(top = Space.s),
        )
        TextLinkButton(
            stringResource(R.string.perm_cam_later),
            onClick = onNotNow,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = Space.s),
        )
    }
}

@Composable
private fun SettingsHintCard(onClick: () -> Unit) {
    val full = stringResource(R.string.perm_cam_denied)
    val link = stringResource(R.string.perm_cam_settings_link)
    val linkColor = Facet.colors.accentInk
    val text = buildAnnotatedString {
        append(full)
        val start = full.indexOf(link)
        if (start >= 0) {
            addStyle(
                SpanStyle(color = linkColor, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline),
                start, start + link.length,
            )
        }
    }
    Surface(
        color = Facet.colors.surface,
        shape = RoundedCornerShape(Radius.card),
        border = BorderStroke(1.dp, Facet.colors.border),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    ) {
        Row(Modifier.padding(Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Icon(painterResource(R.drawable.ic_info), null, tint = Facet.colors.textMuted, modifier = Modifier.size(22.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = Facet.colors.textMuted)
        }
    }
}
