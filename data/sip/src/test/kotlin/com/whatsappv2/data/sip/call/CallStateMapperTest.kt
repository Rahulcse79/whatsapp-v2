package com.whatsappv2.data.sip.call

import com.whatsappv2.domain.call.CallEvent
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.HangupReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The call translation, asserted on the JVM (Task 35).
 *
 * liblinphone does not run here, so this mapping could otherwise only be exercised on a
 * device — which in practice means not exercised. Same arrangement as
 * `RegistrationStateMapperTest`, and it exists for the same reason.
 */
class CallStateMapperTest {

    private fun event(
        state: StackCallState,
        statusCode: Int? = null,
    ) = StackCallEvent(
        callKey = "call-1",
        accountKey = "acct-1",
        remoteUri = "sip:bob@sip.example.com",
        remoteDisplayName = null,
        state = state,
        statusCode = statusCode,
        message = null,
    )

    // ---------------------------------------------------------------- progress

    @Test
    fun `the states before a response carry no transition`() {
        // The call is already in Outgoing.Calling from the moment it was placed, so there
        // is nothing for these to say. Null rather than a no-op event: the FSM rejects
        // transitions that are illegal from the current state.
        assertNull(CallStateMapper.toCallEvent(event(StackCallState.OUTGOING_INIT)))
        assertNull(CallStateMapper.toCallEvent(event(StackCallState.OUTGOING_PROGRESS)))
    }

    @Test
    fun `ringing and early media are different events`() {
        // The distinction is audible. A 183 with SDP means the network is already sending
        // audio, and reporting it as ringing leaves a local ringback playing over it.
        assertEquals(
            CallEvent.RemoteRinging,
            CallStateMapper.toCallEvent(event(StackCallState.OUTGOING_RINGING)),
        )
        assertEquals(
            CallEvent.RemoteEarlyMedia,
            CallStateMapper.toCallEvent(event(StackCallState.OUTGOING_EARLY_MEDIA)),
        )
    }

    @Test
    fun `the answer is reported once, not again when media starts`() {
        // The stack reports Connected and StreamsRunning separately because media can lag
        // the 200 OK. The FSM has one RemoteAnswered, and emitting it twice is a second
        // transition out of a state that has already moved.
        assertEquals(
            CallEvent.RemoteAnswered,
            CallStateMapper.toCallEvent(event(StackCallState.CONNECTED)),
        )
        assertNull(CallStateMapper.toCallEvent(event(StackCallState.STREAMS_RUNNING)))
    }

    @Test
    fun `an inbound INVITE carries no transition, because there is nothing to move`() {
        // The engine creates the call in CallState.Incoming when this arrives, exactly as
        // it creates an outgoing one in Outgoing.Calling. Neither is a transition.
        assertNull(CallStateMapper.toCallEvent(event(StackCallState.INCOMING_RECEIVED)))
        assertTrue(CallStateMapper.isNewIncoming(StackCallState.INCOMING_RECEIVED))
        assertTrue(!CallStateMapper.isNewIncoming(StackCallState.CONNECTED))
    }

    @Test
    fun `who answered depends on which way the call was going`() {
        // One stack state, two FSM events: CallState.Incoming accepts only LocalAnswered,
        // so a direction-blind mapping would leave an answered call showing as ringing.
        assertEquals(
            CallEvent.RemoteAnswered,
            CallStateMapper.toCallEvent(event(StackCallState.CONNECTED), CallDirection.OUTGOING),
        )
        assertEquals(
            CallEvent.LocalAnswered(),
            CallStateMapper.toCallEvent(event(StackCallState.CONNECTED), CallDirection.INCOMING),
        )
    }

    // ---------------------------------------------------------------- termination

    @Test
    fun `a clean BYE is a remote hangup, not an error`() {
        val terminate = assertIs<CallEvent.Terminate>(
            CallStateMapper.toCallEvent(event(StackCallState.ENDED)),
        )
        assertEquals(HangupReason.REMOTE_HANGUP, terminate.reason)
    }

    @Test
    fun `486, 404 and 408 stay distinct all the way to the reason`() {
        // Task 35's third done-when. One "call failed" for all three tells the user
        // nothing about whether to redial, check the address, or check their network.
        assertEquals(HangupReason.BUSY, reasonFor(BUSY_HERE))
        assertEquals(HangupReason.SERVER_ERROR, reasonFor(NOT_FOUND))
        assertEquals(HangupReason.NO_ANSWER, reasonFor(REQUEST_TIMEOUT))
    }

    @Test
    fun `the same three codes stay distinct as errors, which is what the user reads`() {
        // The reason is what the call log records; the error is what gets shown. They are
        // deliberately different vocabularies - a 404 is a plain terminated call in a log
        // and "that address does not exist" on screen.
        assertIs<SipError.Busy>(CallStateMapper.toSipError(errorEvent(BUSY_HERE)))
        assertIs<SipError.NotFound>(CallStateMapper.toSipError(errorEvent(NOT_FOUND)))
        assertEquals(SipError.Timeout, CallStateMapper.toSipError(errorEvent(REQUEST_TIMEOUT)))
    }

    @Test
    fun `an error with no status code is a transport fault, not a rejection`() {
        // "The far end said no" and "nothing reached the far end" are different problems,
        // and only one of them has a SIP reason worth showing.
        assertIs<SipError.TransportFailure>(CallStateMapper.toSipError(errorEvent(null)))
        assertEquals(HangupReason.NETWORK_FAILURE, reasonFor(null))
    }

    @Test
    fun `a zero status code counts as absent`() {
        // The stack reports 0 rather than null when it never got a response, and treating
        // that as a SIP code would map it through the response-class fallback into a
        // server error that never happened.
        assertIs<SipError.TransportFailure>(CallStateMapper.toSipError(errorEvent(0)))
    }

    // ---------------------------------------------------------------- predicates

    @Test
    fun `terminal states are the two that end a call`() {
        assertTrue(CallStateMapper.isTerminal(StackCallState.ENDED))
        assertTrue(CallStateMapper.isTerminal(StackCallState.ERROR))
        StackCallState.entries
            .filterNot { it == StackCallState.ENDED || it == StackCallState.ERROR }
            .forEach { assertTrue(!CallStateMapper.isTerminal(it), "$it must not end a call") }
    }

    @Test
    fun `connected covers both answered states, because duration starts at either`() {
        assertTrue(CallStateMapper.isConnected(StackCallState.CONNECTED))
        assertTrue(CallStateMapper.isConnected(StackCallState.STREAMS_RUNNING))
        assertTrue(!CallStateMapper.isConnected(StackCallState.OUTGOING_EARLY_MEDIA))
    }

    @Test
    fun `a hold from either end reads as a remote hold`() {
        assertEquals(
            CallEvent.RemoteHold,
            CallStateMapper.toCallEvent(event(StackCallState.PAUSED)),
        )
    }

    private fun reasonFor(code: Int?) =
        CallStateMapper.toHangupReason(errorEvent(code))

    private fun errorEvent(code: Int?) = event(StackCallState.ERROR, statusCode = code)

    private companion object {
        const val BUSY_HERE = 486
        const val NOT_FOUND = 404
        const val REQUEST_TIMEOUT = 408
    }
}
