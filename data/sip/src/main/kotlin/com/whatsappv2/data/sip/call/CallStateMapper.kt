package com.whatsappv2.data.sip.call

import com.whatsappv2.domain.call.CallEvent
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

        // See the class doc: the answer has already been reported.
        StackCallState.STREAMS_RUNNING -> null

        StackCallState.PAUSED -> CallEvent.RemoteHold

        StackCallState.ENDED, StackCallState.ERROR ->
            CallEvent.Terminate(toHangupReason(event))
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
