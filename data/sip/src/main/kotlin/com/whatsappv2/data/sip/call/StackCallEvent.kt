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

    /**
     * The far end sent a re-INVITE and the stack is holding it for an answer (Task 54).
     *
     * Its own state because it is the one moment an escalation can be declined. Treated
     * as `StreamsRunning` — which is what a version that ignored it would do — the stack
     * answers with the core's defaults and the camera is on before anybody was asked.
     */
    UPDATED_BY_REMOTE,

    /**
     * A REFER arrived and this call is being transferred away by the far end.
     *
     * Reported so the call log records a transfer rather than a bare hangup; the leg
     * itself ends immediately afterwards.
     */
    REFERRED,

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

    /**
     * True when a video stream is negotiated and running **right now**.
     *
     * Distinct from [videoOffered], which is only about the inbound INVITE. This is read
     * from the call's own params on every event, so a re-INVITE that adds or drops video
     * mid-call reaches the snapshot's `MediaProfile` (Task 54) rather than leaving it
     * describing what was negotiated at the start.
     */
    val videoActive: Boolean = false,
)

/**
 * A transfer's progress, as the stack reports it (Task 55).
 *
 * [callKey] is the call being transferred — the transferor's leg — so a consumer never
 * has to work out which of two calls an event belongs to.
 *
 * [state] is deliberately the stack's own transfer state rather than a bespoke enum: the
 * REFER lifecycle really does run through the same states a call does (`OutgoingProgress`
 * while the transferee is being tried, `Connected` when they answer, `Error` when they do
 * not), and renaming them here would gain a vocabulary and lose the mapping.
 */
internal data class StackTransferEvent(
    val callKey: String,
    val state: StackCallState,

    /** The code from the NOTIFY sipfrag, when there was one. */
    val statusCode: Int?,
)

/**
 * One participant of a conference, as the bridge describes them (Task 60).
 *
 * Flat and primitive on purpose: this is what crossed the SDK boundary, not the domain
 * model. `ConferenceSession` is assembled from these above, where it can be tested.
 */
internal data class StackParticipant(
    val id: String,
    val uri: String?,
    val displayName: String?,
    val isMuted: Boolean,
    val isSpeaking: Boolean,
    val isSelf: Boolean,
    val hasVideoStream: Boolean,
    val joinedAtEpochMillis: Long?,
)

/**
 * The roster of one conference, restated in full (Task 60).
 *
 * Full state rather than a delta. The bridge's own notifications are a mix of both, and
 * reconciling deltas against a roster that may have been missed is how a participant list
 * ends up showing somebody who left ten minutes ago. Restating it is cheap — a conference
 * has tens of participants, not thousands — and it cannot drift.
 *
 * [rosterAvailable] false says the bridge publishes nothing at all, which the UI must say
 * out loud rather than rendering as an empty room (§13, Task 60).
 */
internal data class StackConferenceEvent(
    val callKey: String,
    val participants: List<StackParticipant>,
    val rosterAvailable: Boolean,
)
