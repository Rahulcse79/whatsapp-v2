package com.whatsappv2.feature.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.whatsappv2.core.designsystem.component.Avatar
import com.whatsappv2.core.designsystem.component.CallActionButton
import com.whatsappv2.core.designsystem.component.CallActionStyle
import com.whatsappv2.core.designsystem.preview.PreviewSurface
import com.whatsappv2.core.designsystem.preview.ThemePreviews
import com.whatsappv2.core.designsystem.theme.AppTheme
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.call.CallControls
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.model.CallId

/**
 * The call screen (Tasks 37 and 39).
 *
 * One screen for both directions. An incoming call is not a different screen — it is the
 * same call in a different phase, showing answer and decline instead of the in-call
 * controls. Two screens would mean two places to keep the identity, the timer and the
 * theming in step, and they would drift the first time one of them changed.
 *
 * Every button's `enabled` comes from [CallControlAvailability], which is derived from the
 * phase. Nothing here decides whether an action is possible.
 */
@Composable
internal fun CallScreen(
    state: CallUiState,
    snackbarHostState: SnackbarHostState,
    actions: CallActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                // Deliberately not a spinner. The screen is opened by a notification for a
                // call that already exists, so this lasts a frame; a spinner would flash.
                is CallUiState.Loading -> Text(
                    text = "Connecting",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag(TAG_CONNECTING),
                )

                is CallUiState.Finished -> Text(
                    text = "Call ended",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag(TAG_ENDED),
                )

                is CallUiState.Active -> ActiveCall(call = state.call, actions = actions)
            }
        }
    }
}

@Composable
private fun ActiveCall(call: CallDisplay, actions: CallActions) {
    // Local to the screen, because neither is call state: the keypad being open is a view
    // preference, and the digits are a record of what was sent, which the engine does not
    // keep and must not be asked for. Saveable, so a rotation mid-sequence does not lose
    // the half a caller has already typed into an IVR.
    var keypadOpen by rememberSaveable(call.callId.value) { mutableStateOf(false) }
    var dialled by rememberSaveable(call.callId.value) { mutableStateOf("") }

    // Derived rather than written back: a call that stops being connected hides the keypad,
    // because DTMF needs a running media path, but the user's own choice is remembered and
    // the keys come back when the call does. Assigning to the state here instead would be a
    // write during composition, which is how a recomposition loop starts.
    val keypadShown = keypadOpen && call.availability.canSendDtmf

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(AppTheme.spacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        // The avatar gives way to the keypad rather than being scrolled off it: on a small
        // screen both do not fit, and the keys are what the caller is trying to reach.
        CallIdentity(call = call, showAvatar = !keypadShown)

        Spacer(Modifier.weight(1f))

        if (call.availability.canAnswer) {
            IncomingActions(call = call, actions = actions)
        } else {
            if (keypadShown) {
                CallKeypad(
                    dialled = dialled,
                    onDigit = {
                        dialled += it.symbol
                        actions.onDtmf(it)
                    },
                    onHide = { keypadOpen = false },
                    modifier = Modifier.padding(bottom = AppTheme.spacing.large),
                )
            }
            InCallActions(
                call = call,
                actions = actions,
                keypadOpen = keypadShown,
                onToggleKeypad = { keypadOpen = !keypadShown },
            )
        }
    }
}

/**
 * Who the call is with, and what it is doing.
 *
 * The same block in every phase, because a call's identity does not change when its state
 * does — only the line underneath it does, and that line is [CallDisplay.statusLine].
 */
@Composable
private fun CallIdentity(call: CallDisplay, showAvatar: Boolean) {
    if (showAvatar) Avatar(displayName = call.title, size = AppTheme.sizing.avatarLarge)

    Text(
        text = call.title,
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(top = AppTheme.spacing.large)
            .testTag(TAG_TITLE),
    )
    call.subtitle?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = AppTheme.spacing.extraSmall),
        )
    }

    Text(
        text = call.statusLine(),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = AppTheme.spacing.small)
            .testTag(TAG_STATUS),
    )
}

@Composable
private fun IncomingActions(call: CallDisplay, actions: CallActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = AppTheme.spacing.extraLarge),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        CallActionButton(
            icon = Icons.Filled.CallEnd,
            contentDescription = "Decline call",
            onClick = actions.onReject,
            style = CallActionStyle.HANG_UP,
            enabled = call.availability.canReject,
            label = "Decline",
            modifier = Modifier.testTag(TAG_DECLINE),
        )
        // Offered only when the caller offered video. An audio call answered "with video"
        // is an escalation the peer never asked for (§5.2).
        if (call.videoOffered) {
            CallActionButton(
                icon = Icons.Filled.Videocam,
                contentDescription = "Answer with video",
                onClick = { actions.onAnswer(true) },
                style = CallActionStyle.ANSWER,
                label = "Video",
                modifier = Modifier.testTag(TAG_ANSWER_VIDEO),
            )
        }
        CallActionButton(
            icon = Icons.Filled.Call,
            contentDescription = "Answer call",
            onClick = { actions.onAnswer(false) },
            style = CallActionStyle.ANSWER,
            enabled = call.availability.canAnswer,
            label = "Answer",
            modifier = Modifier.testTag(TAG_ANSWER),
        )
    }
}

@Composable
private fun InCallActions(
    call: CallDisplay,
    actions: CallActions,
    keypadOpen: Boolean,
    onToggleKeypad: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CallControlRow(
            call = call,
            actions = actions,
            keypadOpen = keypadOpen,
            onToggleKeypad = onToggleKeypad,
        )

        CallActionButton(
            icon = Icons.Filled.CallEnd,
            contentDescription = "End call",
            onClick = actions.onHangUp,
            style = CallActionStyle.HANG_UP,
            enabled = call.availability.canHangUp,
            label = "End",
            modifier = Modifier
                .padding(top = AppTheme.spacing.extraLarge, bottom = AppTheme.spacing.extraLarge)
                .testTag(TAG_HANG_UP),
        )
    }
}

/**
 * Mute, speaker, hold and the keypad.
 *
 * Every `enabled` here comes from [CallControlAvailability]; there is no flag to forget to
 * set, which is Task 39's second done-when expressed as code.
 */
@Composable
private fun CallControlRow(
    call: CallDisplay,
    actions: CallActions,
    keypadOpen: Boolean,
    onToggleKeypad: () -> Unit,
) {
    val controls = call.controls
    val availability = call.availability
    val speakerOn = controls.audioRoute == AudioRoute.SPEAKER
    val held = availability.canResume

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        CallActionButton(
            icon = if (controls.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
            contentDescription = if (controls.isMuted) "Unmute microphone" else "Mute microphone",
            activeStateDescription = if (controls.isMuted) "Muted" else "Not muted",
            onClick = { actions.onToggleMute(!controls.isMuted) },
            enabled = availability.canMute,
            active = controls.isMuted,
            label = "Mute",
            modifier = Modifier.testTag(TAG_MUTE),
        )
        CallActionButton(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = if (speakerOn) "Turn off speakerphone" else "Turn on speakerphone",
            activeStateDescription = if (speakerOn) "On" else "Off",
            onClick = { actions.onToggleSpeaker(!speakerOn) },
            enabled = availability.canChangeRoute,
            active = speakerOn,
            label = "Speaker",
            modifier = Modifier.testTag(TAG_SPEAKER),
        )
        CallActionButton(
            icon = if (held) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = if (held) "Resume call" else "Hold call",
            activeStateDescription = if (held) "On hold" else "Not on hold",
            onClick = { actions.onToggleHold(!held) },
            // Task 39: unavailable before Connected, and it is the phase that says so.
            enabled = availability.canHold || availability.canResume,
            active = held,
            label = "Hold",
            modifier = Modifier.testTag(TAG_HOLD),
        )
        CallActionButton(
            icon = Icons.Filled.Dialpad,
            contentDescription = if (keypadOpen) "Hide the keypad" else "Show the keypad",
            activeStateDescription = if (keypadOpen) "Shown" else "Hidden",
            onClick = onToggleKeypad,
            // A tone needs a running media path, so the phase decides this exactly as
            // it decides hold - and a held call's keypad is disabled, not hidden, so
            // the control does not move under the user's thumb (Task 43).
            enabled = availability.canSendDtmf,
            active = keypadOpen,
            label = "Keypad",
            modifier = Modifier.testTag(TAG_KEYPAD_TOGGLE),
        )
    }
}

/**
 * The line under the caller's name.
 *
 * The duration replaces the phase once the call connects, because at that point the phase
 * is obvious and the duration is the thing that changes.
 */
private fun CallDisplay.statusLine(): String = when {
    phase == CallPhase.CONNECTED && durationSeconds != null -> formatDuration(durationSeconds)
    phase == CallPhase.INCOMING && direction == CallDirection.INCOMING -> "Incoming call"
    else -> phase.label()
}

/**
 * What each phase is called on screen.
 *
 * Its own function, not a branch of [statusLine]: eleven phases plus the two exceptions
 * above them is one decision too many for detekt to read as one, and the two questions -
 * "does the duration replace the phase" and "what is this phase called" - are separate.
 */
private fun CallPhase.label(): String = when (this) {
    CallPhase.CALLING -> "Calling"
    CallPhase.RINGING -> "Ringing"
    // Early media is audible - an announcement or a network ringback is already
    // playing - so it says something different from "ringing", which it is not.
    CallPhase.EARLY_MEDIA -> "Connecting"
    CallPhase.INCOMING -> "Incoming call"
    CallPhase.CONNECTED -> "Connected"
    CallPhase.ON_HOLD -> "On hold"
    CallPhase.HELD_BY_REMOTE -> "On hold by the other party"
    CallPhase.HELD_BY_BOTH -> "On hold by both"
    CallPhase.RESUMING -> "Resuming"
    CallPhase.TRANSFERRING -> "Transferring"
    CallPhase.ENDED -> "Call ended"
}

/** `m:ss`, or `h:mm:ss` past the hour. Long calls happen; a 75-minute call is not 75:00. */
internal fun formatDuration(seconds: Long): String {
    val hours = seconds / SECONDS_PER_HOUR
    val minutes = (seconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val remainder = seconds % SECONDS_PER_MINUTE

    return if (hours > 0) {
        "$hours:${minutes.padded()}:${remainder.padded()}"
    } else {
        "$minutes:${remainder.padded()}"
    }
}

private fun Long.padded(): String = toString().padStart(2, '0')

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

internal const val TAG_TITLE = "call-title"
internal const val TAG_STATUS = "call-status"
internal const val TAG_ANSWER = "call-answer"
internal const val TAG_ANSWER_VIDEO = "call-answer-video"
internal const val TAG_DECLINE = "call-decline"
internal const val TAG_HANG_UP = "call-hang-up"
internal const val TAG_MUTE = "call-mute"
internal const val TAG_SPEAKER = "call-speaker"
internal const val TAG_HOLD = "call-hold"
internal const val TAG_KEYPAD_TOGGLE = "call-keypad-toggle"
internal const val TAG_CONNECTING = "call-connecting"
internal const val TAG_ENDED = "call-ended"

// ---------------------------------------------------------------- previews
//
// One per phase, because Task 39 asks for every FSM state to render a correct, previewable
// screen - and a preview per phase is the only way to see that without a SIP server.

@ThemePreviews
@Composable
private fun IncomingCallPreview() = PreviewSurface {
    CallScreen(
        state = CallUiState.Active(previewCall(CallPhase.INCOMING, direction = CallDirection.INCOMING)),
        snackbarHostState = remember { SnackbarHostState() },
        actions = CallActions(),
    )
}

@ThemePreviews
@Composable
private fun OutgoingRingingPreview() = PreviewSurface {
    CallScreen(
        state = CallUiState.Active(previewCall(CallPhase.RINGING)),
        snackbarHostState = remember { SnackbarHostState() },
        actions = CallActions(),
    )
}

@ThemePreviews
@Composable
private fun ConnectedCallPreview() = PreviewSurface {
    CallScreen(
        state = CallUiState.Active(
            previewCall(CallPhase.CONNECTED, durationSeconds = PREVIEW_DURATION_SECONDS),
        ),
        snackbarHostState = remember { SnackbarHostState() },
        actions = CallActions(),
    )
}

@ThemePreviews
@Composable
private fun HeldCallPreview() = PreviewSurface {
    CallScreen(
        state = CallUiState.Active(previewCall(CallPhase.ON_HOLD)),
        snackbarHostState = remember { SnackbarHostState() },
        actions = CallActions(),
    )
}

private const val PREVIEW_DURATION_SECONDS = 125L

private fun previewCall(
    phase: CallPhase,
    direction: CallDirection = CallDirection.OUTGOING,
    durationSeconds: Long? = null,
) = CallDisplay(
    callId = CallId("preview"),
    title = "Carol Danvers",
    subtitle = "sip:1002@sip.example.com",
    direction = direction,
    phase = phase,
    controls = CallControls.DEFAULT,
    durationSeconds = durationSeconds,
    videoOffered = false,
)
