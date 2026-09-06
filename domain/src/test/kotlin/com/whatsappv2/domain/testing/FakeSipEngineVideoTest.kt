package com.whatsappv2.domain.testing

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.TransferEvent
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.TransferType
import com.whatsappv2.domain.model.Transport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The escalation and transfer behaviour the fake now has to model (Tasks 54, 55, 57).
 *
 * A fake is only worth having if it refuses what production refuses. These assert the two
 * refusals that matter: an escalation cannot be answered when none is pending, and an
 * attended transfer cannot be started without a consultation call.
 */
class FakeSipEngineVideoTest {

    private val account = SipAccount(
        id = AccountId("acct-1"),
        label = "Work",
        username = "alice",
        extension = null,
        authUsername = null,
        password = Secret("hunter22"),
        displayName = null,
        domain = "sip.example.com",
        registrar = null,
        outboundProxy = null,
        port = null,
        transport = Transport.UDP,
        registrationExpirySeconds = 3_600,
        stunServer = null,
        turn = null,
        natPolicy = NatPolicy.DEFAULT,
        srtpPolicy = SrtpPolicy.OPTIONAL,
        codecs = CodecPreferences.DEFAULT,
        isDefault = true,
    )

    private val bob = requireNotNull(SipUri.parse("sip:bob@example.com").getOrNull())
    private val carol = requireNotNull(SipUri.parse("sip:carol@example.com").getOrNull())

    private suspend fun connectedCall(engine: FakeSipEngine) =
        engine.simulateIncomingCall(account.id, bob).callId
            .also { engine.answer(it, MediaProfile.AUDIO) }

    // ================================================================ Task 54

    @Test
    fun `an escalation waits for an answer and changes nothing on its own`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val callId = connectedCall(engine)

        val request = engine.simulateVideoRequest(callId)

        assertNotNull(request)
        // Nothing negotiated, nothing on. §5.2 requires the prompt to come first.
        assertEquals(MediaProfile.AUDIO, engine.activeCalls.value.single().media)
        assertFalse(engine.activeCalls.value.single().state.controlsOrNull?.isVideoEnabled == true)
    }

    @Test
    fun `accepting an escalation turns video on`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val callId = connectedCall(engine)
        engine.simulateVideoRequest(callId)

        assertIs<Outcome.Success<Unit>>(engine.respondToVideoRequest(callId, accept = true))

        val call = engine.activeCalls.value.single()
        assertEquals(MediaProfile.AUDIO_VIDEO, call.media)
        assertTrue(call.state.controlsOrNull?.isVideoEnabled == true)
    }

    @Test
    fun `declining an escalation keeps the audio call alive`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val callId = connectedCall(engine)
        engine.simulateVideoRequest(callId)

        assertIs<Outcome.Success<Unit>>(engine.respondToVideoRequest(callId, accept = false))

        // Task 54's second done-when: the call is still there, still audio, still connected.
        val call = engine.activeCalls.value.single()
        assertIs<CallState.Connected>(call.state)
        assertEquals(MediaProfile.AUDIO, call.media)
    }

    @Test
    fun `answering an escalation that is not pending is refused`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val callId = connectedCall(engine)

        val result = engine.respondToVideoRequest(callId, accept = true)

        assertIs<SipError.InvalidState>(assertIs<Outcome.Failure<SipError>>(result).error)
    }

    @Test
    fun `an escalation cannot be answered twice`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val callId = connectedCall(engine)
        engine.simulateVideoRequest(callId)

        engine.respondToVideoRequest(callId, accept = false)
        val second = engine.respondToVideoRequest(callId, accept = true)

        assertIs<Outcome.Failure<SipError>>(second)
    }

    // ================================================================ Tasks 55, 57

    @Test
    fun `a blind transfer is accepted, which is not the same as succeeding`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val callId = connectedCall(engine)

        engine.transfer(callId, carol, TransferType.BLIND)

        assertEquals(
            listOf(TransferEvent.Accepted(callId, TransferType.BLIND)),
            engine.transferHistory,
        )
        // Still on the call: the transferor does not leave until the transferee answers.
        assertTrue(engine.activeCalls.value.isNotEmpty())
    }

    @Test
    fun `a transfer that succeeds releases the leg and says why it went`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val callId = connectedCall(engine)
        engine.transfer(callId, carol, TransferType.BLIND)

        engine.simulateTransferSucceeded(callId)

        assertTrue(engine.transferHistory.contains(TransferEvent.Succeeded(callId)))
        assertTrue(engine.activeCalls.value.isEmpty())
    }

    @Test
    fun `a failed transfer returns the call to connected, not to a dead end`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val callId = connectedCall(engine)
        engine.transfer(callId, carol, TransferType.BLIND)

        engine.simulateTransferFailed(callId)

        // §5.2, and Task 55's third done-when.
        assertIs<CallState.Connected>(engine.activeCalls.value.single().state)
        assertIs<TransferEvent.Failed>(engine.transferHistory.last())
    }

    @Test
    fun `transfer progress is reported between acceptance and the outcome`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val callId = connectedCall(engine)
        engine.transfer(callId, carol, TransferType.BLIND)

        engine.simulateTransferProgress(callId, responseCode = 180)

        assertEquals(TransferEvent.Progressing(callId, 180), engine.transferHistory.last())
    }

    @Test
    fun `an attended transfer without a consultation call is refused`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val callId = connectedCall(engine)

        val result = engine.transfer(callId, carol, TransferType.ATTENDED, consultationCallId = null)

        assertIs<SipError.InvalidState>(assertIs<Outcome.Failure<SipError>>(result).error)
    }
}
