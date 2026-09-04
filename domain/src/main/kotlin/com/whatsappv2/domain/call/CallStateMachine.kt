package com.whatsappv2.domain.call

import com.whatsappv2.domain.model.HangupReason

/**
 * The result of offering an event to the state machine.
 *
 * Sealed rather than a nullable state, so a caller cannot mistake "rejected" for
 * "no change" — those mean very different things when a UI button was just pressed.
 */
sealed interface TransitionResult {

    /** The event was legal; [state] is the new state. */
    data class Moved(val state: CallState) : TransitionResult

    /**
     * The event was not legal in [from]. Not an error to log and forget: it means the
     * UI offered an action the call could not perform, which is a bug worth surfacing
     * in tests rather than swallowing at run time.
     */
    data class Rejected(val from: CallState, val event: CallEvent) : TransitionResult
}

/**
 * The call lifecycle (§4.4), as an explicit finite state machine.
 *
 * Pure and stateless: [transition] is a function of (state, event) with no clock, no
 * I/O and no Android, which is what makes the exhaustive test in `CallStateMachineTest`
 * possible at all.
 *
 * The transition table is split per phase rather than written as one giant `when`.
 * A single expression would be unreadable and would hide exactly the gaps this class
 * exists to prevent.
 */
object CallStateMachine {

    /** The state a call begins in. */
    val initialState: CallState = CallState.Idle

    fun transition(state: CallState, event: CallEvent): TransitionResult {
        // Terminate is legal from any live state, so handling it once here keeps it out
        // of every per-phase table below.
        if (event is CallEvent.Terminate) {
            return if (state.isActive) moved(CallState.Terminated(event.reason)) else reject(state, event)
        }

        // Control events never change phase, only the attributes of an established call.
        if (event.isControlEvent()) {
            return applyControls(state, event)
        }

        return when (state) {
            is CallState.Idle -> fromIdle(state, event)
            is CallState.Outgoing -> fromOutgoing(state, event)
            is CallState.Incoming -> fromIncoming(state, event)
            is CallState.Connected -> fromConnected(state, event)
            is CallState.Held -> fromHeld(state, event)
            is CallState.Resuming -> fromResuming(state, event)
            is CallState.Transferring -> fromTransferring(state, event)
            is CallState.Terminated -> reject(state, event) // absorbing
        }
    }

    // ------------------------------------------------------------------ phases

    private fun fromIdle(state: CallState, event: CallEvent): TransitionResult = when (event) {
        is CallEvent.Dial -> moved(CallState.Outgoing.Calling)
        is CallEvent.IncomingInvite -> moved(CallState.Incoming(event.from))
        else -> reject(state, event)
    }

    private fun fromOutgoing(state: CallState.Outgoing, event: CallEvent): TransitionResult = when (event) {
        // 180 may arrive after 183, and some networks send it twice; both are harmless.
        is CallEvent.RemoteRinging -> moved(CallState.Outgoing.Ringing)
        is CallEvent.RemoteEarlyMedia -> moved(CallState.Outgoing.EarlyMedia)
        is CallEvent.RemoteAnswered -> moved(CallState.Connected())
        else -> reject(state, event)
    }

    private fun fromIncoming(state: CallState.Incoming, event: CallEvent): TransitionResult = when (event) {
        is CallEvent.LocalAnswered -> moved(CallState.Connected(event.controls))
        else -> reject(state, event)
    }

    private fun fromConnected(state: CallState.Connected, event: CallEvent): TransitionResult = when (event) {
        is CallEvent.LocalHold -> moved(CallState.Held(HoldParty.LOCAL, state.controls))
        is CallEvent.RemoteHold -> moved(CallState.Held(HoldParty.REMOTE, state.controls))
        is CallEvent.StartTransfer -> moved(CallState.Transferring(event.type, state.controls))
        else -> reject(state, event)
    }

    private fun fromHeld(state: CallState.Held, event: CallEvent): TransitionResult = when (event) {
        // Holding again from the other side is legal and produces BOTH.
        is CallEvent.LocalHold -> holdOrReject(state, state.by.withLocal(), event)
        is CallEvent.RemoteHold -> holdOrReject(state, state.by.withRemote(), event)

        // Resuming only reaches Connected when nobody is left holding.
        is CallEvent.LocalResume -> when (state.by.withoutLocal()) {
            null -> moved(CallState.Resuming(state.controls))
            else -> moved(CallState.Held(HoldParty.REMOTE, state.controls))
        }
        is CallEvent.RemoteResume -> when (val remaining = state.by.withoutRemote()) {
            null -> moved(CallState.Connected(state.controls))
            else -> moved(CallState.Held(remaining, state.controls))
        }
        else -> reject(state, event)
    }

    /** Holding from a side that already holds is a no-op, not a transition. */
    private fun holdOrReject(
        state: CallState.Held,
        next: HoldParty,
        event: CallEvent,
    ): TransitionResult =
        if (next == state.by) reject(state, event) else moved(CallState.Held(next, state.controls))

    private fun fromResuming(state: CallState.Resuming, event: CallEvent): TransitionResult = when (event) {
        is CallEvent.ResumeConfirmed -> moved(CallState.Connected(state.controls))
        // The far end can hold us while our own resume is still in flight.
        is CallEvent.RemoteHold -> moved(CallState.Held(HoldParty.REMOTE, state.controls))
        else -> reject(state, event)
    }

    private fun fromTransferring(state: CallState.Transferring, event: CallEvent): TransitionResult = when (event) {
        // The transferee took the call; this leg is released locally, not by the peer.
        is CallEvent.TransferSucceeded -> moved(CallState.Terminated(HangupReason.LOCAL_HANGUP))
        // A failed transfer must return the call, not strand it (§5.2).
        is CallEvent.TransferFailed -> moved(CallState.Connected(state.controls))
        else -> reject(state, event)
    }

    // ------------------------------------------------------------------ controls

    private fun CallEvent.isControlEvent(): Boolean = this is CallEvent.SetMuted ||
        this is CallEvent.SetAudioRoute ||
        this is CallEvent.SetVideoEnabled ||
        this is CallEvent.SetRecording

    /**
     * Applies a control event without changing phase.
     *
     * Rejected before media exists: muting a call that is still ringing is a UI bug,
     * and silently accepting it would hide the moment the real microphone was never
     * actually muted.
     */
    private fun applyControls(state: CallState, event: CallEvent): TransitionResult {
        val controls = state.controlsOrNull ?: return reject(state, event)
        val updated = controls.applying(event) ?: return reject(state, event)

        return moved(
            when (state) {
                is CallState.Connected -> state.copy(controls = updated)
                is CallState.Held -> state.copy(controls = updated)
                is CallState.Resuming -> state.copy(controls = updated)
                is CallState.Transferring -> state.copy(controls = updated)
                else -> return reject(state, event)
            },
        )
    }

    private fun CallControls.applying(event: CallEvent): CallControls? = when (event) {
        is CallEvent.SetMuted -> copy(isMuted = event.muted)
        is CallEvent.SetAudioRoute -> copy(audioRoute = event.route)
        is CallEvent.SetVideoEnabled -> copy(isVideoEnabled = event.enabled)
        is CallEvent.SetRecording -> copy(isRecording = event.recording)
        else -> null
    }

    private fun moved(state: CallState): TransitionResult = TransitionResult.Moved(state)

    private fun reject(state: CallState, event: CallEvent): TransitionResult =
        TransitionResult.Rejected(state, event)
}
