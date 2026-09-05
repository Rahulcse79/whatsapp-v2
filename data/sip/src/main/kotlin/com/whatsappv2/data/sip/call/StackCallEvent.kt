package com.whatsappv2.data.sip.call

/**
 * A call's progress, as the stack reports it, with no SDK type in the signature.
 *
 * Reduced from liblinphone's twenty-odd `Call.State` values to the ones that mean
 * something different to this application. `Pausing`, `Updating`, `Released` and the rest
 * are collapsed at the SDK boundary rather than carried up: a state nothing branches on is
 * a state every reader has to check does not matter.
 */
internal enum class StackCallState {

    /**
     * An inbound INVITE arrived and has not been answered or rejected.
     *
     * The one state that can name a call this module has never seen before, so it is also
     * where a call key is minted rather than looked up.
     */
    INCOMING_RECEIVED,

    /** The INVITE has been created but not yet sent. */
    OUTGOING_INIT,

    /** The INVITE is on the wire; no provisional response yet. */
    OUTGOING_PROGRESS,

    /** 180 Ringing — the far end is alerting. */
    OUTGOING_RINGING,

    /**
     * 183 Session Progress with SDP: media is flowing before the call is answered.
     *
     * Its own state because it is audible. Treating it as "still ringing" would leave the
     * app playing a local ringback tone over the announcement the network is sending.
     */
    OUTGOING_EARLY_MEDIA,

    /** Answered. Media may not be running yet. */
    CONNECTED,

    /** Answered and media is running in both directions. */
    STREAMS_RUNNING,

    /**
     * We are holding: our re-INVITE was accepted and our media is `sendonly`.
     *
     * Separate from [PAUSED_BY_REMOTE] because the FSM has two different states for them
     * and only one of them is ours to resume. Collapsing the two is how "resume did
     * nothing" bugs happen.
     */
    PAUSED,

    /** The far end is holding us. */
    PAUSED_BY_REMOTE,

    /** Our resume re-INVITE is in flight; media is not running again yet. */
    RESUMING,

    /** Ended normally — BYE sent or received, or CANCEL acknowledged. */
    ENDED,

    /** Ended because of a failure response or a transport fault. */
    ERROR,
}

/**
 * One call-state change.
 *
 * [callKey] is the app's own id rather than the stack's, so the engine never has to hold a
 * mapping between two identifier spaces — the same choice `StackRegistrationEvent` makes
 * with `accountKey`.
 *
 * [statusCode] is the SIP response behind an [StackCallState.ERROR], and it is the whole
 * reason Task 35 can tell 486 Busy from 404 Not Found from 408 Timeout. Null when the call
 * ended without a response — a transport fault rather than a rejection.
 */
internal data class StackCallEvent(
    val callKey: String,
    val accountKey: String,
    val remoteUri: String,
    val remoteDisplayName: String?,
    val state: StackCallState,
    val statusCode: Int?,
    val message: String?,

    /**
     * True when the peer offered video, read from the remote call parameters.
     *
     * Only meaningful on [StackCallState.INCOMING_RECEIVED], which is the moment the
     * offer exists and nothing has answered it: the incoming UI has to know whether to
     * show a video answer button, and it cannot ask the stack itself (Task 37, §5.2).
     */
    val videoOffered: Boolean = false,
)
