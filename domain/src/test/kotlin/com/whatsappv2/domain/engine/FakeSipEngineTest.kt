package com.whatsappv2.domain.engine

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.HoldParty
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.DtmfDigit
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.RegistrationState
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.TransferType
import com.whatsappv2.domain.model.Transport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeSipEngineTest {

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

    private fun engine() = FakeSipEngine()

    // ================================================================ done-when #1
    //
    // "A test can script a full incoming-call-answered-then-remote-hangup sequence in
    // < 20 lines." The body below is the whole scenario.

    @Test
    fun `an incoming call can be answered and then hung up by the far end`() = runTest {
        val engine = engine().givenRegistered(account)

        val incoming = engine.simulateIncomingCall(account.id, bob)
        assertEquals(1, engine.activeCalls.value.size)

        engine.answer(incoming.callId, MediaProfile.AUDIO)
        assertIs<CallState.Connected>(engine.activeCalls.value.single().state)

        engine.clock.advanceBy(42_000L)
        engine.simulateRemoteHangup(incoming.callId)

        assertTrue(engine.activeCalls.value.isEmpty())
        val ended = engine.terminatedCalls.single()
        assertEquals(CallState.Terminated(HangupReason.REMOTE_HANGUP), ended.state)
        assertEquals(42_000L, ended.durationMillis(engine.clock.nowEpochMillis()))
    }

    // ================================================================ registration

    @Test
    fun `registration succeeds and reports the granted expiry`() = runTest {
        val engine = engine()
        assertIs<Outcome.Success<Unit>>(engine.register(account))
        assertEquals(
            RegistrationState.Registered(3_600),
            engine.registrationState.value[account.id],
        )
    }

    @Test
    fun `a scripted auth failure leaves an observable Failed state`() = runTest {
        // Returning an error is not enough: the account list renders from
        // registrationState, so a failure that leaves no state is invisible (Task 31).
        val engine = engine().failNext(FakeSipEngine.Operation.REGISTER, SipError.AuthenticationFailed(401))

        assertIs<Outcome.Failure<SipError>>(engine.register(account))
        val state = engine.registrationState.value[account.id]
        assertEquals(
            RegistrationState.Failed(RegistrationFailure.AUTHENTICATION_FAILED, retryScheduled = true),
            state,
        )
    }

    @Test
    fun `a registrar timeout is distinguishable from a rejected password`() = runTest {
        val engine = engine().failNext(FakeSipEngine.Operation.REGISTER, SipError.Timeout)
        engine.register(account)
        val failed = assertIs<RegistrationState.Failed>(engine.registrationState.value[account.id])
        assertEquals(RegistrationFailure.TIMEOUT, failed.reason)
        assertTrue(!failed.reason.requiresUserAction, "a timeout should retry on its own")
    }

    @Test
    fun `a one-shot failure applies once and then clears`() = runTest {
        val engine = engine().failNext(FakeSipEngine.Operation.REGISTER, SipError.Timeout)
        assertIs<Outcome.Failure<SipError>>(engine.register(account))
        assertIs<Outcome.Success<Unit>>(engine.register(account))
    }

    @Test
    fun `a persistent failure applies until cleared`() = runTest {
        val engine = engine().alwaysFail(FakeSipEngine.Operation.REGISTER, SipError.Timeout)
        assertIs<Outcome.Failure<SipError>>(engine.register(account))
        assertIs<Outcome.Failure<SipError>>(engine.register(account))
        engine.clearFailures()
        assertIs<Outcome.Success<Unit>>(engine.register(account))
    }

    @Test
    fun `invocations are recorded in order so callers can assert on sequencing`() = runTest {
        // Task 29 requires editing a registered account to unregister BEFORE registering.
        val engine = engine()
        engine.register(account)
        engine.unregister(account.id)
        engine.register(account)

        assertEquals(
            listOf(
                FakeSipEngine.Operation.REGISTER,
                FakeSipEngine.Operation.UNREGISTER,
                FakeSipEngine.Operation.REGISTER,
            ),
            engine.invocations.map { it.operation },
        )
    }

    // ================================================================ outgoing calls

    @Test
    fun `placing a call requires a registered account`() = runTest {
        val engine = engine()
        assertEquals(SipError.UnknownAccount, failureOf(engine.placeCall(account.id, bob, MediaProfile.AUDIO)))

        engine.givenRegistered(account)
        engine.simulateRegistrationExpiry(account.id)
        assertEquals(SipError.NotRegistered, failureOf(engine.placeCall(account.id, bob, MediaProfile.AUDIO)))
    }

    @Test
    fun `an outgoing call progresses through ringing to connected`() = runTest {
        val engine = engine().givenRegistered(account)
        val callId = requireNotNull(engine.placeCall(account.id, bob, MediaProfile.AUDIO).getOrNull())

        assertEquals(CallState.Outgoing.Calling, state(engine, callId))
        engine.simulateRemoteRinging(callId)
        assertEquals(CallState.Outgoing.Ringing, state(engine, callId))
        engine.simulateRemoteEarlyMedia(callId)
        assertEquals(CallState.Outgoing.EarlyMedia, state(engine, callId))
        engine.simulateRemoteAnswer(callId)
        assertIs<CallState.Connected>(state(engine, callId))
    }

    @Test
    fun `a busy far end ends the call with the reason the call log will record`() = runTest {
        val engine = engine().givenRegistered(account)
        val callId = requireNotNull(engine.placeCall(account.id, bob, MediaProfile.AUDIO).getOrNull())

        engine.simulateRemoteRejection(callId, SipError.Busy(486))

        val ended = engine.terminatedCalls.single()
        assertEquals(CallState.Terminated(HangupReason.BUSY), ended.state)
        assertNull(ended.connectedAtEpochMillis, "a busy call never connected")
    }

    @Test
    fun `a timeout ends the call as no answer`() = runTest {
        val engine = engine().givenRegistered(account)
        val callId = requireNotNull(engine.placeCall(account.id, bob, MediaProfile.AUDIO).getOrNull())
        engine.simulateRemoteRejection(callId, SipError.Timeout)
        assertEquals(CallState.Terminated(HangupReason.NO_ANSWER), engine.terminatedCalls.single().state)
    }

    @Test
    fun `hanging up an already ended call succeeds, per the engine contract`() = runTest {
        val engine = engine().givenRegistered(account)
        val callId = requireNotNull(engine.placeCall(account.id, bob, MediaProfile.AUDIO).getOrNull())
        engine.simulateRemoteHangup(callId)
        assertIs<Outcome.Success<Unit>>(engine.hangup(callId, HangupReason.LOCAL_HANGUP))
    }

    // ================================================================ in-call

    @Test
    fun `hold and resume run through the real state machine`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId

        engine.setHold(callId, held = true)
        assertEquals(CallState.Held(HoldParty.LOCAL), state(engine, callId))

        engine.setHold(callId, held = false)
        assertIs<CallState.Connected>(state(engine, callId))
    }

    @Test
    fun `a remote hold is visible and both-sides hold resolves correctly`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId

        engine.setHold(callId, held = true)
        engine.simulateRemoteHold(callId)
        assertEquals(CallState.Held(HoldParty.BOTH), state(engine, callId))

        // Resuming locally must NOT report the call live while the far end still holds.
        engine.setHold(callId, held = false)
        assertEquals(CallState.Held(HoldParty.REMOTE), state(engine, callId))
    }

    @Test
    fun `mute and audio route are recorded on the call, not as phase changes`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId

        engine.setMuted(callId, muted = true)
        engine.setAudioRoute(callId, AudioRoute.BLUETOOTH)

        val controls = requireNotNull(state(engine, callId).controlsOrNull)
        assertTrue(controls.isMuted)
        assertEquals(AudioRoute.BLUETOOTH, controls.audioRoute)
        assertIs<CallState.Connected>(state(engine, callId))
    }

    @Test
    fun `controls are refused before media exists`() = runTest {
        val engine = engine().givenRegistered(account)
        val callId = requireNotNull(engine.placeCall(account.id, bob, MediaProfile.AUDIO).getOrNull())
        assertIs<SipError.InvalidState>(failureOf(engine.setMuted(callId, muted = true)))
    }

    @Test
    fun `DTMF requires an established call`() = runTest {
        val engine = engine().givenRegistered(account)
        val callId = requireNotNull(engine.placeCall(account.id, bob, MediaProfile.AUDIO).getOrNull())
        assertIs<SipError.InvalidState>(failureOf(engine.sendDtmf(callId, DtmfDigit.FIVE)))

        engine.simulateRemoteAnswer(callId)
        assertIs<Outcome.Success<Unit>>(engine.sendDtmf(callId, DtmfDigit.FIVE))
    }

    @Test
    fun `an unknown call is reported rather than ignored`() = runTest {
        val engine = engine()
        assertEquals(SipError.UnknownCall, failureOf(engine.answer(CallId("nope"), MediaProfile.AUDIO)))
    }

    // ================================================================ transfer

    @Test
    fun `a failed transfer returns the call rather than stranding it`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId

        engine.transfer(callId, bob, TransferType.BLIND)
        assertIs<CallState.Transferring>(state(engine, callId))

        engine.simulateTransferFailed(callId)
        assertIs<CallState.Connected>(state(engine, callId))
    }

    @Test
    fun `a successful transfer releases this leg`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId
        engine.transfer(callId, bob, TransferType.BLIND)
        engine.simulateTransferSucceeded(callId)
        assertTrue(engine.activeCalls.value.isEmpty())
    }

    @Test
    fun `an attended transfer without a consultation call is rejected`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId
        assertIs<SipError.InvalidState>(
            failureOf(engine.transfer(callId, bob, TransferType.ATTENDED, consultationCallId = null)),
        )
    }

    // ================================================================ network

    @Test
    fun `network loss drops registrations and calls, and recovery restores them`() = runTest {
        val engine = connectedCall()

        engine.simulateNetworkLoss()
        assertTrue(engine.activeCalls.value.isEmpty())
        assertEquals(
            CallState.Terminated(HangupReason.NETWORK_FAILURE),
            engine.terminatedCalls.last().state,
        )
        val failed = assertIs<RegistrationState.Failed>(engine.registrationState.value[account.id])
        assertEquals(RegistrationFailure.NETWORK_UNAVAILABLE, failed.reason)

        engine.simulateNetworkRestored()
        assertTrue(requireNotNull(engine.registrationState.value[account.id]).isUsable)
    }

    // ================================================================ lifecycle

    @Test
    fun `after shutdown every operation reports the engine is unavailable`() = runTest {
        val engine = engine().givenRegistered(account)
        engine.shutdown()

        assertEquals(SipError.EngineUnavailable, failureOf(engine.register(account)))
        assertEquals(SipError.EngineUnavailable, failureOf(engine.unregister(account.id)))
        assertTrue(engine.activeCalls.value.isEmpty())
    }

    // ================================================================ determinism

    @Test
    fun `time only moves when the test moves it`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId

        val before = engine.activeCalls.value.single().durationMillis(engine.clock.nowEpochMillis())
        assertEquals(0L, before)

        engine.clock.advanceBy(90_000L)
        val after = engine.activeCalls.value.single().durationMillis(engine.clock.nowEpochMillis())
        assertEquals(90_000L, after)
        assertEquals(callId, engine.activeCalls.value.single().callId)
    }

    // ================================================================ helpers

    private suspend fun connectedCall(): FakeSipEngine {
        val engine = engine().givenRegistered(account)
        val incoming = engine.simulateIncomingCall(account.id, bob)
        engine.answer(incoming.callId, MediaProfile.AUDIO)
        return engine
    }

    private fun state(engine: FakeSipEngine, callId: CallId): CallState =
        requireNotNull(engine.activeCalls.value.firstOrNull { it.callId == callId }) {
            "no active call $callId"
        }.state

    private fun <T> failureOf(outcome: Outcome<T, SipError>): SipError =
        assertIs<Outcome.Failure<SipError>>(outcome).error
}
