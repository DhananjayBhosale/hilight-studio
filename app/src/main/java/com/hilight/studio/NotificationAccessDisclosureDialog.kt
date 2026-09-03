package com.hilight.studio

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Explains sensitive notification access before Android asks the user to grant it. */
@Composable
fun NotificationAccessDisclosureDialog(
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.notification_access_disclosure_title)) },
        text = {
            Text(
                stringResource(R.string.notification_access_disclosure_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                ButtonLabel(stringResource(R.string.notification_access_disclosure_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                ButtonLabel(stringResource(R.string.notification_access_disclosure_cancel))
            }
        },
    )
}
