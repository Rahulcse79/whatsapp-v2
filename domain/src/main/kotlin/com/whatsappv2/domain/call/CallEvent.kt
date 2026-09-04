package com.whatsappv2.domain.call

import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.TransferType

/**
 * Everything that can happen to a call.
 *
 * Split into signalling events, which change the phase, and control events, which only
 * change [CallControls]. Keeping both in one sealed hierarchy means the state machine
 * is the single place that decides whether an action is allowed right now — a UI button
 * cannot mute a ringing call by forgetting to check.
 */
sealed interface CallEvent {

    // ---------------------------------------------------------------- outgoing

    /** The local user placed a call. */
    data class Dial(val to: SipUri) : CallEvent

    /** 180 Ringing from the far end. */
    data object RemoteRinging : CallEvent

    /** 183 Session Progress carrying SDP. */
    data object RemoteEarlyMedia : CallEvent

    /** 200 OK — the far end answered. */
    data object RemoteAnswered : CallEvent

    // ---------------------------------------------------------------- incoming

    /** An inbound INVITE arrived. */
    data class IncomingInvite(val from: SipUri) : CallEvent

    /** The local user answered. */
    data class LocalAnswered(val controls: CallControls = CallControls.DEFAULT) : CallEvent

    // ---------------------------------------------------------------- hold

    /** The local user pressed hold. */
    data object LocalHold : CallEvent

    /** A re-INVITE from the far end put us on hold. */
    data object RemoteHold : CallEvent

    /** The local user pressed resume; a re-INVITE is now in flight. */
    data object LocalResume : CallEvent

    /** The resume re-INVITE was answered. */
    data object ResumeConfirmed : CallEvent

    /** The far end resumed. */
    data object RemoteResume : CallEvent

    // ---------------------------------------------------------------- transfer

    /** A REFER was sent. */
    data class StartTransfer(val type: TransferType) : CallEvent

    /** The transferee accepted; this leg can now be released. */
    data object TransferSucceeded : CallEvent

    /** The transfer failed. The original call must survive. */
    data object TransferFailed : CallEvent

    // ---------------------------------------------------------------- controls

    /** Toggle the microphone. Allowed only once media exists. */
    data class SetMuted(val muted: Boolean) : CallEvent

    /** Change where audio plays. */
    data class SetAudioRoute(val route: AudioRoute) : CallEvent

    /** Toggle the outbound video stream. */
    data class SetVideoEnabled(val enabled: Boolean) : CallEvent

    /** Start or stop recording. Gated by consent elsewhere (§2.6). */
    data class SetRecording(val recording: Boolean) : CallEvent

    // ---------------------------------------------------------------- termination

    /** The call ended, for any reason. Legal from any active state. */
    data class Terminate(val reason: HangupReason) : CallEvent
}
