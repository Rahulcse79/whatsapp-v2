package com.whatsappv2.feature.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PhoneForwarded
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.whatsappv2.core.designsystem.component.CallActionButton
import com.whatsappv2.core.designsystem.theme.AppTheme

/**
 * The second row of in-call controls: video, camera, transfer, record (Tasks 53-58).
 *
 * A row of its own rather than eight buttons in one. Four is what fits across a phone
 * without shrinking the targets below the minimum, and the split is not arbitrary — the
 * first row is what every call has, and this row is what only some calls do.
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
) {
    val availability = call.availability
    val videoOn = call.controls.isVideoEnabled

    Row(
        modifier = modifier.fillMaxWidth().padding(top = AppTheme.spacing.medium),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        CallActionButton(
            icon = if (videoOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
            contentDescription = if (videoOn) "Turn off my video" else "Turn on my video",
            activeStateDescription = if (videoOn) "On" else "Off",
            onClick = { actions.onToggleVideo(!videoOn) },
            enabled = availability.canToggleVideo,
            active = videoOn,
            label = "Video",
            modifier = Modifier.testTag(TAG_VIDEO_TOGGLE),
        )
        CallActionButton(
            icon = Icons.Filled.Cameraswitch,
            contentDescription = "Switch camera",
            onClick = actions.onSwitchCamera,
            // Only while a camera is actually running: switching one that is off does
            // nothing, and a button that does nothing reads as broken (Task 53).
            enabled = availability.canSwitchCamera,
            label = "Flip",
            modifier = Modifier.testTag(TAG_SWITCH_CAMERA),
        )
        CallActionButton(
            icon = Icons.AutoMirrored.Filled.PhoneForwarded,
            contentDescription = "Transfer this call",
            onClick = actions.onStartTransfer,
            enabled = availability.canTransfer,
            label = "Transfer",
            modifier = Modifier.testTag(TAG_TRANSFER),
        )
        CallActionButton(
            icon = Icons.Filled.FiberManualRecord,
            contentDescription = if (recording.isRecording) "Stop recording" else "Record this call",
            activeStateDescription = if (recording.isRecording) "Recording" else "Not recording",
            // Starting always goes through the consent dialog; there is no path from this
            // button to a running recorder that skips it (§2.6, Task 58).
            onClick = if (recording.isRecording) actions.onStopRecording else actions.onRequestRecording,
            enabled = availability.canRecord,
            active = recording.isRecording,
            label = "Record",
            modifier = Modifier.testTag(TAG_RECORD),
        )
    }
}

internal const val TAG_VIDEO_TOGGLE = "call-video-toggle"
internal const val TAG_SWITCH_CAMERA = "call-switch-camera"
internal const val TAG_TRANSFER = "call-transfer"
internal const val TAG_RECORD = "call-record"
