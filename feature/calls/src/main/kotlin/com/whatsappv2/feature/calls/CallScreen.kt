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

                is CallUiState.Active -> ActiveCall(state = state, actions = actions)
            }
        }
    }
}

@Composable
private fun ActiveCall(state: CallUiState.Active, actions: CallActions) {
    val call = state.call
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

    // Behind everything, when there is a picture to draw. The identity and the controls
    // stay on top of it: a video call still has to say who it is with and offer a way to
    // end it, and putting the video in a panel of its own would waste most of the screen
    // on a call whose whole point is the picture (Task 52).
    // A conference's video is not a call's video: the bridge composes it, and the screen
    // has to say so rather than present the server's arrangement as its own (Task 61).
    val conference = state.conference
    if (call.showsRemoteVideo) {
        if (conference != null) {
            ConferenceVideo(call = call, conference = conference, actions = actions)
        } else {
            CallVideo(call = call, actions = actions)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(AppTheme.spacing.large),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CallBanners(state = state, actions = actions)

        Spacer(Modifier.weight(1f))

        // The avatar gives way to the keypad rather than being scrolled off it: on a small
        // screen both do not fit, and the keys are what the caller is trying to reach. It
        // also gives way to video, where the picture is the identity.
        CallIdentity(call = call, showAvatar = !keypadShown && !call.showsRemoteVideo)

        // The roster stays even with video on: under a mixing bridge the composed picture
        // is the only place a participant appears, and it does not say who is muted or
        // who has just left (Task 60).
        conference?.let {
            ConferenceRoster(
                state = it,
                modifier = Modifier.padding(top = AppTheme.spacing.medium),
            )
        }

        Spacer(Modifier.weight(1f))

        CallActionArea(
            state = state,
            actions = actions,
            keypadShown = keypadShown,
            dialled = dialled,
            onDialled = { dialled += it },
            onToggleKeypad = { keypadOpen = it },
        )
    }

    CallDialogs(state = state, actions = actions)
}

/**
 * Either the two answers to an incoming call, or the controls for one in progress.
 *
 * Split out so [ActiveCall] stays a layout: the choice between them is the call's phase,
 * and everything below it is the same set of buttons either way.
 */
@Composable
private fun CallActionArea(
    state: CallUiState.Active,
    actions: CallActions,
    keypadShown: Boolean,
    dialled: String,
    onDialled: (Char) -> Unit,
    onToggleKeypad: (Boolean) -> Unit,
) {
    val call = state.call
    if (call.availability.canAnswer) {
        IncomingActions(call = call, actions = actions)
        return
    }

    if (keypadShown) {
        CallKeypad(
            dialled = dialled,
            onDigit = {
                onDialled(it.symbol)
                actions.onDtmf(it)
            },
            onHide = { onToggleKeypad(false) },
            modifier = Modifier.padding(bottom = AppTheme.spacing.large),
        )
    }
    InCallActions(
        call = call,
        actions = actions,
        keypadOpen = keypadShown,
        onToggleKeypad = { onToggleKeypad(!keypadShown) },
        pending = state.pendingActions,
        secondaryControls = {
            CallSecondaryControls(
                call = call,
                recording = state.recording,
                actions = actions,
                canMerge = state.canMerge,
                mixedCallCount = state.mixedCallCount,
                pending = state.pendingActions,
            )
        },
    )
}

/**
 * What is going on besides this call, above the caller's name.
 *
 * Both are statements rather than controls — one says the call is being recorded, the
 * other that somebody else is on hold — and both sit above the identity because a user
 * reads down from the name.
 */
@Composable
private fun CallBanners(state: CallUiState.Active, actions: CallActions) {
    // Present for the whole recording — Task 58's second done-when is about duration, so
    // this is rendered from what the recorder says it is writing rather than from an event
    // somebody has to remember to send.
    if (state.recording.isRecording) {
        RecordingBanner(modifier = Modifier.padding(bottom = AppTheme.spacing.small))
    }

    // The held call, by name, and tappable. Without it a user has no way to tell a
    // successful hold from a dropped call (Task 56).
    state.otherCalls.firstOrNull()?.let { other ->
        HeldCallBanner(
            other = other,
            onSwap = { actions.onSwapTo(other.callId) },
            modifier = Modifier.padding(bottom = AppTheme.spacing.small),
        )
    }
}

/**
 * Everything that is a question rather than a control.
 *
 * Gathered into one composable because they share a rule: each is a modal awaiting an
 * answer somebody else is blocked on — the far end's re-INVITE (Task 54), a second caller
 * who is ringing (Task 56), a transfer in flight (Task 55). At most one is up at a time in
 * practice, and rendering them together keeps that visible.
 */
@Composable
private fun CallDialogs(state: CallUiState.Active, actions: CallActions) {
    state.pendingVideoRequest?.let { request ->
        VideoRequestPrompt(request = request, onRespond = actions.onRespondToVideoRequest)
    }

    state.secondCall?.let { prompt ->
        SecondCallPromptDialog(
            prompt = prompt,
            onRespond = { response -> actions.onSecondCall(prompt.callId, response) },
        )
    }

    if (state.recording.askingConsent) {
        RecordingConsentPrompt(
            remoteName = state.call.title,
            onConfirm = actions.onConfirmRecording,
            onDismiss = actions.onDismissRecordingConsent,
        )
    }

    CallTransferSheet(state = state.transfer, actions = actions)
}

/**
 * Who the call is with, and what it is doing.
 *
 * The same block in every phase, because a call's identity does not change when its state
 * does — only the line underneath it does, and that line is [CallDisplay.statusLine].
 */
@Composable
private fun CallIdentity(call: CallDisplay, showAvatar: Boolean) {
    if (showAvatar) {
        Avatar(
            displayName = call.title,
            size = AppTheme.sizing.avatarLarge,
            photoUri = call.photoUri,
        )
    }

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
    pending: Set<CallAction>,
    secondaryControls: @Composable () -> Unit,
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
            pending = pending,
        )

        // Passed in rather than called directly so this stays a layout: the second row
        // needs the recording state, which is not a property of the call.
        secondaryControls()

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
 *
 * [pending] is the other half of that honesty (Task 76). A control the engine has not
 * answered yet is busy, not toggled: the icon still shows the state the call is actually
 * in, and the spinner says the press was received.
 */
@Composable
private fun CallControlRow(
    call: CallDisplay,
    actions: CallActions,
    keypadOpen: Boolean,
    onToggleKeypad: () -> Unit,
    pending: Set<CallAction>,
) {
    val controls = call.controls
    val availability = call.availability
    val speakerOn = controls.audioRoute == AudioRoute.SPEAKER
    val held = availability.canResume

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        MuteButton(
            muted = controls.isMuted,
            enabled = availability.canMute,
            pending = CallAction.MUTE in pending,
            onToggle = actions.onToggleMute,
        )
        CallActionButton(
            icon = Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = if (speakerOn) "Turn off speakerphone" else "Turn on speakerphone",
            activeStateDescription = if (speakerOn) "On" else "Off",
            onClick = { actions.onToggleSpeaker(!speakerOn) },
            enabled = availability.canChangeRoute,
            active = speakerOn,
            label = "Speaker",
            pending = CallAction.SPEAKER in pending,
            pendingStateDescription = "Switching audio",
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
            pending = CallAction.HOLD in pending,
            pendingStateDescription = if (held) "Resuming" else "Holding",
            modifier = Modifier.testTag(TAG_HOLD),
        )
        KeypadToggle(
            open = keypadOpen,
            enabled = availability.canSendDtmf,
            onToggle = onToggleKeypad,
        )
    }
}

/**
 * The microphone.
 *
 * The icon shows the state the call is actually in; `pending` shows that a press was
 * received. Never the other way round — see [CallActionButton] and Task 76.
 */
@Composable
private fun MuteButton(muted: Boolean, enabled: Boolean, pending: Boolean, onToggle: (Boolean) -> Unit) {
    CallActionButton(
        icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
        contentDescription = if (muted) "Unmute microphone" else "Mute microphone",
        activeStateDescription = if (muted) "Muted" else "Not muted",
        onClick = { onToggle(!muted) },
        enabled = enabled,
        active = muted,
        label = "Mute",
        pending = pending,
        pendingStateDescription = if (muted) "Unmuting" else "Muting",
        modifier = Modifier.testTag(TAG_MUTE),
    )
}

/**
 * Shows and hides the DTMF keypad.
 *
 * A tone needs a running media path, so the phase decides this exactly as it decides hold
 * — and a held call's keypad is disabled, not hidden, so the control does not move under
 * the user's thumb (Task 43).
 */
@Composable
private fun KeypadToggle(open: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    CallActionButton(
        icon = Icons.Filled.Dialpad,
        contentDescription = if (open) "Hide the keypad" else "Show the keypad",
        activeStateDescription = if (open) "Shown" else "Hidden",
        onClick = onToggle,
        enabled = enabled,
        active = open,
        label = "Keypad",
        modifier = Modifier.testTag(TAG_KEYPAD_TOGGLE),
    )
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
