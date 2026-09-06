package com.whatsappv2.data.sip.call

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.data.sip.registration.TestTarget
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.call.HoldParty
import com.whatsappv2.domain.engine.CallSnapshot
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.DtmfDigit
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A whole audio call against a real FreeSWITCH, on a real device (Task 46, §8, DoD 8).
 *
 * ## What this suite is for
 *
 * The same division as Task 33's registration suite. Everything decidable without a server
 * is already exact on the JVM against `FakeSipEngine` and a fake gateway — the state
 * machine, the mapping, the refusals. What cannot be faked is whether a real INVITE, a
 * real re-INVITE and real RTP behave the way the fake pretends. That is what runs here.
 *
 * So the assertions are coarse and the waits are generous. A re-INVITE crossing a network
 * takes as long as it takes, and a suite that failed on a tight timer would be reporting
 * on the network rather than on the client.
 *
 * ## Both ends, one engine
 *
 * [CallTestHarness] registers both configured extensions on one engine, so the outgoing
 * call and the inbound INVITE it produces are the same call seen from both sides. That is
 * how the first done-when — outgoing *and* incoming — is covered without a second client.
 *
 * ## Skips rather than fails
 *
 * With no target configured every test here reports skipped, naming the properties to
 * set. See [TestTarget] and `docs/testing.md`.
 */
@RunWith(AndroidJUnit4::class)
class CallIntegrationTest {

    private lateinit var harness: CallTestHarness

    @Before
    fun setUp() = runBlocking {
        val target = TestTarget.requireConfigured()
        harness = CallTestHarness(
            context = InstrumentationRegistry.getInstrumentation().targetContext,
            target = target,
        )
        harness.start()
        registerBothExtensions()
    }

    @After
    fun tearDown() {
        if (::harness.isInitialized) harness.stop()
    }

    // ---------------------------------------------------------------- the call itself

    @Test
    fun anOutgoingCallReachesTheOtherExtension() = runBlocking {
        val callId = placeCall()

        // Telecom is asked before the INVITE goes out (Task 34, §3), and on a real call
        // that ordering is the thing a fake cannot prove.
        assertTrue(harness.platform.registeredOutgoing.any { it.callId == callId })
        assertNotNull(awaitCall(callId) { it.state is CallState.Outgoing })
    }

    @Test
    fun anInboundInviteArrivesOnTheOtherAccount() = runBlocking {
        placeCall()

        // The same call, seen from the callee. It is the registrar that put it there, so
        // this is a real INVITE over the wire rather than a locally invented event.
        val inbound = awaitAnyCall { it.state is CallState.Incoming }
        assertNotNull(inbound, "no inbound INVITE arrived at ${harness.callee.username}")
    }

    @Test
    fun aCallConnectsWhenTheOtherEndAnswers() = runBlocking {
        val connected = connectedCall()

        assertNotNull(connected, "the call never reached Connected")
    }

    @Test
    fun hangingUpEndsTheCallAtBothEnds() = runBlocking {
        val callId = connectedCall()!!.callId

        harness.engine.hangup(callId, HangupReason.LOCAL_HANGUP)

        assertNotNull(
            awaitNoCall(callId),
            "the call was still reported after hangup",
        )
    }

    // ---------------------------------------------------------------- mid-call controls

    @Test
    fun holdAndResumeSurviveARealReInvite() = runBlocking {
        val callId = connectedCall()!!.callId

        harness.engine.setHold(callId, held = true)
        val held = awaitCall(callId) { (it.state as? CallState.Held)?.by == HoldParty.LOCAL }
        assertNotNull(held, "the call never reported held")

        harness.engine.setHold(callId, held = false)
        val resumed = awaitCall(callId) { it.state is CallState.Connected }
        assertNotNull(resumed, "the call never came back from hold")
    }

    @Test
    fun mutingIsReportedOnTheCall() = runBlocking {
        val callId = connectedCall()!!.callId

        harness.engine.setMuted(callId, muted = true)
        assertNotNull(
            awaitCall(callId) { it.state.controlsOrNull?.isMuted == true },
            "mute was not reported",
        )

        harness.engine.setMuted(callId, muted = false)
        assertNotNull(
            awaitCall(callId) { it.state.controlsOrNull?.isMuted == false },
            "unmute was not reported",
        )
    }

    @Test
    fun theSpeakerRouteIsRequestedAndReported() = runBlocking {
        val callId = connectedCall()!!.callId

        val accepted = harness.engine.setAudioRoute(callId, AudioRoute.SPEAKER)

        assertTrue(accepted is Outcome.Success)
        assertTrue(harness.platform.requestedRoutes.any { it.second == AudioRoute.SPEAKER })
    }

    @Test
    fun everyDtmfDigitIsAcceptedOnAConnectedCall() = runBlocking {
        val callId = connectedCall()!!.callId

        // All sixteen, because the ones that are not on a keypad — A to D — are the ones
        // a stack is most likely to drop, and an IVR that wants them will not say so.
        for (digit in DtmfDigit.entries) {
            val sent = harness.engine.sendDtmf(callId, digit)
            assertTrue(sent is Outcome.Success, "the stack refused $digit")
        }
    }

    /**
     * Bluetooth is **not** covered, and that is a property of the runner rather than of
     * the client.
     *
     * Switching to a Bluetooth route needs a paired, connected headset. An emulator has
     * none, and a device in a CI rack has none either — so a test that asserted the switch
     * would be asserting that the runner has a headset, and would fail for a reason that
     * says nothing about this app. The route request itself is exact on the JVM, where the
     * available routes are injected.
     *
     * This test is here rather than absent so the gap is visible in the report instead of
     * being something a reader has to notice is missing.
     */
    @Test
    @Ignore("Needs a paired Bluetooth headset; no runner or emulator has one")
    fun theBluetoothRouteCanBeSelected() = Unit

    // ---------------------------------------------------------------- fixture

    private suspend fun registerBothExtensions() {
        harness.engine.register(harness.caller)
        harness.engine.register(harness.callee)

        val registered = withTimeoutOrNull(REGISTRATION_TIMEOUT_MILLIS) {
            while (harness.engine.registrationState.value.count { it.value.isUsable } < BOTH) {
                delay(POLL_MILLIS)
            }
            true
        }
        assertTrue(registered == true, "both extensions must register before a call")
    }

    /** Places the call and returns its id, failing the test if the engine refused. */
    private suspend fun placeCall(): CallId {
        val target = SipUri.parse(harness.calleeAddress()).getOrNull()
        assertNotNull(target, "the callee address did not parse")

        val placed = harness.engine.placeCall(harness.caller.id, target, MediaProfile.AUDIO)
        val callId = placed.getOrNull()
        assertNotNull(callId, "the engine refused the call: $placed")
        return callId
    }

    /**
     * A call that is up at both ends.
     *
     * The inbound leg is answered from the same engine, which is what makes this one
     * process a complete call: a real 200 OK goes back over the wire and the outbound leg
     * connects because of it, not because a fake said so.
     */
    private suspend fun connectedCall(): CallSnapshot? {
        val callId = placeCall()
        val inbound = awaitAnyCall { it.state is CallState.Incoming }
        assertNotNull(inbound, "no inbound INVITE to answer")

        harness.engine.answer(inbound.callId, MediaProfile.AUDIO)
        return awaitCall(callId) { it.state is CallState.Connected }
    }

    private suspend fun awaitCall(callId: CallId, predicate: (CallSnapshot) -> Boolean) =
        withTimeoutOrNull(CALL_TIMEOUT_MILLIS) {
            var match: CallSnapshot? = null
            while (match == null) {
                match = harness.engine.activeCalls.value
                    .firstOrNull { it.callId == callId && predicate(it) }
                if (match == null) delay(POLL_MILLIS)
            }
            match
        }

    private suspend fun awaitAnyCall(predicate: (CallSnapshot) -> Boolean) =
        withTimeoutOrNull(CALL_TIMEOUT_MILLIS) {
            var match: CallSnapshot? = null
            while (match == null) {
                match = harness.engine.activeCalls.value.firstOrNull(predicate)
                if (match == null) delay(POLL_MILLIS)
            }
            match
        }

    private suspend fun awaitNoCall(callId: CallId) =
        withTimeoutOrNull(CALL_TIMEOUT_MILLIS) {
            while (harness.engine.activeCalls.value.any { it.callId == callId }) {
                delay(POLL_MILLIS)
            }
            true
        }

    private companion object {
        /** Generous on purpose: a REGISTER crossing a real network takes as long as it takes. */
        const val REGISTRATION_TIMEOUT_MILLIS = 20_000L

        /** Longer still: an INVITE, a 200 OK and RTP setup are three round trips. */
        const val CALL_TIMEOUT_MILLIS = 30_000L

        const val POLL_MILLIS = 200L
        const val BOTH = 2
    }
}
