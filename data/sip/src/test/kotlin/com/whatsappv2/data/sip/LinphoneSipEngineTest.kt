@file:OptIn(ExperimentalCoroutinesApi::class)

package com.whatsappv2.data.sip

import app.cash.turbine.test
import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.core.common.time.MutableClock
import com.whatsappv2.data.sip.call.StackCallState
import com.whatsappv2.data.sip.network.FakeNetworkMonitor
import com.whatsappv2.data.sip.registration.FakeLinphoneCoreGateway
import com.whatsappv2.data.sip.registration.LinphoneCoreGateway
import com.whatsappv2.data.sip.registration.RegistrationStateMapper
import com.whatsappv2.data.sip.registration.StackPushParameters
import com.whatsappv2.data.sip.registration.StackRegistrationEvent
import com.whatsappv2.data.sip.registration.StackRegistrationState
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.HoldParty
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.PushToken
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.DtmfDigit
import com.whatsappv2.domain.model.DtmfMode
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.RegistrationState
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.repository.SipAccountRepository
import com.whatsappv2.domain.testing.FakeAppSettingsRepository
import com.whatsappv2.domain.testing.FakePlatformCallRegistry
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The fake stack, the account, and the states the engine tests start from.
 *
 * Task 27's done-when is only reachable because the SDK sits behind
 * [com.whatsappv2.data.sip.registration.LinphoneCoreGateway]: liblinphone cannot run
 * here, so without that seam none of this could be tested before a device.
 *
 * The cases themselves are split by subject below. One class held all of them and had
 * grown past the point where anything could be found in it.
 *
 * The members are `internal` rather than `protected` because the engine and its fakes are
 * `internal` to this module, and a `protected` member of a public class may not expose
 * one. The subclasses are in this module, so `internal` reaches them just as well.
 */
open class LinphoneSipEngineFixture {

    internal val gateway = FakeLinphoneCoreGateway()
    internal val repository = FakeSipAccountRepository()

    internal val account = SipAccount(
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
        transport = Transport.TLS,
        registrationExpirySeconds = 600,
        stunServer = null,
        turn = null,
        natPolicy = NatPolicy.DEFAULT,
        srtpPolicy = SrtpPolicy.OPTIONAL,
        codecs = CodecPreferences.DEFAULT,
        isDefault = true,
    )

    internal val networkMonitor = FakeNetworkMonitor()

    /** Fixed, so a call's start and connect timestamps are equalities rather than ranges. */
    internal val clock = MutableClock().set(NOW)

    /** Telecom, faked. Permits everything unless a test says otherwise. */
    internal val platform = FakePlatformCallRegistry()

    /** App settings, which is where the DTMF transport comes from (Task 43). */
    internal val settings = FakeAppSettingsRepository()

    internal fun engine(scope: TestScope) =
        // The same fake twice: one object implements both halves of the seam, exactly as
        // the real gateway does, because one `Core` owns registration and calls alike.
        LinphoneSipEngine(
            gateway,
            gateway,
            repository,
            settings,
            networkMonitor,
            scope,
            NoOpLogger,
            clock,
            platform,
        ).also { repository.given(account) }

    /** Registered and ready to place a call. */
    internal suspend fun TestScope.registeredEngine(): LinphoneSipEngine {
        val engine = engine(this)
        engine.start()
        engine.register(account)
        gateway.emit(account.id.value, StackRegistrationState.OK)
        runCurrent()
        return engine
    }

    /** A call that has been placed and answered, ready for hold, mute or a DTMF digit. */
    internal suspend fun TestScope.connectedCall(): LinphoneSipEngine {
        val engine = registeredEngine()
        val callId = engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()
        gateway.emitCall(callId.value, StackCallState.CONNECTED)
        runCurrent()
        return engine
    }

    /** A connected call this end has put on hold, with the stack's acceptance in. */
    internal suspend fun TestScope.heldCall(): LinphoneSipEngine {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId
        engine.setHold(callId, held = true)
        gateway.emitCall(callId.value, StackCallState.PAUSED)
        runCurrent()
        return engine
    }

    internal companion object {
        /** Fixed instant, so a call's timestamps are equalities rather than ranges. */
        const val NOW = 1_700_000_000_000L

        val TARGET: SipUri = SipUri.parse("sip:bob@sip.example.com").getOrNull()!!

        /** 486 Busy Here, named so the assertions read as intent rather than arithmetic. */
        const val BUSY_HERE = 486
    }
}

/** Placing a call, and what the far end does to it (Tasks 35 and 37). */
class LinphoneSipEngineCallTest : LinphoneSipEngineFixture() {

    // ---------------------------------------------------------------- calls (Task 35)

    @Test
    fun `placing a call requires a registration, because an INVITE has nowhere else to go`() =
        runTest {
            // Failing here rather than letting the stack time out is the difference
            // between an immediate accurate message and thirty seconds of nothing.
            val engine = engine(this)
            engine.start()

            val result = engine.placeCall(account.id, TARGET, MediaProfile.AUDIO)

            assertEquals(SipError.NotRegistered, result.errorOrNull())
            assertTrue(gateway.placedCalls.isEmpty(), "no INVITE may leave without a binding")
            engine.stop()
        }

    @Test
    fun `a placed call appears before the INVITE, not after it`() = runTest {
        // Two reasons, and they are the same reason: a collector that attaches late must
        // still see the call, and Telecom needs a Connection to exist before the INVITE so
        // the platform can refuse it during a cellular call (Task 34, §3).
        val engine = registeredEngine()

        val callId = engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull()
        runCurrent()

        assertNotNull(callId)
        val snapshot = engine.activeCalls.value.single()
        assertEquals(CallState.Outgoing.Calling, snapshot.state)
        assertEquals(NOW, snapshot.startedAtEpochMillis)
        assertEquals(null, snapshot.connectedAtEpochMillis, "nothing has been answered yet")
        assertEquals(
            FakeLinphoneCoreGateway.PlacedCall(
                callKey = callId.value,
                accountKey = account.id.value,
                destination = TARGET.render(),
                videoEnabled = false,
            ),
            gateway.placedCalls.single(),
        )
        engine.stop()
    }

    @Test
    fun `ringing then answering walks the call through the state machine`() = runTest {
        val engine = registeredEngine()
        val callId = engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()

        gateway.emitCall(callId.value, StackCallState.OUTGOING_RINGING)
        runCurrent()
        assertEquals(CallState.Outgoing.Ringing, engine.activeCalls.value.single().state)

        gateway.emitCall(callId.value, StackCallState.CONNECTED)
        runCurrent()

        val connected = engine.activeCalls.value.single()
        assertIs<CallState.Connected>(connected.state)
        assertEquals(NOW, connected.connectedAtEpochMillis, "the duration starts at the answer")
        engine.stop()
    }

    @Test
    fun `early media is its own state, not ringing`() = runTest {
        // A 183 with SDP means audio is already arriving. Calling it "ringing" leaves the
        // app playing a local ringback over an announcement the network is sending.
        val engine = registeredEngine()
        val callId = engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()

        gateway.emitCall(callId.value, StackCallState.OUTGOING_EARLY_MEDIA)
        runCurrent()

        assertEquals(CallState.Outgoing.EarlyMedia, engine.activeCalls.value.single().state)
        engine.stop()
    }

    @Test
    fun `a call that ends stops being reported`() = runTest {
        val engine = registeredEngine()
        val callId = engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()

        gateway.emitCall(callId.value, StackCallState.ENDED)
        runCurrent()

        assertTrue(engine.activeCalls.value.isEmpty())
        engine.stop()
    }

    @Test
    fun `hanging up terminates the call and drops it immediately`() = runTest {
        // Immediately, not when the BYE is acknowledged: the user pressed hang up, and a
        // row that lingers reads as a button that did nothing.
        val engine = registeredEngine()
        val callId = engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()

        engine.hangup(callId, HangupReason.LOCAL_HANGUP)

        assertEquals(listOf(callId.value), gateway.terminatedCalls)
        assertTrue(engine.activeCalls.value.isEmpty())
        engine.stop()
    }

    @Test
    fun `hanging up a call that is already gone succeeds quietly`() = runTest {
        // The SipEngine contract requires idempotence: the caller cannot act on being
        // told otherwise, and the outcome they wanted is already true.
        val engine = registeredEngine()

        val result = engine.hangup(CallId("never-existed"), HangupReason.LOCAL_HANGUP)

        assertTrue(result is Outcome.Success)
        assertTrue(gateway.terminatedCalls.isEmpty(), "nothing to terminate")
        engine.stop()
    }

    @Test
    fun `Telecom is asked before the INVITE, not after it`() = runTest {
        // Task 35's ordering requirement, and the only place it is observable: once both
        // calls have returned, nothing about the outside world says which went first.
        val engine = registeredEngine()
        platform.onRegisterOutgoing = {
            assertTrue(gateway.placedCalls.isEmpty(), "the connection must exist before the INVITE")
        }

        engine.placeCall(account.id, TARGET, MediaProfile.AUDIO)
        runCurrent()

        assertEquals(1, platform.registeredOutgoing.size)
        assertEquals(1, gateway.placedCalls.size)
        engine.stop()
    }

    @Test
    fun `a call Telecom refuses is not placed and does not linger on screen`() = runTest {
        // A native call is in progress. §3 says honour it: no INVITE, and no row left
        // behind for a call that will never exist.
        val engine = registeredEngine()
        platform.permitOutgoing = false

        val result = engine.placeCall(account.id, TARGET, MediaProfile.AUDIO)
        runCurrent()

        assertEquals(SipError.CallNotPermitted, result.errorOrNull())
        assertTrue(gateway.placedCalls.isEmpty(), "nothing may reach the wire")
        assertTrue(engine.activeCalls.value.isEmpty())
        engine.stop()
    }

    @Test
    fun `Telecom is told when a call ends for a reason it did not cause`() = runTest {
        // Without this the platform holds audio focus for a call that is over.
        val engine = registeredEngine()
        val callId = engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()

        gateway.emitCall(callId.value, StackCallState.ERROR, statusCode = BUSY_HERE)
        runCurrent()

        assertEquals(listOf(callId to HangupReason.BUSY), platform.ended)
        engine.stop()
    }

    // ---------------------------------------------------------------- incoming (Task 37)

    @Test
    fun `an inbound INVITE becomes a ringing call and an event`() = runTest {
        val engine = registeredEngine()

        engine.incomingCalls.test {
            gateway.emitCall(
                callKey = "in-1",
                state = StackCallState.INCOMING_RECEIVED,
                remoteUri = "sip:carol@sip.example.com",
                displayName = "Carol",
            )
            runCurrent()

            val call = awaitItem()
            assertEquals(CallId("in-1"), call.callId)
            assertEquals("Carol", call.fromDisplayName)
            assertEquals(MediaProfile.AUDIO, call.offeredMedia)
            assertEquals(NOW, call.receivedAtEpochMillis)

            val snapshot = engine.activeCalls.value.single()
            assertEquals(CallDirection.INCOMING, snapshot.direction)
            assertIs<CallState.Incoming>(snapshot.state)
            cancelAndIgnoreRemainingEvents()
        }
        engine.stop()
    }

    @Test
    fun `a video offer is reported as one, so the UI can offer a video answer`() = runTest {
        val engine = registeredEngine()

        engine.incomingCalls.test {
            gateway.emitCall("in-1", StackCallState.INCOMING_RECEIVED, videoOffered = true)
            runCurrent()

            assertEquals(MediaProfile.AUDIO_VIDEO, awaitItem().offeredMedia)
            cancelAndIgnoreRemainingEvents()
        }
        engine.stop()
    }

    @Test
    fun `an inbound call Telecom refuses is answered busy and never rings`() = runTest {
        // The user is on a cellular call. Forcing our own full-screen UI over it is the
        // design §3 rejects, so the call is declined with 486 and nothing is shown.
        val engine = registeredEngine()
        platform.permitIncoming = false

        gateway.emitCall("in-1", StackCallState.INCOMING_RECEIVED)
        runCurrent()

        assertEquals(listOf("in-1" to true), gateway.rejectedCalls)
        assertTrue(engine.activeCalls.value.isEmpty(), "a refused call must not be shown")
        engine.stop()
    }

    @Test
    fun `answering an inbound call moves it through LocalAnswered, not RemoteAnswered`() = runTest {
        // The same stack state means different things by direction: CallState.Incoming
        // accepts only LocalAnswered, so a direction-blind mapping would reject the
        // transition and leave an answered call showing as ringing.
        val engine = registeredEngine()
        gateway.emitCall("in-1", StackCallState.INCOMING_RECEIVED)
        runCurrent()

        engine.answer(CallId("in-1"), MediaProfile.AUDIO)
        assertEquals(listOf("in-1" to false), gateway.answeredCalls)

        gateway.emitCall("in-1", StackCallState.CONNECTED)
        runCurrent()

        val snapshot = engine.activeCalls.value.single()
        assertIs<CallState.Connected>(snapshot.state)
        assertEquals(NOW, snapshot.connectedAtEpochMillis)
        assertEquals(listOf(CallId("in-1")), platform.connected)
        engine.stop()
    }

    @Test
    fun `answering a call that is not ringing is refused rather than sent`() = runTest {
        val engine = registeredEngine()
        val callId = engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()

        val result = engine.answer(callId, MediaProfile.AUDIO)

        assertIs<SipError.InvalidState>(result.errorOrNull())
        assertTrue(gateway.answeredCalls.isEmpty())
        engine.stop()
    }

    @Test
    fun `busy sends 486 and declining sends 603, because the caller hears the difference`() =
        runTest {
            val engine = registeredEngine()
            gateway.emitCall("in-1", StackCallState.INCOMING_RECEIVED)
            gateway.emitCall("in-2", StackCallState.INCOMING_RECEIVED)
            runCurrent()

            engine.reject(CallId("in-1"), HangupReason.BUSY)
            engine.reject(CallId("in-2"), HangupReason.LOCAL_REJECTED)

            assertEquals(listOf("in-1" to true, "in-2" to false), gateway.rejectedCalls)
            assertTrue(engine.activeCalls.value.isEmpty())
            engine.stop()
        }
}

/** Audio routing, hold, mute and DTMF (Tasks 40 to 43). */
class LinphoneSipEngineMediaTest : LinphoneSipEngineFixture() {

    // ---------------------------------------------------------------- media (Task 40)

    @Test
    fun `muting a connected call reaches the stack and the state`() = runTest {
        val engine = registeredEngine()
        val callId = engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()
        gateway.emitCall(callId.value, StackCallState.CONNECTED)
        runCurrent()

        assertTrue(engine.setMuted(callId, muted = true) is Outcome.Success)

        assertEquals(true, gateway.mutedCalls[callId.value])
        assertEquals(true, engine.activeCalls.value.single().state.controlsOrNull?.isMuted)
        engine.stop()
    }

    @Test
    fun `muting a call that is still ringing is refused, not silently accepted`() = runTest {
        // Accepting it would report success for an action that never muted a microphone.
        val engine = registeredEngine()
        val callId = engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()

        val result = engine.setMuted(callId, muted = true)

        assertIs<SipError.InvalidState>(result.errorOrNull())
        assertTrue(gateway.mutedCalls.isEmpty(), "the stack must not be told")
        engine.stop()
    }

    @Test
    fun `a route the platform cannot provide fails rather than playing somewhere else`() =
        runTest {
            // §5.2: a Bluetooth request with no headset connected must fail, not fall back
            // to the earpiece while the UI shows Bluetooth.
            val engine = registeredEngine()
            val callId = engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull()!!
            runCurrent()
            gateway.emitCall(callId.value, StackCallState.CONNECTED)
            runCurrent()
            platform.availableRoutes = setOf(AudioRoute.EARPIECE, AudioRoute.SPEAKER)

            val refused = engine.setAudioRoute(callId, AudioRoute.BLUETOOTH)
            assertIs<SipError.InvalidState>(refused.errorOrNull())
            assertEquals(
                AudioRoute.EARPIECE,
                engine.activeCalls.value.single().state.controlsOrNull?.audioRoute,
            )

            assertTrue(engine.setAudioRoute(callId, AudioRoute.SPEAKER) is Outcome.Success)
            assertEquals(
                AudioRoute.SPEAKER,
                engine.activeCalls.value.single().state.controlsOrNull?.audioRoute,
            )
            engine.stop()
        }

    // ---------------------------------------------------------------- hold (Task 41)

    @Test
    fun `holding asks the stack and waits for it, rather than showing a hold that has not happened`() =
        runTest {
            // A re-INVITE the far end answers with 488 is a hold that did not happen. The
            // state moves on the stack's event, exactly as Connected does on the answer.
            val engine = connectedCall()
            val callId = engine.activeCalls.value.single().callId

            assertTrue(engine.setHold(callId, held = true) is Outcome.Success)

            assertEquals(listOf(callId.value to true), gateway.holdRequests)
            assertIs<CallState.Connected>(
                engine.activeCalls.value.single().state,
                "the call is not held until the re-INVITE is accepted",
            )

            gateway.emitCall(callId.value, StackCallState.PAUSED)
            runCurrent()

            assertEquals(CallState.Held(HoldParty.LOCAL), engine.activeCalls.value.single().state)
            assertEquals(listOf(callId to true), platform.holdChanges)
            engine.stop()
        }

    @Test
    fun `resuming goes through Resuming and only reaches Connected when media runs again`() =
        runTest {
            val engine = heldCall()
            val callId = engine.activeCalls.value.single().callId

            assertTrue(engine.setHold(callId, held = false) is Outcome.Success)
            assertEquals(callId.value to false, gateway.holdRequests.last())

            gateway.emitCall(callId.value, StackCallState.RESUMING)
            runCurrent()
            assertIs<CallState.Resuming>(engine.activeCalls.value.single().state)

            gateway.emitCall(callId.value, StackCallState.STREAMS_RUNNING)
            runCurrent()

            assertIs<CallState.Connected>(engine.activeCalls.value.single().state)
            assertEquals(listOf(callId to true, callId to false), platform.holdChanges)
            engine.stop()
        }

    @Test
    fun `a hold by the far end is detected and reported as theirs`() = runTest {
        // Task 41's second done-when. Shown differently because the remedy is different:
        // there is nothing the local user can press to lift it.
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId

        gateway.emitCall(callId.value, StackCallState.PAUSED_BY_REMOTE)
        runCurrent()

        assertEquals(CallState.Held(HoldParty.REMOTE), engine.activeCalls.value.single().state)
        // Telecom is told either way: as far as the system UI is concerned it is held.
        assertEquals(listOf(callId to true), platform.holdChanges)
        engine.stop()
    }

    @Test
    fun `resuming a call only the far end holds is refused rather than sent`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId
        gateway.emitCall(callId.value, StackCallState.PAUSED_BY_REMOTE)
        runCurrent()

        val result = engine.setHold(callId, held = false)

        assertIs<SipError.InvalidState>(result.errorOrNull())
        assertTrue(gateway.holdRequests.isEmpty(), "no re-INVITE for a hold that is not ours")
        engine.stop()
    }

    @Test
    fun `with both ends holding, our resume leaves the call held by the far end`() = runTest {
        // Task 41's third done-when. The whole reason HoldParty exists: a boolean here
        // would resume a call the other party is still holding.
        val engine = heldCall()
        val callId = engine.activeCalls.value.single().callId

        gateway.emitCall(callId.value, StackCallState.PAUSED_BY_REMOTE)
        runCurrent()
        assertEquals(CallState.Held(HoldParty.BOTH), engine.activeCalls.value.single().state)

        engine.setHold(callId, held = false)
        gateway.emitCall(callId.value, StackCallState.RESUMING)
        runCurrent()

        assertEquals(CallState.Held(HoldParty.REMOTE), engine.activeCalls.value.single().state)
        assertTrue(
            platform.holdChanges.none { !it.second },
            "the call never came back, so Telecom must not be told it did",
        )
        engine.stop()
    }

    @Test
    fun `holding a call that is still ringing is refused, because there is no dialog yet`() =
        runTest {
            val engine = registeredEngine()
            val callId = engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull()!!
            runCurrent()

            val result = engine.setHold(callId, held = true)

            assertIs<SipError.InvalidState>(result.errorOrNull())
            assertTrue(gateway.holdRequests.isEmpty())
            engine.stop()
        }

    @Test
    fun `a paused state repeated by the stack does not disturb a call already held`() = runTest {
        // liblinphone re-reports Paused after a re-negotiation. The call must stay exactly
        // where it is, and Telecom must not be told about a hold it already knows about.
        val engine = heldCall()
        val callId = engine.activeCalls.value.single().callId

        gateway.emitCall(callId.value, StackCallState.PAUSED)
        runCurrent()

        assertEquals(CallState.Held(HoldParty.LOCAL), engine.activeCalls.value.single().state)
        assertEquals(listOf(callId to true), platform.holdChanges)
        engine.stop()
    }

    // ---------------------------------------------------------------- mute (Task 42)

    @Test
    fun `muting tells the platform as well as the stack`() = runTest {
        // Two mutes, and both are needed: the stack's stops uplink audio for this call,
        // and the platform's is what the system UI and a headset's mute button read.
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId

        engine.setMuted(callId, muted = true)

        assertEquals(true, gateway.mutedCalls[callId.value])
        assertEquals(true, platform.muted[callId])
        engine.stop()
    }

    @Test
    fun `muting a call that is already muted is success, not a second round trip`() = runTest {
        // A headset's mute button arrives here through Telecom, so answering it by setting
        // the platform's mute again would be this app echoing the platform back at itself.
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId
        engine.setMuted(callId, muted = true)

        gateway.mutedCalls.clear()
        platform.muted.clear()
        val result = engine.setMuted(callId, muted = true)

        assertTrue(result is Outcome.Success)
        assertTrue(gateway.mutedCalls.isEmpty(), "the stack was told nothing new")
        assertTrue(platform.muted.isEmpty(), "the platform was told nothing new")
        engine.stop()
    }

    // ---------------------------------------------------------------- DTMF (Task 43)

    @Test
    fun `a digit rides RFC 4733 unless the settings say otherwise`() = runTest {
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId

        assertTrue(engine.sendDtmf(callId, DtmfDigit.FIVE) is Outcome.Success)

        assertEquals(
            FakeLinphoneCoreGateway.SentDtmf(callId.value, '5', useInfo = false),
            gateway.sentDtmf.single(),
        )
        engine.stop()
    }

    @Test
    fun `the transport is read per digit, so a changed setting applies to the next one`() =
        runTest {
            // Not the next call: a user who changes the mode because an IVR is not hearing
            // them expects the next key press to be the one that works.
            val engine = connectedCall()
            val callId = engine.activeCalls.value.single().callId

            engine.sendDtmf(callId, DtmfDigit.ONE)
            settings.setDtmfMode(DtmfMode.SIP_INFO)
            engine.sendDtmf(callId, DtmfDigit.TWO)

            assertEquals(listOf(false, true), gateway.sentDtmf.map { it.useInfo })
            engine.stop()
        }

    @Test
    fun `all sixteen tones reach the stack as themselves`() = runTest {
        // Task 43's third done-when, as far as the JVM can take it: A-D are rare but
        // required by some PBX signalling, and an enum that carries them is worth nothing
        // if the path below drops them.
        val engine = connectedCall()
        val callId = engine.activeCalls.value.single().callId

        DtmfDigit.entries.forEach { engine.sendDtmf(callId, it) }

        assertEquals(
            DtmfDigit.entries.map { it.symbol },
            gateway.sentDtmf.map { it.digit },
        )
        engine.stop()
    }

    @Test
    fun `a digit sent before the call is answered is refused, not swallowed`() = runTest {
        // There is no media path to carry it, and reporting success would leave the user
        // pressing keys an IVR never hears.
        val engine = registeredEngine()
        val callId = engine.placeCall(account.id, TARGET, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()

        val result = engine.sendDtmf(callId, DtmfDigit.ONE)

        assertIs<SipError.InvalidState>(result.errorOrNull())
        assertTrue(gateway.sentDtmf.isEmpty())
        engine.stop()
    }

    @Test
    fun `a digit for a call the engine does not know fails as an unknown call`() = runTest {
        val engine = registeredEngine()

        val result = engine.sendDtmf(CallId("never-existed"), DtmfDigit.ONE)

        assertEquals(SipError.UnknownCall, result.errorOrNull())
        engine.stop()
    }
}

/**
 * The callback-to-Flow mapping itself: registration, push and the engine's lifecycle.
 *
 * Task 27's done-when asks for exactly this — what the stack reports arriving as a Flow
 * the app can collect, asserted with no device in the room.
 */
class LinphoneSipEngineTest : LinphoneSipEngineFixture() {

    // ---------------------------------------------------------------- push (Task 38)

    @Test
    fun `push parameters reach the stack and clear again on logout`() = runTest {
        // ADR-004: sent unconditionally, because they cost nothing when ignored and are
        // the only way the token reaches the server without a side channel of its own.
        val engine = registeredEngine()

        engine.setPushToken(PushToken(provider = "fcm", param = "sender-1", prid = "token-abc"))
        assertEquals(
            StackPushParameters("fcm", "sender-1", "token-abc"),
            gateway.pushParameters,
        )

        engine.setPushToken(null)
        assertEquals(null, gateway.pushParameters)
        engine.stop()
    }

    @Test
    fun `an event for an unknown call is ignored rather than inventing one`() = runTest {
        // The only way this happens today is an inbound INVITE, which Task 37 implements.
        val engine = registeredEngine()

        gateway.emitCall("not-ours", StackCallState.OUTGOING_RINGING)
        runCurrent()

        assertTrue(engine.activeCalls.value.isEmpty())
        engine.stop()
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    fun `nothing works before the engine is started`() = runTest {
        // Constructing the engine must not start a native stack as a side effect of
        // dependency injection, before the app has decided it needs one.
        val engine = engine(this)
        assertEquals(SipError.EngineUnavailable, engine.register(account).errorOrNull())
        assertEquals(0, gateway.startCount)
    }

    @Test
    fun `starting twice does not create a second stack`() = runTest {
        val engine = engine(this)
        engine.start()
        engine.start()
        assertEquals(1, gateway.startCount)
        engine.stop()
    }

    // ---------------------------------------------------------------- registration

    @Test
    fun `registering reports Registering immediately, before the stack answers`() = runTest {
        // The UI must show something is happening the moment the user presses save.
        val engine = engine(this)
        engine.start()

        engine.register(account)

        assertEquals(RegistrationState.Registering, engine.registrationState.value[account.id])
        engine.stop()
    }

    @Test
    fun `the stack's Ok event becomes Registered`() = runTest(StandardTestDispatcher()) {
        val engine = engine(this)
        engine.start()
        engine.register(account)
        advanceUntilIdle()

        gateway.emit(account.id.value, StackRegistrationState.OK)
        advanceUntilIdle()

        assertEquals(
            RegistrationState.Registered(600),
            engine.registrationState.value[account.id],
        )
        engine.stop()
    }

    @Test
    fun `a wrong password becomes Failed with AuthenticationFailed`() = runTest(StandardTestDispatcher()) {
        // Task 27 done-when, and Task 31 depends on it: the user must be told to check
        // their password, not shown a generic error.
        val engine = engine(this)
        engine.start()
        engine.register(account)
        advanceUntilIdle()

        gateway.emit(account.id.value, StackRegistrationState.FAILED, statusCode = 401)
        advanceUntilIdle()

        val state = assertIs<RegistrationState.Failed>(engine.registrationState.value[account.id])
        assertEquals(RegistrationFailure.AUTHENTICATION_FAILED, state.reason)
        assertTrue(state.reason.requiresUserAction)
        engine.stop()
    }

    @Test
    fun `credentials are fetched per registration and never retained`() = runTest {
        val engine = engine(this)
        engine.start()

        engine.register(account)

        // The password reached the gateway, which needs it, and came from the repository
        // rather than from anything the engine holds.
        assertEquals("hunter22", gateway.addedAccounts.single().password)

        // And the engine kept none of it. Structural rather than behavioural on purpose:
        // by the time a cached credential shows up in behaviour it has already been in
        // memory for the life of the process, which is what Task 18 forbids.
        assertTrue(
            "hunter22" !in reachableStrings(engine),
            "the engine is holding on to a decrypted password",
        )
        engine.stop()
    }

    @Test
    fun `the account is described to the stack with its effective values`() = runTest {
        val engine = engine(this)
        engine.start()
        engine.register(account)

        val added = gateway.addedAccounts.single()
        assertEquals("alice", added.username)
        // Auth username falls back to the username, and TLS implies port 5061.
        assertEquals("alice", added.authUsername)
        assertEquals("tls", added.transport)
        assertEquals("sip:sip.example.com:5061", added.registrarUri)
        assertEquals(600, added.expirySeconds)
        engine.stop()
    }

    @Test
    fun `unregistering removes the account from the stack`() = runTest {
        val engine = engine(this)
        engine.start()
        engine.register(account)

        val unregistering = launch { engine.unregister(account.id) }
        runCurrent()
        gateway.emit(account.id.value, StackRegistrationState.CLEARED)
        unregistering.join()

        assertEquals(listOf(account.id.value), gateway.removedKeys)
        engine.stop()
    }

    @Test
    fun `unregistering waits for the registrar to acknowledge`() = runTest {
        // The SipRegistrar contract, and Task 29 depends on it: logout stops the
        // foreground service next, and returning before the `Expires: 0` is answered
        // would let that stop cut the request off. A registrar that never hears it keeps
        // ringing this device until the binding lapses.
        val engine = engine(this)
        engine.start()
        engine.register(account)
        gateway.emit(account.id.value, StackRegistrationState.OK)
        runCurrent()

        val unregistering = launch { engine.unregister(account.id) }
        runCurrent()
        assertTrue(unregistering.isActive, "unregister returned before the registrar answered")

        gateway.emit(account.id.value, StackRegistrationState.CLEARED)
        unregistering.join()

        assertEquals(RegistrationState.Unregistered, engine.registrationState.value[account.id])
        engine.stop()
    }

    @Test
    fun `unregistering completes even when the registrar never answers`() = runTest {
        // Bounded, because the alternative is a logout that hangs on an unreachable
        // server. The account is reported unregistered regardless: leaving a stale
        // Registered behind would keep the foreground service alive with nothing to hold
        // open (§6) and show a registration that no longer exists.
        val engine = engine(this)
        engine.start()
        engine.register(account)
        gateway.emit(account.id.value, StackRegistrationState.OK)
        advanceUntilIdle()

        assertIs<Outcome.Success<Unit>>(engine.unregister(account.id))

        assertEquals(RegistrationState.Unregistered, engine.registrationState.value[account.id])
        engine.stop()
    }

    @Test
    fun `logging out leaves the stack holding no credentials`() = runTest {
        // Task 29's third done-when, where a decrypted password actually lives: the
        // stack's auth store. Asserts what is still held, not what was once handed over -
        // an append-only record could never answer the question.
        val engine = engine(this)
        engine.start()
        engine.register(account)
        assertEquals("hunter22", gateway.heldAccounts.getValue(account.id.value).password)

        engine.unregister(account.id)

        assertTrue(
            gateway.heldAccounts.isEmpty(),
            "the stack is still holding a logged-out account's password",
        )
        engine.stop()
    }

    @Test
    fun `unregistering an unknown account succeeds quietly`() = runTest {
        // The SipEngine contract: repeating an operation that already succeeded must not
        // fail, and a caller cannot act on "that was already gone".
        val engine = engine(this)
        engine.start()
        assertIs<Outcome.Success<Unit>>(engine.unregister(AccountId("never-registered")))
        engine.stop()
    }

    @Test
    fun `refreshing an unknown account is reported`() = runTest {
        val engine = engine(this)
        engine.start()
        assertEquals(SipError.UnknownAccount, engine.refreshRegistration(AccountId("nope")).errorOrNull())
        engine.stop()
    }

    @Test
    fun `state updates flow to a collector`() = runTest(StandardTestDispatcher()) {
        val engine = engine(this)
        engine.start()

        engine.registrationState.test {
            assertTrue(awaitItem().isEmpty())

            engine.register(account)
            advanceUntilIdle()
            // expectMostRecentItem, not awaitItem: StateFlow conflates, so asserting a
            // specific intermediate emission is a race rather than a property. That the
            // state PASSES THROUGH Registering is covered by its own test, which reads the
            // value synchronously before the stack answers.
            assertEquals(RegistrationState.Registering, expectMostRecentItem()[account.id])

            gateway.emit(account.id.value, StackRegistrationState.OK)
            advanceUntilIdle()
            assertEquals(RegistrationState.Registered(600), expectMostRecentItem()[account.id])

            cancelAndIgnoreRemainingEvents()
        }
        engine.stop()
    }

    @Test
    fun `stopping clears every registration`() = runTest(StandardTestDispatcher()) {
        val engine = engine(this)
        engine.start()
        engine.register(account)
        gateway.emit(account.id.value, StackRegistrationState.OK)
        advanceUntilIdle()

        engine.stop()

        assertTrue(engine.registrationState.value.isEmpty())
        assertEquals(1, gateway.stopCount)
    }
}

/** The translation itself, independent of the engine's bookkeeping. */
class RegistrationStateMapperTest {

    private fun event(
        state: StackRegistrationState,
        statusCode: Int? = null,
    ) = StackRegistrationEvent("acct-1", state, statusCode, message = null)

    @Test
    fun `a refresh keeps the account usable`() {
        // The binding is still valid throughout, so reporting Registering would make a
        // healthy account flicker every cycle.
        val state = RegistrationStateMapper.toDomain(
            event(StackRegistrationState.REFRESHING),
            requestedExpirySeconds = 300,
            retryScheduled = false,
        )
        assertEquals(RegistrationState.Registered(300), state)
        assertTrue(state.isUsable)
        assertTrue(RegistrationStateMapper.isUsable(StackRegistrationState.REFRESHING))
    }

    @Test
    fun `Cleared is a successful logout, not a failure`() {
        // It is the acknowledgement of Expires: 0. Reporting it as failed would make
        // every logout look like an error.
        assertEquals(
            RegistrationState.Unregistered,
            RegistrationStateMapper.toDomain(
                event(StackRegistrationState.CLEARED),
                requestedExpirySeconds = 300,
                retryScheduled = false,
            ),
        )
    }

    @Test
    fun `every stack state maps to something`() {
        for (state in StackRegistrationState.entries) {
            RegistrationStateMapper.toDomain(event(state), 300, retryScheduled = false)
        }
    }

    @Test
    fun `a failure without a status code is a transport problem, not a rejection`() {
        // The difference between "check your password" and "check your network".
        val error = RegistrationStateMapper.toSipError(event(StackRegistrationState.FAILED))
        assertIs<SipError.TransportFailure>(error)
    }

    @Test
    fun `a status code is mapped through the single error taxonomy`() {
        assertEquals(
            SipError.fromResponseCode(408),
            RegistrationStateMapper.toSipError(event(StackRegistrationState.FAILED, 408)),
        )
        assertEquals(
            SipError.fromResponseCode(503),
            RegistrationStateMapper.toSipError(event(StackRegistrationState.FAILED, 503)),
        )
    }

    @Test
    fun `retryScheduled distinguishes trying again from needing the user`() {
        val retrying = RegistrationStateMapper.toDomain(
            event(StackRegistrationState.FAILED, 408),
            requestedExpirySeconds = 300,
            retryScheduled = true,
        )
        assertTrue(assertIs<RegistrationState.Failed>(retrying).retryScheduled)
    }
}

/**
 * Every string reachable from an object's own bookkeeping.
 *
 * Deliberately does **not** follow [LinphoneCoreGateway] or [SipAccountRepository]. Those
 * two are supposed to hold a credential — the stack while an account is registered, the
 * store always and encrypted — and each is asserted separately. What this measures is
 * everything else, which is where a cached password would hide.
 *
 * A guard rather than a proof: it walks this project's own classes, so a credential
 * squirrelled away inside a framework type would slip past it. It catches the thing that
 * actually happens — a field added to the engine to "avoid decrypting on every refresh".
 */
private fun reachableStrings(root: Any): Set<String> = StringWalk().apply { visit(root) }.found

private class StringWalk {
    private val seen = IdentityHashMap<Any, Boolean>()
    val found: MutableSet<String> = mutableSetOf()

    fun visit(value: Any?) {
        if (value == null || seen.put(value, true) != null) return
        when {
            value is String -> found += value
            value is Map<*, *> -> {
                value.keys.forEach(::visit)
                value.values.forEach(::visit)
            }
            value is Iterable<*> -> value.forEach(::visit)
            value is LinphoneCoreGateway || value is SipAccountRepository -> Unit
            value.javaClass.name.startsWith(PROJECT_PACKAGE) -> visitFields(value)
        }
    }

    private fun visitFields(value: Any) {
        value.javaClass.declaredFields.forEach { field ->
            field.isAccessible = true
            visit(field.get(value))
        }
    }

    private companion object {
        const val PROJECT_PACKAGE = "com.whatsappv2"
    }
}
