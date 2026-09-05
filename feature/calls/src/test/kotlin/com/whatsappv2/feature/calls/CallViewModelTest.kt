package com.whatsappv2.feature.calls

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.DtmfDigit
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeSipEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The call screen's state, driven by [FakeSipEngine] (Tasks 37 and 39).
 *
 * No SIP server, no device, no Telecom: the engine's fake runs the real
 * `CallStateMachine`, so a screen tested against it is tested against the same rules
 * production enforces.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModelTest {

    private val engine = FakeSipEngine()
    private val clock = engine.clock
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = CallViewModel(calls = engine, media = engine, clock = clock)

    @Test
    fun `an outgoing call renders its phase as the stack moves it`() = runTest {
        val callId = placeCall()
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            assertIs<CallUiState.Loading>(awaitItem())

            engine.simulateRemoteRinging(callId)
            awaitDisplay { it.phase == CallPhase.RINGING }

            engine.simulateRemoteAnswer(callId)
            awaitDisplay { it.phase == CallPhase.CONNECTED }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `early media is its own phase, because it is audible`() = runTest {
        // Reporting it as ringing would leave the app playing a local ringback over the
        // announcement the network is already sending.
        val callId = placeCall()
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            engine.simulateRemoteEarlyMedia(callId)

            awaitDisplay { it.phase == CallPhase.EARLY_MEDIA }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the timer counts from the answer, not from the dial`() = runTest {
        // Task 39's third done-when: driven by call start rather than a counter. The call
        // rang for thirty seconds and the duration must not include them.
        val callId = placeCall()
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            clock.advanceBy(THIRTY_SECONDS)
            engine.simulateRemoteAnswer(callId)
            // Zero, not thirty: the call rang for half a minute and none of it counts.
            awaitDisplay { it.phase == CallPhase.CONNECTED && it.durationSeconds == 0L }

            clock.advanceBy(TEN_SECONDS)
            advanceTimeBy(TICK)
            runCurrent()

            awaitDisplay { it.durationSeconds == TEN_SECONDS / MILLIS_PER_SECOND }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a duration recomputed from the answer cannot drift`() = runTest {
        // The same subtraction gives the same answer however many ticks were missed,
        // which is the whole reason it is a subtraction and not a counter.
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            clock.advanceBy(ONE_HOUR)
            advanceTimeBy(TICK)
            runCurrent()

            awaitDisplay { it.durationSeconds == ONE_HOUR / MILLIS_PER_SECOND }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a call that ends finishes the screen`() = runTest {
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            engine.simulateRemoteHangup(callId)

            awaitFinished()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an inbound call is answered with what was offered, not with more`() = runTest {
        // Answering an audio offer with video is an escalation the peer never asked for.
        engine.givenRegistered(ACCOUNT)
        val incoming = engine.simulateIncomingCall(ACCOUNT.id, REMOTE)
        val viewModel = viewModel().also { it.watch(incoming.callId) }

        viewModel.uiState.test {
            skipItems(1)
            viewModel.answer(withVideo = false)
            runCurrent()

            val connected = awaitDisplay { it.phase == CallPhase.CONNECTED }
            assertEquals(MediaProfile.AUDIO.hasVideo, connected.videoOffered)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `declining sends a decline, not a busy`() = runTest {
        // The caller hears the difference: 603 means the user was there and said no.
        engine.givenRegistered(ACCOUNT)
        val incoming = engine.simulateIncomingCall(ACCOUNT.id, REMOTE)
        val viewModel = viewModel().also { it.watch(incoming.callId) }

        viewModel.reject()
        runCurrent()

        val terminated = assertIs<CallState.Terminated>(engine.terminatedCalls.single().state)
        assertEquals(HangupReason.LOCAL_REJECTED, terminated.reason)
    }

    @Test
    fun `muting reaches the engine and comes back in the state`() = runTest {
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            viewModel.setMuted(true)
            runCurrent()

            awaitDisplay { it.controls.isMuted }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the speaker is a route, so turning it off returns to the earpiece`() = runTest {
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            viewModel.setSpeakerOn(true)
            runCurrent()
            awaitDisplay { it.controls.audioRoute == AudioRoute.SPEAKER }

            viewModel.setSpeakerOn(false)
            runCurrent()
            awaitDisplay { it.controls.audioRoute == AudioRoute.EARPIECE }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `holding and resuming move the call through the FSM the engine enforces`() = runTest {
        // The fake runs the real CallStateMachine, so this is the same rule production
        // applies: Connected -> Held -> Resuming -> Connected, and nothing skipped.
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            viewModel.setHold(true)
            runCurrent()
            awaitDisplay { it.phase == CallPhase.ON_HOLD }

            viewModel.setHold(false)
            runCurrent()
            awaitDisplay { it.phase == CallPhase.CONNECTED }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a hold applied by the far end is shown as theirs, and offers no resume`() = runTest {
        // Pressing resume would change nothing: the far end is the only side that can
        // lift it, which is why the phase and the availability are different values.
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.uiState.test {
            skipItems(1)
            engine.simulateRemoteHold(callId)

            val held = awaitDisplay { it.phase == CallPhase.HELD_BY_REMOTE }
            assertEquals(false, held.availability.canResume)
            assertEquals(false, held.availability.canSendDtmf)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a keypad press sends exactly that digit`() = runTest {
        // One digit per press: an IVR acts on each tone as it arrives, so a batch sent
        // together is a sequence the caller never typed.
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.sendDtmf(DtmfDigit.STAR)
        viewModel.sendDtmf(DtmfDigit.SEVEN)
        runCurrent()

        assertEquals(
            listOf("${callId.value}:*", "${callId.value}:7"),
            engine.invocations
                .filter { it.operation == FakeSipEngine.Operation.SEND_DTMF }
                .map { it.detail },
        )
    }

    @Test
    fun `a digit the engine refuses is reported, not lost`() = runTest {
        // A tone that never left is worse than an error: the caller keeps pressing keys
        // an IVR will never hear.
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        engine.failNext(FakeSipEngine.Operation.SEND_DTMF, SipError.EngineUnavailable)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.events.test {
            viewModel.sendDtmf(DtmfDigit.ONE)
            runCurrent()

            val event = assertIs<CallEvent.ActionFailed>(awaitItem())
            assertEquals(CallAction.DTMF, event.action)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an action the engine refuses is reported rather than swallowed`() = runTest {
        // The user pressed a button and is owed an answer. A silent failure is how a
        // screen ends up looking broken with nothing in the log.
        val callId = placeCall()
        engine.simulateRemoteAnswer(callId)
        engine.failNext(FakeSipEngine.Operation.HANGUP, SipError.EngineUnavailable)
        val viewModel = viewModel().also { it.watch(callId) }

        viewModel.events.test {
            viewModel.hangUp()
            runCurrent()

            val event = assertIs<CallEvent.ActionFailed>(awaitItem())
            assertEquals(CallAction.HANG_UP, event.action)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun placeCall(): CallId {
        engine.givenRegistered(ACCOUNT)
        return engine.placeCall(ACCOUNT.id, REMOTE, MediaProfile.AUDIO).getOrNull()!!
    }

    /**
     * Waits for the call to look like [predicate] describes.
     *
     * Not "the next item": the screen's state changes for reasons other than the one under
     * test — a tick, a control, the engine publishing the same call again — and a test that
     * counted emissions would be asserting the shape of the flow rather than the behaviour
     * of the screen.
     */
    private suspend fun ReceiveTurbine<CallUiState>.awaitDisplay(
        predicate: (CallDisplay) -> Boolean,
    ): CallDisplay {
        while (true) {
            val item = awaitItem()
            if (item is CallUiState.Active && predicate(item.call)) return item.call
        }
    }

    private suspend fun ReceiveTurbine<CallUiState>.awaitFinished(): CallUiState.Finished {
        var item = awaitItem()
        while (item !is CallUiState.Finished) item = awaitItem()
        return item
    }

    private companion object {
        val REMOTE: SipUri = SipUri.parse("sip:bob@sip.example.com").getOrNull()!!

        const val MILLIS_PER_SECOND = 1_000L
        const val TICK = 1_100L
        const val TEN_SECONDS = 10_000L
        const val THIRTY_SECONDS = 30_000L
        const val ONE_HOUR = 3_600_000L

        val ACCOUNT = SipAccount(
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
    }
}
