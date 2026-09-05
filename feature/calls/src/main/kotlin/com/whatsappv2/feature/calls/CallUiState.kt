package com.whatsappv2.feature.calls

import com.whatsappv2.domain.call.CallControls
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.HoldParty
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
        )
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

    /** True when the peer offered video, so an inbound call can be answered with it. */
    val videoOffered: Boolean,
) {
    val availability: CallControlAvailability get() = CallControlAvailability.of(phase)
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

    data class Active(val call: CallDisplay) : CallUiState

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
}

/** Builds the display model for [snapshot] at [nowEpochMillis]. */
internal fun CallSnapshot.toDisplay(nowEpochMillis: Long): CallDisplay {
    val name = remoteDisplayName?.takeIf { it.isNotBlank() }
    val address = remote.render()

    return CallDisplay(
        callId = callId,
        title = name ?: remote.user ?: remote.host.rendered,
        // Only when it adds something: repeating the address under itself is noise.
        subtitle = address.takeIf { name != null },
        direction = direction,
        phase = CallPhase.of(state),
        controls = state.controlsOrNull ?: CallControls.DEFAULT,
        durationSeconds = durationMillis(nowEpochMillis)?.let { it / MILLIS_PER_SECOND },
        videoOffered = media.hasVideo,
    )
}

private const val MILLIS_PER_SECOND = 1_000L
