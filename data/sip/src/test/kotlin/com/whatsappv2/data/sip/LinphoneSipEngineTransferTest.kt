@file:OptIn(ExperimentalCoroutinesApi::class)

package com.whatsappv2.data.sip

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.data.sip.call.StackCallState
import com.whatsappv2.data.sip.call.StackParticipant
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.TransferEvent
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.TransferType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Transfer and dial-in conferencing, in the real engine (Tasks 55, 57, 60).
 *
 * Transfer outcomes are collected into a list rather than read off a state: the stream is
 * unreplayed on purpose — a transfer failure shown twice is a second alarm about a call
 * that was resolved minutes ago — so a test has to be listening when it happens, exactly
 * as the screen is.
 */
class LinphoneSipEngineTransferTest : LinphoneSipEngineFixture() {

    /** Starts collecting [LinphoneSipEngine.transferEvents] before anything can emit. */
    private fun TestScope.collectTransfers(engine: LinphoneSipEngine): List<TransferEvent> {
        val seen = mutableListOf<TransferEvent>()
        backgroundScope.launch { engine.transferEvents.collect { seen += it } }
        runCurrent()
        return seen
    }

    // ================================================================ Task 55

    @Test
    fun `a blind transfer sends a REFER and moves the call to Transferring`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId

        assertIs<Outcome.Success<Unit>>(engine.transfer(callId, TARGET, TransferType.BLIND))

        assertEquals(listOf(callId.value to TARGET.render()), gateway.blindTransfers)
        assertIs<CallState.Transferring>(engine.activeCalls.value.single().state)
    }

    @Test
    fun `transferring a call that is still ringing is refused`() = runTest {
        val engine = registeredEngine()
        val callId = requireNotNull(engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull())
        runCurrent()

        val result = engine.transfer(callId, TARGET, TransferType.BLIND)

        assertIs<SipError.InvalidState>(assertIs<Outcome.Failure<SipError>>(result).error)
        assertTrue(gateway.blindTransfers.isEmpty())
    }

    @Test
    fun `the 202 is reported as accepted, which is not success`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId
        val seen = collectTransfers(engine)
        engine.transfer(callId, TARGET, TransferType.BLIND)

        gateway.emitTransfer(callId.value, StackCallState.OUTGOING_INIT)
        runCurrent()

        assertEquals(listOf(TransferEvent.Accepted(callId, TransferType.BLIND)), seen)
        // Still on the call: nobody has said the transferee answered.
        assertTrue(engine.activeCalls.value.isNotEmpty())
    }

    @Test
    fun `a sipfrag while the transferee rings is reported as progress`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId
        val seen = collectTransfers(engine)
        engine.transfer(callId, TARGET, TransferType.BLIND)

        gateway.emitTransfer(callId.value, StackCallState.OUTGOING_RINGING, statusCode = 180)
        runCurrent()

        assertEquals(TransferEvent.Progressing(callId, 180), seen.single())
    }

    @Test
    fun `the transferee answering releases the leg, and says why it went`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId
        val seen = collectTransfers(engine)
        engine.transfer(callId, TARGET, TransferType.BLIND)

        gateway.emitTransfer(callId.value, StackCallState.CONNECTED)
        runCurrent()

        assertEquals(TransferEvent.Succeeded(callId), seen.single())
        assertTrue(engine.activeCalls.value.isEmpty())
    }

    @Test
    fun `a failed transfer returns the call to connected and names the reason`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId
        val seen = collectTransfers(engine)
        engine.transfer(callId, TARGET, TransferType.BLIND)

        gateway.emitTransfer(callId.value, StackCallState.ERROR, statusCode = BUSY_HERE)
        runCurrent()

        // §5.2, Task 55's third done-when: back to Connected, never a dead end.
        assertIs<CallState.Connected>(engine.activeCalls.value.single().state)
        val failure = assertIs<TransferEvent.Failed>(seen.single())
        assertIs<SipError.Busy>(failure.cause)
    }

    @Test
    fun `a late transfer event for a call that has moved on is ignored`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId

        // No REFER was ever sent, so the call is Connected and TransferFailed is illegal.
        gateway.emitTransfer(callId.value, StackCallState.ERROR, statusCode = BUSY_HERE)
        runCurrent()

        assertIs<CallState.Connected>(engine.activeCalls.value.single().state)
    }

    // ================================================================ Task 57

    @Test
    fun `an attended transfer names the consultation call rather than an address`() = runTest {
        val engine = connectedCall()
        val first = engine.activeCalls.value.single().callId
        val consultation = requireNotNull(engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull())
        runCurrent()

        engine.transfer(first, TARGET, TransferType.ATTENDED, consultationCallId = consultation)

        // `Replaces` identifies a dialog, which an address cannot name.
        assertEquals(listOf(first.value to consultation.value), gateway.attendedTransfers)
        assertTrue(gateway.blindTransfers.isEmpty())
    }

    @Test
    fun `an attended transfer with no consultation call is refused`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId

        val result = engine.transfer(callId, TARGET, TransferType.ATTENDED, consultationCallId = null)

        assertIs<SipError.InvalidState>(assertIs<Outcome.Failure<SipError>>(result).error)
    }

    @Test
    fun `an attended transfer naming a call the engine does not have is refused`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId

        val result = engine.transfer(
            callId,
            TARGET,
            TransferType.ATTENDED,
            consultationCallId = CallId("gone"),
        )

        assertIs<SipError.UnknownCall>(assertIs<Outcome.Failure<SipError>>(result).error)
    }

    // ================================================================ Task 60

    @Test
    fun `joining a conference dials it and records the leg as one`() = runTest {
        val engine = registeredEngine()

        val callId = requireNotNull(engine.joinConference(account.id, TARGET, MediaProfile.AUDIO).getOrNull())

        assertEquals(TARGET.render(), gateway.placedCalls.single().destination)
        assertTrue(engine.activeCalls.value.single().isConference)
        assertEquals(callId, engine.conferences.value.single().callId)
    }

    @Test
    fun `a fresh conference has no roster rather than an empty one`() = runTest {
        val engine = registeredEngine()
        engine.joinConference(account.id, TARGET, MediaProfile.AUDIO)

        val session = engine.conferences.value.single()

        assertEquals(false, session.rosterAvailable)
        // Task 60's third done-when: the UI must be able to say "not known", not "nobody".
        assertNull(session.participantCount)
    }

    @Test
    fun `a roster from the bridge reaches the session`() = runTest {
        val engine = registeredEngine()
        val callId = requireNotNull(engine.joinConference(account.id, TARGET, MediaProfile.AUDIO).getOrNull())

        gateway.emitConference(
            callKey = callId.value,
            participants = listOf(
                participant("sip:me@sip.example.com", self = true),
                participant("sip:bob@sip.example.com"),
            ),
        )
        runCurrent()

        val session = engine.conferences.value.single()
        assertTrue(session.rosterAvailable)
        assertEquals(2, session.participantCount)
        assertEquals(1, session.others.size)
    }

    @Test
    fun `a bridge that publishes nothing leaves the session saying so`() = runTest {
        val engine = registeredEngine()
        val callId = requireNotNull(engine.joinConference(account.id, TARGET, MediaProfile.AUDIO).getOrNull())

        gateway.emitConference(callKey = callId.value, participants = emptyList(), rosterAvailable = false)
        runCurrent()

        assertEquals(false, engine.conferences.value.single().rosterAvailable)
    }

    @Test
    fun `a roster for a call that is not a joined conference is dropped`() = runTest {
        val engine = connectedCall()

        gateway.emitConference(callKey = "not-a-conference", participants = listOf(participant("sip:x@y")))
        runCurrent()

        assertTrue(engine.conferences.value.isEmpty())
    }

    @Test
    fun `hanging up the leg takes the conference with it`() = runTest {
        val engine = registeredEngine()
        val callId = requireNotNull(engine.joinConference(account.id, TARGET, MediaProfile.AUDIO).getOrNull())
        runCurrent()

        gateway.emitCall(callId.value, StackCallState.ENDED)
        runCurrent()

        assertTrue(engine.conferences.value.isEmpty())
    }

    private fun participant(uri: String, self: Boolean = false) = StackParticipant(
        id = uri,
        uri = uri,
        displayName = null,
        isMuted = false,
        isSpeaking = false,
        isSelf = self,
        hasVideoStream = false,
        joinedAtEpochMillis = null,
    )
}
