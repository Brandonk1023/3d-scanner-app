package app.facet.android.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import app.facet.android.R
import app.facet.android.ui.theme.Facet

@Composable
private fun DialogButton(text: String, onClick: () -> Unit, color: androidx.compose.ui.graphics.Color = Facet.colors.text) {
    TextButton(onClick = onClick) {
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = color)
    }
}

@Composable
fun RenameDialog(currentName: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(currentName, TextRange(0, currentName.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = value.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Facet.colors.surface,
        title = { Text(stringResource(R.string.rename_title), color = Facet.colors.text) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.rename_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.focusRequester(focus),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Facet.colors.text,
                    focusedLabelColor = Facet.colors.text,
                    cursorColor = Facet.colors.text,
                ),
            )
        },
        confirmButton = {
            DialogButton(stringResource(R.string.action_save), onClick = { if (valid) { onRename(value.text); onDismiss() } })
        },
        dismissButton = { DialogButton(stringResource(R.string.action_cancel), onDismiss, Facet.colors.textMuted) },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Facet.colors.surface,
        title = { Text(title, color = Facet.colors.text) },
        text = { Text(body, color = Facet.colors.textMuted, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            DialogButton(confirmLabel, { onConfirm(); onDismiss() }, if (destructive) Facet.colors.danger else Facet.colors.text)
        },
        dismissButton = { DialogButton(stringResource(R.string.action_cancel), onDismiss, Facet.colors.textMuted) },
    )
}

@Composable
fun DeleteScanDialog(name: String, onDelete: () -> Unit, onDismiss: () -> Unit) = ConfirmDialog(
    title = stringResource(R.string.delete_title, name),
    body = stringResource(R.string.delete_body),
    confirmLabel = stringResource(R.string.act_delete),
    onConfirm = onDelete,
    onDismiss = onDismiss,
)

/** Single-choice dialog (Appearance, Units, Default result). Selecting closes it. */
@Composable
fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Facet.colors.surface,
        title = { Text(title, color = Facet.colors.text) },
        text = { ChoiceList(options, selected) { onSelect(it); onDismiss() } },
        confirmButton = {},
        dismissButton = { DialogButton(stringResource(R.string.action_cancel), onDismiss, Facet.colors.textMuted) },
    )
}

/** Plain information dialog (privacy, licences). */
@Composable
fun InfoDialog(title: String, body: @Composable () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Facet.colors.surface,
        title = { Text(title, color = Facet.colors.text) },
        text = body,
        confirmButton = { DialogButton(stringResource(R.string.action_close), onDismiss) },
    )
}
