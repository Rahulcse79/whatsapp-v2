package com.whatsappv2.feature.calls

import androidx.compose.runtime.Stable
import com.whatsappv2.domain.call.SecondCallResponse
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.DtmfDigit

/**
 * What the call screen can do, gathered into one value.
 *
 * Every one of them is the same kind of thing: something the user did. Grouping them keeps
 * the screen's signature readable and means the next control adds a field here rather than
 * another argument threaded through four call sites — which is exactly what Tasks 52-60
 * did. `:feature:dialer` groups its own for the same reason.
 *
 * All defaulted to no-ops so a preview or a UI test supplies only what it exercises.
 */
@Stable
data class CallActions(
    val onAnswer: (Boolean) -> Unit = {},
    val onReject: () -> Unit = {},
    val onHangUp: () -> Unit = {},
    val onToggleMute: (Boolean) -> Unit = {},
    val onToggleSpeaker: (Boolean) -> Unit = {},
    val onToggleHold: (Boolean) -> Unit = {},
    val onDtmf: (DtmfDigit) -> Unit = {},

    // ------------------------------------------------------------------ video
    /** Adds or drops the video stream by re-INVITE (Tasks 53, 54). */
    val onToggleVideo: (Boolean) -> Unit = {},
    val onSwitchCamera: () -> Unit = {},

    /** Accepts or declines an escalation the far end asked for (Task 54). */
    val onRespondToVideoRequest: (Boolean) -> Unit = {},

    /** Hands the stack the views to draw into, and takes them back (Task 52). */
    val onVideoSurfaces: (remote: Any?, preview: Any?) -> Unit = { _, _ -> },
    val onReleaseVideoSurfaces: () -> Unit = {},

    // ------------------------------------------------------------------ transfer
    val onStartTransfer: () -> Unit = {},
    val onTransferTargetChanged: (String) -> Unit = {},
    val onCancelTransfer: () -> Unit = {},
    val onTransferBlind: (String) -> Unit = {},
    val onStartConsultation: (String) -> Unit = {},
    val onCompleteConsultation: () -> Unit = {},
    val onCancelConsultation: () -> Unit = {},

    // ------------------------------------------------------------------ call waiting
    /** One of the three answers to a second call (Task 56). */
    val onSecondCall: (CallId, SecondCallResponse) -> Unit = { _, _ -> },

    /** Makes another call the live one, holding this (Task 56). */
    val onSwapTo: (CallId) -> Unit = {},

    // ------------------------------------------------------------------ recording
    /** Opens the consent dialog. There is no way to start recording that skips it (§2.6). */
    val onRequestRecording: () -> Unit = {},
    val onConfirmRecording: () -> Unit = {},
    val onDismissRecordingConsent: () -> Unit = {},
    val onStopRecording: () -> Unit = {},
)
