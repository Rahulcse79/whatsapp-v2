package com.whatsappv2.feature.calls

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * Choosing where to transfer a call, and watching it happen (Tasks 55, 57, DoD 10).
 *
 * ## Two buttons, because there are two transfers
 *
 * "Transfer now" is blind: the REFER goes out and this app drops out of the call
 * immediately, never learning whether anybody answered. "Ask first" is attended: the call
 * is held, the target is rung, and the user speaks to them before deciding. They are
 * genuinely different promises to the caller being transferred, so they are two buttons
 * rather than a checkbox somebody will not read.
 *
 * ## Progress and failure are both on screen
 *
 * Task 55's second done-when. [TransferUiState.InProgress] keeps the sheet up with what is
 * known — nothing at first, "Ringing" once a sipfrag says so — and
 * [TransferUiState.Failed] keeps it up with the reason. The failure stays until it is
 * dismissed rather than fading: the caller is still on the line, and the user needs long
 * enough to read "that extension is busy" and decide what to do about it.
 */
@Composable
internal fun CallTransferSheet(
    state: TransferUiState,
    actions: CallActions,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is TransferUiState.Idle -> Unit

        is TransferUiState.Choosing -> TransferTargetDialog(state, actions, modifier)

        is TransferUiState.InProgress -> TransferStatusDialog(
            title = "Transferring",
            // Named where it is known. "Transferring to 1002" is a different reassurance
            // from a spinner, and the target is always known here because the user typed it.
            message = listOfNotNull(state.target.takeIf { it.isNotBlank() }, state.detail)
                .joinToString(" — ")
                .ifBlank { "Asking the other end to take the call…" },
            dismissLabel = "Hide",
            onDismiss = actions.onCancelTransfer,
            modifier = modifier,
        )

        is TransferUiState.Failed -> TransferStatusDialog(
            title = "Transfer failed",
            message = "${state.reason}. The call is still connected.",
            dismissLabel = "OK",
            onDismiss = actions.onCancelTransfer,
            modifier = modifier,
        )

        is TransferUiState.Consulting -> ConsultationDialog(state, actions, modifier)
    }
}

@Composable
private fun TransferTargetDialog(
    state: TransferUiState.Choosing,
    actions: CallActions,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = actions.onCancelTransfer,
        modifier = modifier.testTag(TAG_TRANSFER_SHEET),
        title = { Text("Transfer call") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = actions.onTransferTargetChanged,
                    label = { Text("Extension or address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_TRANSFER_TARGET),
                )
                Text(
                    text = "Transfer now hands the call over immediately. " +
                        "Ask first puts this call on hold so you can speak to them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AppTheme.spacing.small),
                )
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = { actions.onStartConsultation(state.input) },
                    enabled = state.input.isNotBlank(),
                ) { Text("Ask first") }
                TextButton(
                    onClick = { actions.onTransferBlind(state.input) },
                    enabled = state.input.isNotBlank(),
                ) { Text("Transfer now") }
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelTransfer) { Text("Cancel") }
        },
    )
}

/**
 * The consultation, with the two ways out of it (Task 57).
 *
 * "Complete" sends the REFER with `Replaces` and both legs end here. "Go back" hangs up
 * the consultation and resumes the original — Task 57's second done-when, and the reason
 * it is a button rather than something the user is expected to assemble from a hangup and
 * a resume.
 */
@Composable
private fun ConsultationDialog(
    state: TransferUiState.Consulting,
    actions: CallActions,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = actions.onCancelConsultation,
        modifier = modifier.testTag(TAG_CONSULTATION),
        title = { Text("Speaking to ${state.target}") },
        text = {
            Text(
                text = "Your first call is on hold. Complete the transfer to connect them, " +
                    "or go back to leave things as they were.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = actions.onCompleteConsultation) { Text("Complete transfer") }
        },
        dismissButton = {
            TextButton(onClick = actions.onCancelConsultation) { Text("Go back") }
        },
    )
}

@Composable
private fun TransferStatusDialog(
    title: String,
    message: String,
    dismissLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.testTag(TAG_TRANSFER_STATUS),
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

internal const val TAG_TRANSFER_SHEET = "call-transfer-sheet"
internal const val TAG_TRANSFER_TARGET = "call-transfer-target"
internal const val TAG_TRANSFER_STATUS = "call-transfer-status"
internal const val TAG_CONSULTATION = "call-consultation"
