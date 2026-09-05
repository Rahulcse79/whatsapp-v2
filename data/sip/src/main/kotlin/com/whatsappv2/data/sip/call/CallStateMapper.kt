package com.whatsappv2.data.sip.call

import com.whatsappv2.domain.call.CallEvent
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.HoldParty
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.TransportFailureKind
import com.whatsappv2.domain.engine.toHangupReason
import com.whatsappv2.domain.model.HangupReason

/**
 * Turns a stack call event into something the FSM understands.
 *
 * Pure, and the only place the translation happens — the same arrangement as
 * `RegistrationStateMapper`, and for the same reason: liblinphone does not run on the JVM,
 * so a mapping that lived inside the gateway could only be exercised on a device, which in
 * practice means not exercised.
 *
 * The subtleties, gathered here rather than scattered through the engine:
 *
 * - **Early media is not ringing.** A 183 with SDP means the network is already sending
 *   audio — an announcement, a ringback, a "the number you dialled" recording. Reporting
 *   it as `Ringing` leaves the app playing its own local ringback over the top, and the
 *   caller hears two things at once.
 * - **`Connected` and `StreamsRunning` are both answered.** The stack reports them
 *   separately because media may lag the 200 OK, but the FSM has one `RemoteAnswered`, and
 *   emitting it twice would be a second transition out of a state that has already moved.
 *   `StreamsRunning` therefore maps to nothing once connected.
 * - **An error without a status code is a transport fault,** not a rejection. The
 *   difference is "the far end said no" versus "nothing reached the far end", and only one
 *   of them is worth showing the user a SIP reason for.
 * - **Who answered depends on which way the call was going.** The stack reports one
 *   `Connected` either way; the FSM has two events, because `LocalAnswered` and
 *   `RemoteAnswered` are legal from different states and mean different things in the call
 *   log. That is why [toCallEvent] takes a direction rather than guessing.
 * - **`StreamsRunning` means whatever the call was doing before it.** Media running again
 *   after our own resume is `ResumeConfirmed`; after the far end's, `RemoteResume`; on a
 *   call that was simply connected, nothing at all. One stack state, three meanings — so
 *   the mapper is given the current state rather than inventing one (Task 41).
 * - **A hold already in force is not a new hold.** The stack repeats its paused state
 *   after a re-negotiation, and the FSM rejects a hold from a side that already holds. A
 *   repeat therefore maps to nothing, so a normal re-negotiation does not fill the log
 *   with rejected transitions that look like defects.
 */
internal object CallStateMapper {

    /**
     * The FSM event for [event], or null when the state carries no transition.
     *
     * Null rather than a no-op event: the state machine rejects transitions that are not
     * legal from the current state, so feeding it something meaningless would either throw
     * or force the caller to know which events are safe to ignore.
     */
    fun toCallEvent(
        event: StackCallEvent,
        state: CallState = CallState.Idle,
        direction: CallDirection = CallDirection.OUTGOING,
    ): CallEvent? = when (event.state) {
        // The INVITE exists but nothing has come back. The call is already in
        // Outgoing.Calling from the moment it was placed, so there is nothing to say.
        StackCallState.OUTGOING_INIT, StackCallState.OUTGOING_PROGRESS -> null

        // The engine creates the call in CallState.Incoming when this arrives, exactly as
        // it creates an outgoing one in Outgoing.Calling. Neither is a transition: there
        // is no prior state to move out of.
        StackCallState.INCOMING_RECEIVED -> null

        StackCallState.OUTGOING_RINGING -> CallEvent.RemoteRinging
        StackCallState.OUTGOING_EARLY_MEDIA -> CallEvent.RemoteEarlyMedia

        // The same stack state, two FSM events. An inbound call reaches Connected because
        // *we* accepted it, and CallState.Incoming only accepts LocalAnswered.
        StackCallState.CONNECTED -> when (direction) {
            CallDirection.OUTGOING -> CallEvent.RemoteAnswered
            CallDirection.INCOMING -> CallEvent.LocalAnswered()
        }

        // Media running again means different things depending on what the call was
        // doing; see [resumeEventFor]. On a call that was already connected it means
        // nothing, because the answer has already been reported.
        StackCallState.STREAMS_RUNNING -> resumeEventFor(state)

        // Ours to resume, and theirs. Two states, because the FSM has two and only one of
        // them is lifted by our own resume.
        StackCallState.PAUSED -> holdEventFor(state, HoldParty.LOCAL)
        StackCallState.PAUSED_BY_REMOTE -> holdEventFor(state, HoldParty.REMOTE)

        // The re-INVITE is on the wire. Reported so the screen can say "resuming" rather
        // than showing a held call that appears to have ignored the button.
        StackCallState.RESUMING -> resumeStartedEventFor(state)

        StackCallState.ENDED, StackCallState.ERROR ->
            CallEvent.Terminate(toHangupReason(event))
    }

    /**
     * A hold by [by], unless that side is already holding.
     *
     * The stack repeats a paused state after any re-negotiation, and the FSM rejects a
     * hold from a side that already holds — correctly, because accepting it would report
     * success for an action that changed nothing. Returning null here keeps a repeat as
     * the no-op it is, instead of a rejection in the log that reads like a bug.
     */
    private fun holdEventFor(state: CallState, by: HoldParty): CallEvent? {
        val holder = (state as? CallState.Held)?.by
        if (holder == by || holder == HoldParty.BOTH) return null
        return if (by == HoldParty.LOCAL) CallEvent.LocalHold else CallEvent.RemoteHold
    }

    /**
     * Our resume re-INVITE going out, but only from a hold that is ours to lift.
     *
     * A call the far end alone is holding cannot be resumed from this side, and the FSM
     * says so by rejecting `LocalResume` there. The stack should never report `Resuming`
     * for one; if it does, nothing is reported rather than an event that would be refused.
     */
    private fun resumeStartedEventFor(state: CallState): CallEvent? =
        if (state is CallState.Held && state.by != HoldParty.REMOTE) CallEvent.LocalResume else null

    /**
     * What media running again means, given where the call was.
     *
     * `Resuming` is our own resume completing. A call the far end was holding is the far
     * end resuming it. Anything else — a call that was simply connected, or one whose
     * media re-negotiated without a hold — has nothing to report, and inventing an event
     * there would be a second transition out of a state that never moved.
     */
    private fun resumeEventFor(state: CallState): CallEvent? = when {
        state is CallState.Resuming -> CallEvent.ResumeConfirmed
        state is CallState.Held && state.by != HoldParty.LOCAL -> CallEvent.RemoteResume
        else -> null
    }

    /**
     * Why the call ended.
     *
     * A clean BYE carries no status code and is a normal remote hangup. Anything with a
     * code goes through the single [SipError] taxonomy so 486, 404 and 408 stay distinct
     * all the way to the screen — Task 35's third done-when.
     */
    fun toHangupReason(event: StackCallEvent): HangupReason {
        val code = event.statusCode
        return when {
            event.state == StackCallState.ENDED && code == null -> HangupReason.REMOTE_HANGUP
            code != null && code > 0 -> SipError.fromResponseCode(code).toHangupReason()
            else -> HangupReason.NETWORK_FAILURE
        }
    }

    /**
     * The error behind a failed call, for a caller that wants to say why.
     *
     * Distinct from [toHangupReason]: the reason is what the call log records, and the
     * error is what the user is shown. A 486 is `BUSY` in the log and "busy" on screen,
     * but a 404 is a terminated call in the log and "that address does not exist" on
     * screen, which is a different sentence.
     */
    fun toSipError(event: StackCallEvent): SipError {
        val code = event.statusCode
        return if (code != null && code > 0) {
            SipError.fromResponseCode(code)
        } else {
            SipError.TransportFailure(TransportFailureKind.CONNECTION_LOST)
        }
    }

    /** True when this event announces a call nothing has seen before. */
    fun isNewIncoming(state: StackCallState): Boolean = state == StackCallState.INCOMING_RECEIVED

    /** True when the call is over and the engine should stop tracking it. */
    fun isTerminal(state: StackCallState): Boolean =
        state == StackCallState.ENDED || state == StackCallState.ERROR

    /** True once media is flowing, which is when a call's duration starts counting. */
    fun isConnected(state: StackCallState): Boolean =
        state == StackCallState.CONNECTED || state == StackCallState.STREAMS_RUNNING
}
