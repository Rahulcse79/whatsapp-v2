package com.whatsappv2.feature.calls

import com.whatsappv2.domain.call.CallControls
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.HoldParty
import com.whatsappv2.domain.contacts.Contact
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.model.CallId

/**
 * The phase of a call, as the screen renders it (Task 39).
 *
 * A projection of [CallState], not a copy: the FSM's states carry data the screen does not
 * need — controls, hold party, hangup reason — and the screen needs a single value it can
 * `when` over to choose a label and a set of buttons. Deriving it in one place is what
 * stops two composables disagreeing about what "ringing" looks like.
 */
enum class CallPhase {
    /** INVITE sent, nothing back yet. */
    CALLING,

    /** 180 — the far end is alerting. */
    RINGING,

    /** 183 with SDP: audio is already arriving, so this is not "still ringing". */
    EARLY_MEDIA,

    /** An inbound call the user has not answered. */
    INCOMING,

    /** Media is flowing. */
    CONNECTED,

    /** The local user is holding. */
    ON_HOLD,

    /** The far end is holding us. Shown differently, because the remedy is different. */
    HELD_BY_REMOTE,

    /** Both ends are holding. Resuming locally does not resume the call. */
    HELD_BY_BOTH,

    /** A resume is in flight. */
    RESUMING,

    /** A transfer is in flight. */
    TRANSFERRING,

    /** Over. */
    ENDED,
    ;

    companion object {
        fun of(state: CallState): CallPhase = when (state) {
            is CallState.Idle -> ENDED
            is CallState.Outgoing.Calling -> CALLING
            is CallState.Outgoing.Ringing -> RINGING
            is CallState.Outgoing.EarlyMedia -> EARLY_MEDIA
            is CallState.Incoming -> INCOMING
            is CallState.Connected -> CONNECTED
            is CallState.Held -> when (state.by) {
                HoldParty.LOCAL -> ON_HOLD
                HoldParty.REMOTE -> HELD_BY_REMOTE
                HoldParty.BOTH -> HELD_BY_BOTH
            }
            is CallState.Resuming -> RESUMING
            is CallState.Transferring -> TRANSFERRING
            is CallState.Terminated -> ENDED
        }
    }
}

/**
 * Which actions the call permits **right now**.
 *
 * Task 39's second done-when, as a value: hold is unavailable before `Connected`, and the
 * reason it is unavailable is the state itself rather than a flag somebody remembered to
 * set. Every button on the call screen reads its `enabled` from here, so a button cannot
 * be offered for an action the state machine would reject.
 */
data class CallControlAvailability(
    val canAnswer: Boolean,
    val canReject: Boolean,
    val canHangUp: Boolean,
    val canMute: Boolean,
    val canChangeRoute: Boolean,
    val canHold: Boolean,
    val canResume: Boolean,
    val canSendDtmf: Boolean,

    /** REFER needs an established dialog, and a transfer already in flight cannot start another. */
    val canTransfer: Boolean,

    /** Adding or dropping video is a re-INVITE, so it needs the same dialog hold does. */
    val canToggleVideo: Boolean,

    /** Only while this call is actually sending video — there is nothing else to switch. */
    val canSwitchCamera: Boolean,

    /** Recording needs media to record. Consent is a separate gate and is asked for later. */
    val canRecord: Boolean,
) {
    companion object {
        fun of(phase: CallPhase): CallControlAvailability = CallControlAvailability(
            canAnswer = phase == CallPhase.INCOMING,
            canReject = phase == CallPhase.INCOMING,
            // Everything except an inbound call that has not been answered - that one is
            // rejected rather than hung up, and the two send different responses.
            canHangUp = phase != CallPhase.ENDED && phase != CallPhase.INCOMING,
            // Mute and routing need media, which exists from the moment the call is
            // answered and not before. Muting a ringing call mutes nothing.
            canMute = phase.hasMedia,
            canChangeRoute = phase.hasMedia,
            // Hold is a re-INVITE on an established dialog. Before Connected there is no
            // dialog to re-INVITE, which is why this is a state question and not a flag.
            canHold = phase == CallPhase.CONNECTED,
            canResume = phase == CallPhase.ON_HOLD || phase == CallPhase.HELD_BY_BOTH,
            // Connected only, and not merely established: RFC 4733 digits ride the RTP
            // stream, and a held call's stream is paused, so a keypad offered there would
            // send tones into a media path that is not running (Task 43).
            canSendDtmf = phase == CallPhase.CONNECTED,
            // Connected only. A REFER sent from a held call is legal SIP and a bad idea:
            // the transferee is handed a call whose audio is paused, and whether it comes
            // back depends on the far end (Task 55).
            canTransfer = phase == CallPhase.CONNECTED,
            // A re-INVITE, so the same rule as hold — and not while one is already in
            // flight, which is what excludes RESUMING and TRANSFERRING (Tasks 53, 54).
            canToggleVideo = phase == CallPhase.CONNECTED,
            // Set by the caller from the call's own video state; the phase alone cannot
            // say whether there is a camera running to switch (Task 53).
            canSwitchCamera = false,
            canRecord = phase.hasMedia,
        )

        /**
         * The same, refined by what this particular call is doing (Tasks 53, 58).
         *
         * [of] answers from the phase alone, which is all most buttons need. Two of them
         * need more: switching cameras is meaningless unless video is actually running,
         * and offering it on an audio call is a control that cannot work.
         */
        fun of(phase: CallPhase, videoEnabled: Boolean): CallControlAvailability =
            of(phase).copy(canSwitchCamera = phase.hasMedia && videoEnabled)
    }
}

/** True once media has been negotiated, which is what mute and routing act on. */
val CallPhase.hasMedia: Boolean
    get() = this == CallPhase.CONNECTED ||
        this == CallPhase.ON_HOLD ||
        this == CallPhase.HELD_BY_REMOTE ||
        this == CallPhase.HELD_BY_BOTH ||
        this == CallPhase.RESUMING ||
        this == CallPhase.TRANSFERRING

/**
 * One call, ready to render.
 *
 * Presentation-resolved in the ViewModel so a Compose preview can build one without an
 * engine, and so the screen holds no logic worth testing separately from the state that
 * produced it.
 */
data class CallDisplay(
    val callId: CallId,

    /** The caller's name if they asserted one, else their address. */
    val title: String,

    /** The address, shown under the name. Null when the title is already the address. */
    val subtitle: String?,

    val direction: CallDirection,
    val phase: CallPhase,
    val controls: CallControls,

    /**
     * Seconds since the call connected, or null before it did.
     *
     * A duration rather than a start time, because the screen shows a duration — and
     * computed from the call's connect timestamp on every tick rather than incremented,
     * so a rotation, a process pause, or a dropped tick cannot make it drift (Task 39).
     */
    val durationSeconds: Long?,

    /**
     * The caller's photo from the address book, or null (Task 49).
     *
     * A `content://` reference rather than an image: the screen loads it when it draws,
     * and nothing here makes a copy of somebody's photograph.
     */
    val photoUri: String? = null,

    /** True when the peer offered video, so an inbound call can be answered with it. */
    val videoOffered: Boolean,

    /**
     * True when a video stream is negotiated on this call right now (Tasks 52, 54).
     *
     * Distinct from [videoOffered], which is about the inbound INVITE, and from
     * `controls.isVideoEnabled`, which is whether the user is *sending*. All three differ
     * on a video call whose camera the user has muted, and the screen renders differently
     * for each: a remote picture with no local preview.
     */
    val videoActive: Boolean = false,

    /** True while this call is a conference leg, so the roster is worth showing (Task 60). */
    val isConference: Boolean = false,
) {
    val availability: CallControlAvailability
        get() = CallControlAvailability.of(phase, controls.isVideoEnabled)

    /** True when there is a remote picture to draw — the far end is sending (Task 52). */
    val showsRemoteVideo: Boolean get() = videoActive && phase.hasMedia

    /** True when the local preview should be on screen: we have a camera running. */
    val showsLocalPreview: Boolean get() = showsRemoteVideo && controls.isVideoEnabled
}

/** What the call screen is showing. */
sealed interface CallUiState {

    /**
     * The engine has not published this call yet.
     *
     * Real and brief: the screen can be opened from a notification a frame before the
     * flow arrives. Distinct from [Finished] so a call that is starting is not drawn as
     * one that has ended.
     */
    data object Loading : CallUiState

    /**
     * A call is on screen.
     *
     * Everything beyond [call] arrived with Tasks 52-60 and is defaulted, so the shape a
     * preview or a test builds for an ordinary audio call is unchanged: an audio call has
     * no other calls, no escalation pending, no transfer in flight and no roster.
     */
    data class Active(
        val call: CallDisplay,

        /**
         * Every other call this app is holding (Task 56).
         *
         * Held calls, almost always exactly one. Present so the screen can show who is
         * waiting and offer a swap, which is the only way "exactly one active" is visible
         * to the person it protects.
         */
        val otherCalls: List<CallDisplay> = emptyList(),

        /** An escalation the far end asked for, still unanswered (Task 54). */
        val pendingVideoRequest: PendingVideoRequest? = null,

        /** A second call arriving right now, with its three answers (Task 56). */
        val secondCall: SecondCallPrompt? = null,

        val transfer: TransferUiState = TransferUiState.Idle,

        val recording: RecordingUiState = RecordingUiState(),

        /** The roster, when this call is a conference leg (Task 60). */
        val conference: ConferenceUiState? = null,
    ) : CallUiState

    /**
     * The call is over and the screen should close.
     *
     * Not an error state: this is the normal end of every call, and the screen's whole
     * lifetime is the call's.
     */
    data object Finished : CallUiState
}

/** A one-shot thing the screen must react to, as opposed to state it renders. */
sealed interface CallEvent {

    /** An action the engine refused, with the reason worth showing. */
    data class ActionFailed(val action: CallAction, val detail: String) : CallEvent
}

/** The actions the screen offers, named so a failure can say which one failed. */
enum class CallAction {
    ANSWER,
    REJECT,
    HANG_UP,
    MUTE,
    SPEAKER,
    HOLD,
    DTMF,
    VIDEO,
    SWITCH_CAMERA,
    TRANSFER,
    SWAP,
    RECORD,
}

/**
 * Builds the display model for [snapshot] at [nowEpochMillis].
 *
 * [contact] is what the address book said, when it knew and when it was allowed to be
 * asked. It wins over the name the peer asserted: the user's own word for a person is
 * more trustworthy than whatever the far end chose to call itself, and it is the name
 * they will recognise. Null for a stranger, and for a device where READ_CONTACTS was
 * declined — both of which leave the screen exactly as it was before Task 49.
 */
internal fun CallSnapshot.toDisplay(nowEpochMillis: Long, contact: Contact? = null): CallDisplay {
    val name = contact?.displayName?.takeIf { it.isNotBlank() }
        ?: remoteDisplayName?.takeIf { it.isNotBlank() }
    val address = remote.render()

    return CallDisplay(
        callId = callId,
        title = name ?: remote.user ?: remote.host.rendered,
        // Only when it adds something: repeating the address under itself is noise.
        subtitle = address.takeIf { name != null },
        photoUri = contact?.photoUri,
        direction = direction,
        phase = CallPhase.of(state),
        controls = state.controlsOrNull ?: CallControls.DEFAULT,
        durationSeconds = durationMillis(nowEpochMillis)?.let { it / MILLIS_PER_SECOND },
        videoOffered = media.hasVideo,
        videoActive = media.hasVideo,
        isConference = isConference,
    )
}

private const val MILLIS_PER_SECOND = 1_000L
