package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.HoldParty
import com.whatsappv2.domain.call.SecondCallResponse
import com.whatsappv2.domain.engine.CameraAvailability
import com.whatsappv2.domain.engine.NoCameraAvailable
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeSipEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Task 56, end to end through the fake engine.
 *
 * `CallWaitingPolicyTest` asserts the *order* of the steps; this asserts that running them
 * leaves the calls where they should be — and, in the last test, that a failed hold stops
 * the sequence rather than answering anyway. That is the one that matters: answering after
 * a failed hold is the two-live-calls bug arriving by a different route.
 */
class CallWaitingUseCaseTest {

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

    private object CameraPresent : CameraAvailability {
        override fun isCameraUsable(): Boolean = true
    }

    private fun useCase(engine: FakeSipEngine, camera: CameraAvailability = NoCameraAvailable) =
        CallWaitingUseCase(engine, camera)

    /** A connected first call, and a second one ringing. */
    private suspend fun twoCalls(engine: FakeSipEngine): Pair<CallId, CallId> {
        val first = engine.simulateIncomingCall(account.id, bob).callId
        engine.answer(first, MediaProfile.AUDIO)
        val second = engine.simulateIncomingCall(account.id, carol).callId
        return first to second
    }

    @Test
    fun `hold and answer leaves exactly one call live`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val (first, second) = twoCalls(engine)

        val result = useCase(engine).respond(second, SecondCallResponse.ACCEPT_AND_HOLD)

        assertIs<Outcome.Success<Unit>>(result)
        val calls = engine.activeCalls.value.associateBy { it.callId }
        assertEquals(CallState.Held(HoldParty.LOCAL), calls.getValue(first).state)
        assertIs<CallState.Connected>(calls.getValue(second).state)
    }

    @Test
    fun `end and answer leaves only the new call`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val (_, second) = twoCalls(engine)

        useCase(engine).respond(second, SecondCallResponse.ACCEPT_AND_END)

        val remaining = engine.activeCalls.value.single()
        assertEquals(second, remaining.callId)
        assertIs<CallState.Connected>(remaining.state)
    }

    @Test
    fun `rejecting leaves the first call untouched`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val (first, second) = twoCalls(engine)

        useCase(engine).respond(second, SecondCallResponse.REJECT)

        val remaining = engine.activeCalls.value.single()
        assertEquals(first, remaining.callId)
        assertIs<CallState.Connected>(remaining.state)
    }

    @Test
    fun `swapping puts exactly one on hold and one active`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val (first, second) = twoCalls(engine)
        useCase(engine).respond(second, SecondCallResponse.ACCEPT_AND_HOLD)

        useCase(engine).swapTo(first)

        val calls = engine.activeCalls.value.associateBy { it.callId }
        assertIs<CallState.Connected>(calls.getValue(first).state)
        assertEquals(CallState.Held(HoldParty.LOCAL), calls.getValue(second).state)
        // Never two active, at any point in the sequence.
        assertEquals(1, engine.activeCalls.value.count { it.state is CallState.Connected })
    }

    @Test
    fun `swapping to the live call changes nothing and asks the engine for nothing`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val first = engine.simulateIncomingCall(account.id, bob).callId
        engine.answer(first, MediaProfile.AUDIO)
        engine.clearInvocations()

        assertIs<Outcome.Success<Unit>>(useCase(engine).swapTo(first))

        assertTrue(engine.invocations.isEmpty())
    }

    @Test
    fun `a failed hold stops the sequence rather than answering anyway`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val (_, second) = twoCalls(engine)
        engine.failNext(FakeSipEngine.Operation.SET_HOLD, SipError.EngineUnavailable)

        val result = useCase(engine).respond(second, SecondCallResponse.ACCEPT_AND_HOLD)

        assertIs<Outcome.Failure<SipError>>(result)
        // The second call is still ringing: it was never answered, which is the point.
        assertIs<CallState.Incoming>(engine.activeCalls.value.single { it.callId == second }.state)
    }

    @Test
    fun `answering with video downgrades when there is no camera`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val (_, second) = twoCalls(engine)

        useCase(engine).respond(second, SecondCallResponse.ACCEPT_AND_HOLD, withVideo = true)

        // Task 51: a declined camera downgrades the call rather than failing it.
        assertEquals(MediaProfile.AUDIO, engine.activeCalls.value.single { it.callId == second }.media)
    }

    @Test
    fun `answering with video keeps it when a camera is available`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val (_, second) = twoCalls(engine)

        useCase(engine, CameraPresent).respond(second, SecondCallResponse.ACCEPT_AND_HOLD, withVideo = true)

        assertEquals(MediaProfile.AUDIO_VIDEO, engine.activeCalls.value.single { it.callId == second }.media)
    }
}
