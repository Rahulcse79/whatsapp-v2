package com.whatsappv2.core.designsystem.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews

/**
 * Confirmation for an action that cannot be undone.
 *
 * [destructive] colours the confirm button as an error and is not decoration: deleting a
 * SIP account discards credentials the user may not be able to recover, and it must not
 * look like "OK".
 *
 * Deliberately has no "don't ask again": the actions this guards are rare and
 * irreversible, and a remembered dismissal turns one careless tap into a silent policy.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = confirmLabel,
                    color = if (destructive) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
        modifier = modifier,
    )
}

@ThemePreviews
@Composable
private fun ConfirmDialogPreview() = PreviewSurface {
    ConfirmDialog(
        title = "Delete account?",
        message = "The SIP password for alice@sip.example.com will be removed from this device.",
        confirmLabel = "Delete",
        destructive = true,
        onConfirm = {},
        onDismiss = {},
    )
}

@ThemePreviews
@Composable
private fun ConfirmDialogNonDestructivePreview() = PreviewSurface {
    ConfirmDialog(
        title = "Register now?",
        message = "This will retry registration immediately instead of waiting for the next attempt.",
        confirmLabel = "Register",
        onConfirm = {},
        onDismiss = {},
    )
}
