package com.whatsappv2.feature.calls

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.whatsappv2.core.designsystem.component.ConfirmDialog
import com.whatsappv2.domain.call.SecondCallResponse

/**
 * The escalation prompt (Task 54, §5.2).
 *
 * A dialog, and a modal one, because the far end's re-INVITE is being held open while it
 * is up: this is a question somebody is waiting on the answer to, not a notification.
 *
 * Declining does **not** end the call — the label says "Keep audio" rather than "Decline"
 * for exactly that reason. Task 54's second done-when is that the audio call survives, and
 * a button labelled "Decline" on a call screen reads like it hangs up.
 */
@Composable
internal fun VideoRequestPrompt(
    request: PendingVideoRequest,
    onRespond: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    ConfirmDialog(
        title = "Turn on video?",
        message = "${request.from} would like to add video to this call. " +
            "Your camera will only start if you accept.",
        confirmLabel = "Turn on video",
        dismissLabel = "Keep audio",
        onConfirm = { onRespond(true) },
        onDismiss = { onRespond(false) },
        modifier = modifier.testTag(TAG_VIDEO_PROMPT),
    )
}

/**
 * The consent gate in front of every recording (Task 58, §2.6, §7).
 *
 * ## Why the wording is this specific
 *
 * §2.6 forbids a silent recorder, and a dialog that says only "Record this call?" is a
 * silent recorder with a button on it: the user is not told that the other party is not
 * being asked, and in many jurisdictions that is the difference between a recording they
 * may keep and one they may not. The text says who has consented — them — and leaves the
 * other party's agreement as something they have to obtain themselves.
 *
 * `destructive` is false: this is not a deletion. It is deliberately not styled as a
 * routine confirmation either, which is why the message is three sentences rather than a
 * label.
 */
@Composable
internal fun RecordingConsentPrompt(
    remoteName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ConfirmDialog(
        title = "Record this call?",
        message = "This records the audio of your call with $remoteName onto this device, " +
            "encrypted. $remoteName is not asked and is not told — telling them is up to " +
            "you, and in many places it is required by law.",
        confirmLabel = "Start recording",
        dismissLabel = "Not now",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        modifier = modifier.testTag(TAG_RECORDING_PROMPT),
    )
}

/**
 * A second call arriving during one already in progress (Task 56, §5.2).
 *
 * Three buttons, not two, and that is the requirement. "Accept" alone hides a decision the
 * user has every right to make: whether the person they are already speaking to gets put
 * on hold or hung up on. Both names are on screen so the choice is between two people
 * rather than between two verbs.
 *
 * Not dismissible by tapping outside: the second caller is ringing and something has to
 * answer them. `onDismissRequest` rejects, which is the safe default — it leaves the call
 * in progress untouched.
 */
@Composable
internal fun SecondCallPromptDialog(
    prompt: SecondCallPrompt,
    onRespond: (SecondCallResponse) -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = { onRespond(SecondCallResponse.REJECT) },
        modifier = modifier.testTag(TAG_SECOND_CALL),
        title = { Text("${prompt.from} is calling") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "You are on a call with ${prompt.currentCallWith}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRespond(SecondCallResponse.ACCEPT_AND_HOLD) }) {
                Text("Hold and answer")
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { onRespond(SecondCallResponse.ACCEPT_AND_END) }) {
                    Text("End and answer")
                }
                TextButton(onClick = { onRespond(SecondCallResponse.REJECT) }) {
                    Text("Decline")
                }
            }
        },
    )
}

internal const val TAG_VIDEO_PROMPT = "call-video-request"
internal const val TAG_RECORDING_PROMPT = "call-recording-consent"
internal const val TAG_SECOND_CALL = "call-second-call"
