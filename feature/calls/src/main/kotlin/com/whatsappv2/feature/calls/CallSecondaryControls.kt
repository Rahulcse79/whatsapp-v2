package com.whatsappv2.feature.calls

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PhoneForwarded
import androidx.compose.material.icons.filled.AddIcCall
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.whatsappv2.core.designsystem.component.CallActionButton
import com.whatsappv2.core.designsystem.component.CallControlGrid
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * The rest of the in-call controls, under the four every call has.
 *
 * ## The order is fixed, and the conditional ones come last
 *
 * Video, flip, add, transfer — then record, then merge. Video and its camera lead because
 * they are the pair a user reaches for together and the second row is where the call's
 * *media* is changed. Merge is last because it is the only one that appears and
 * disappears mid-call, and a control that shifts its neighbours is a control that changes
 * meaning under a thumb already on its way down: a second call arriving must not slide
 * Transfer into the space Record was occupying.
 *
 * Flip stays in place rather than being hidden when no camera is running. It is disabled,
 * which says "not now" — removing it would say "not here", and would move everything after
 * it every time video went on or off.
 *
 * ## Four to a row, on the shared grid
 *
 * [CallControlGrid] divides by a constant rather than by how many controls this row
 * happens to hold, which is what stops the two rows drifting out of alignment. See its own
 * comment for what that was doing to the screen.
 *
 * Every `enabled` comes from [CallControlAvailability], exactly as the first row's does.
 * A control offered for something the state machine would refuse is the class of bug that
 * arrangement exists to prevent, and it applies no less to a transfer button than to hold.
 */
@Composable
internal fun CallSecondaryControls(
    call: CallDisplay,
    recording: RecordingUiState,
    actions: CallActions,
    modifier: Modifier = Modifier,
    /** True when this device is holding two or more established calls (ADR-009). */
    canMerge: Boolean = false,
    /** How many calls are already mixed, so the control can say so rather than repeat. */
    mixedCallCount: Int = 0,
    /** Actions the engine has not answered yet, shown busy rather than toggled (Task 76). */
    pending: Set<CallAction> = emptySet(),
) {
    val availability = call.availability
    val videoOn = call.controls.isVideoEnabled
    val showsMerge = canMerge || mixedCallCount > 0

    CallControlGrid(
        modifier = modifier.padding(top = AppTheme.spacing.large),
        controls = buildList {
            add { VideoButton(videoOn, availability.canToggleVideo, CallAction.VIDEO in pending, actions) }
            add { FlipButton(availability.canSwitchCamera, CallAction.SWITCH_CAMERA in pending, actions) }
            add { AddCallButton(availability.canAddCall, actions) }
            add { TransferButton(availability.canTransfer, actions) }
            add { RecordButton(recording, availability.canRecord, actions) }
            if (showsMerge) add { MergeButton(canMerge, mixedCallCount, actions.onMerge, CallAction.MERGE in pending) }
        },
    )
}

/** Adds or drops the video stream by re-INVITE (Tasks 53, 54). */
@Composable
private fun VideoButton(on: Boolean, enabled: Boolean, pending: Boolean, actions: CallActions) {
    CallActionButton(
        icon = if (on) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
        contentDescription = if (on) "Turn off my video" else "Turn on my video",
        activeStateDescription = if (on) "On" else "Off",
        onClick = { actions.onToggleVideo(!on) },
        enabled = enabled,
        active = on,
        label = "Video",
        pending = pending,
        pendingStateDescription = if (on) "Stopping video" else "Starting video",
        modifier = Modifier.testTag(TAG_VIDEO_TOGGLE),
    )
}

/**
 * Front camera to back.
 *
 * Disabled rather than hidden while no camera is running: switching one that is off does
 * nothing, and a button that does nothing reads as broken (Task 53) — but a button that
 * vanishes takes the whole row's positions with it.
 */
@Composable
private fun FlipButton(enabled: Boolean, pending: Boolean, actions: CallActions) {
    CallActionButton(
        icon = Icons.Filled.Cameraswitch,
        contentDescription = "Switch camera",
        onClick = actions.onSwitchCamera,
        enabled = enabled,
        label = "Flip",
        pending = pending,
        pendingStateDescription = "Switching camera",
        modifier = Modifier.testTag(TAG_SWITCH_CAMERA),
    )
}

/** Opens the dialler for a second leg, which is how a conference starts (ADR-009). */
@Composable
private fun AddCallButton(enabled: Boolean, actions: CallActions) {
    CallActionButton(
        icon = Icons.Filled.AddIcCall,
        contentDescription = "Add a call",
        onClick = actions.onAddCall,
        enabled = enabled,
        label = "Add",
        modifier = Modifier.testTag(TAG_ADD_CALL),
    )
}

/** Hands this call to somebody else, blind or after asking (Task 55). */
@Composable
private fun TransferButton(enabled: Boolean, actions: CallActions) {
    CallActionButton(
        icon = Icons.AutoMirrored.Filled.PhoneForwarded,
        contentDescription = "Transfer this call",
        onClick = actions.onStartTransfer,
        enabled = enabled,
        label = "Transfer",
        modifier = Modifier.testTag(TAG_TRANSFER),
    )
}

/**
 * Starts and stops the recorder (Task 58).
 *
 * Starting always goes through the consent dialog; there is no path from this button to a
 * running recorder that skips it (§2.6).
 */
@Composable
private fun RecordButton(recording: RecordingUiState, enabled: Boolean, actions: CallActions) {
    CallActionButton(
        icon = Icons.Filled.FiberManualRecord,
        contentDescription = if (recording.isRecording) "Stop recording" else "Record this call",
        activeStateDescription = if (recording.isRecording) "Recording" else "Not recording",
        onClick = if (recording.isRecording) actions.onStopRecording else actions.onRequestRecording,
        enabled = enabled,
        active = recording.isRecording,
        label = "Record",
        modifier = Modifier.testTag(TAG_RECORD),
    )
}

/**
 * Mixes the calls on this device into one conference (ADR-009).
 *
 * The one control that is not always present. A control that sits present-but-disabled
 * through every ordinary call teaches people to ignore it, and merging is meaningless with
 * one call — the bridge needs two established legs before it has anything to mix. It is
 * last in the row so that appearing moves nothing.
 */
@Composable
private fun MergeButton(canMerge: Boolean, mixedCallCount: Int, onMerge: () -> Unit, pending: Boolean) {
    val merged = mixedCallCount >= MIN_MIXED
    CallActionButton(
        icon = Icons.Filled.Groups,
        contentDescription = if (merged) "$mixedCallCount calls merged" else "Merge these calls",
        activeStateDescription = if (merged) "Merged" else "Not merged",
        onClick = onMerge,
        enabled = canMerge,
        active = merged,
        label = if (merged) "Merged" else "Merge",
        pending = pending,
        pendingStateDescription = "Merging the calls",
        modifier = Modifier.testTag(TAG_MERGE),
    )
}

internal const val TAG_ADD_CALL = "call-add-call"
internal const val TAG_VIDEO_TOGGLE = "call-video-toggle"
internal const val TAG_SWITCH_CAMERA = "call-switch-camera"
internal const val TAG_TRANSFER = "call-transfer"
internal const val TAG_RECORD = "call-record"
internal const val TAG_MERGE = "call-merge"

/** Two mixed calls is the least that reads as a conference rather than a call (ADR-009). */
private const val MIN_MIXED = 2
