package com.whatsappv2.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * Explains why a permission is needed, before the system dialog appears.
 *
 * Shown in context rather than at launch, and it always offers a way out: a user who
 * declines the microphone should still reach the rest of the app. Task 15 wires the
 * denial paths; this is the surface they use.
 *
 * [permanentlyDenied] switches the primary action to "Open settings", because once
 * Android has recorded a permanent denial the system dialog will never appear again and
 * a button that silently does nothing is worse than no button.
 */
@Composable
fun PermissionRationaleSheet(
    icon: ImageVector,
    title: String,
    rationale: String,
    onRequest: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    permanentlyDenied: Boolean = false,
    dismissLabel: String = "Not now",
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = AppTheme.spacing.extraLarge,
                    end = AppTheme.spacing.extraLarge,
                    bottom = AppTheme.spacing.huge,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppTheme.spacing.large),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(AppTheme.sizing.avatarSmall),
            )
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = rationale,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                Text(if (permanentlyDenied) "Open settings" else "Continue")
            }
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        }
    }
}

@ThemePreviews
@Composable
private fun PermissionRationaleSheetPreview() = PreviewSurface {
    PermissionRationaleSheet(
        icon = Icons.Filled.Mic,
        title = "Microphone access",
        rationale = "Calls need the microphone so the person you are speaking to can hear you. " +
            "Audio is never recorded unless you start a recording yourself.",
        onRequest = {},
        onDismiss = {},
    )
}

@ThemePreviews
@Composable
private fun PermissionPermanentlyDeniedPreview() = PreviewSurface {
    PermissionRationaleSheet(
        icon = Icons.Filled.Mic,
        title = "Microphone access",
        rationale = "Microphone access was turned off. Calls cannot send your voice until it is " +
            "enabled in Settings.",
        permanentlyDenied = true,
        onRequest = {},
        onDismiss = {},
    )
}
