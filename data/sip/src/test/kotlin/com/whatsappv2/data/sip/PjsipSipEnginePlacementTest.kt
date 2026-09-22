package com.whatsappv2.data.sip

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.data.sip.call.StackCallState
import com.whatsappv2.domain.call.AudioRoute
import com.whatsappv2.domain.engine.CallPlacement
import com.whatsappv2.domain.engine.PlatformDecision
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The legs of a group call placed together, and what the platform is told about each
 * ([CallPlacement]).
 *
 * Telecom permits one outgoing call in progress. A conference called back from history
 * dials every member at once, so all but one of its legs go out without a connection —
 * and the engine then has to keep the platform's view of the call honest by hand: one
 * registered leg while any leg is live, brought up to date when it takes over.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PjsipSipEnginePlacementTest {

    private val fixture = PjsipSipEngineFixture()
    private val bob: SipUri = PjsipSipEngineFixture.TARGET
    private val carol: SipUri = requireNotNull(SipUri.parse("sip:carol@sip.example.com").getOrNull())
    private val dave: SipUri = requireNotNull(SipUri.parse("sip:dave@sip.example.com").getOrNull())

    private suspend fun PjsipSipEngine.dial(to: SipUri, placement: CallPlacement = CallPlacement.STANDALONE) =
        placeCall(fixture.account.id, to, MediaProfile.AUDIO, placement).getOrNull()!!

    @Test
    fun `a member placed beside a dialling leg goes out without asking the platform`() = runTest {
        val engine = with(fixture) { registeredEngine() }

        val first = engine.dial(bob)
        val second = engine.dial(carol, CallPlacement.CONFERENCE_MEMBER)
        runCurrent()

        assertEquals(
            listOf(first),
            fixture.platform.registeredOutgoing.map { it.callId },
            "the platform hears of the first leg only",
        )
        assertEquals(2, fixture.gateway.placedCalls.size, "both INVITEs go out")
        assertEquals(
            mapOf(first to true, second to false),
            engine.activeCalls.value.associate { it.callId to it.platformManaged },
        )
        engine.stop()
    }

    @Test
    fun `a member placed with nothing dialling is registered like any call`() = runTest {
        // The first leg of a group is placed STANDALONE by the coordinator, but a member
        // placed on its own — nothing else dialling — must not slip past the platform
        // either: that is the leg a cellular call has to be able to refuse.
        val engine = with(fixture) { registeredEngine() }

        val only = engine.dial(carol, CallPlacement.CONFERENCE_MEMBER)
        runCurrent()

        assertEquals(listOf(only), fixture.platform.registeredOutgoing.map { it.callId })
        assertTrue(engine.activeCalls.value.single().platformManaged)
        engine.stop()
    }

    @Test
    fun `a member the platform refuses is still refused`() = runTest {
        // The exemption is for the one-outgoing-call limit only. A refusal for a
        // cellular call is honoured for every leg that is asked about.
        val engine = with(fixture) { registeredEngine() }
        fixture.platform.outgoingDecision = PlatformDecision.Refused

        val result = engine.placeCall(
            fixture.account.id,
            carol,
            MediaProfile.AUDIO,
            CallPlacement.CONFERENCE_MEMBER,
        )
        runCurrent()

        assertEquals(SipError.CallNotPermitted, result.errorOrNull())
        assertTrue(fixture.gateway.placedCalls.isEmpty())
        engine.stop()
    }

    @Test
    fun `the platform hears nothing about a leg it was not told of`() = runTest {
        val engine = with(fixture) { registeredEngine() }
        val first = engine.dial(bob)
        val second = engine.dial(carol, CallPlacement.CONFERENCE_MEMBER)
        runCurrent()

        fixture.gateway.emitCall(first.value, StackCallState.CONNECTED)
        fixture.gateway.emitCall(second.value, StackCallState.CONNECTED, remoteUri = carol.render())
        runCurrent()
        assertEquals(listOf(first), fixture.platform.connected, "only the registered leg is reported connected")

        fixture.gateway.emitCall(second.value, StackCallState.ENDED, remoteUri = carol.render())
        runCurrent()
        assertTrue(fixture.platform.ended.none { it.first == second }, "and its ending is not reported either")
        assertEquals(listOf(first), engine.activeCalls.value.map { it.callId })
        engine.stop()
    }

    @Test
    fun `when the registered leg ends first a survivor takes the connection`() = runTest {
        // The first member is busy while the others still ring. Without this the
        // platform believes the call is over: audio focus gone, nothing on the lock
        // screen, and the next placement refused as "on another call" — or worse,
        // permitted over a call it does not know about.
        val engine = with(fixture) { registeredEngine() }
        val first = engine.dial(bob)
        val second = engine.dial(carol, CallPlacement.CONFERENCE_MEMBER)
        val third = engine.dial(dave, CallPlacement.CONFERENCE_MEMBER)
        runCurrent()
        // Dave answered; Carol still rings.
        fixture.gateway.emitCall(third.value, StackCallState.CONNECTED, remoteUri = dave.render())
        runCurrent()

        fixture.gateway.emitCall(first.value, StackCallState.ERROR, statusCode = 486)
        runCurrent()

        assertEquals(listOf(first to HangupReason.BUSY), fixture.platform.ended)
        assertEquals(
            listOf(first, third),
            fixture.platform.registeredOutgoing.map { it.callId },
            "the established leg is preferred",
        )
        assertEquals(listOf(third), fixture.platform.connected, "and told it is already connected")
        assertEquals(
            mapOf(second to false, third to true),
            engine.activeCalls.value.associate { it.callId to it.platformManaged },
        )
        engine.stop()
    }

    @Test
    fun `a leg connecting with no registered leg beside it becomes the registered one`() = runTest {
        // Every other leg has ended by the time this one answers — the survivor was
        // promoted while ringing and then failed itself, say. Connected and unmanaged
        // is the state the promotion on ending cannot reach, so connecting checks too.
        val engine = with(fixture) { registeredEngine() }
        val first = engine.dial(bob)
        val second = engine.dial(carol, CallPlacement.CONFERENCE_MEMBER)
        runCurrent()
        // The platform will not take the promotion when the first leg fails...
        fixture.platform.outgoingDecision = PlatformDecision.Unavailable
        fixture.gateway.emitCall(first.value, StackCallState.ERROR, statusCode = 486)
        runCurrent()
        assertFalse(engine.activeCalls.value.single().platformManaged, "the promotion was refused")

        // ...but does when the leg connects.
        fixture.platform.outgoingDecision = PlatformDecision.Permitted
        fixture.gateway.emitCall(second.value, StackCallState.CONNECTED, remoteUri = carol.render())
        runCurrent()

        assertTrue(engine.activeCalls.value.single().platformManaged)
        assertEquals(listOf(second), fixture.platform.connected)
        engine.stop()
    }

    @Test
    fun `a route asked of an unmanaged leg is routed through the registered one`() = runTest {
        // One device, one audio path: Speaker pressed on the second leg's card must move
        // the whole call, and the platform only knows the first leg.
        val engine = with(fixture) { registeredEngine() }
        val first = engine.dial(bob)
        val second = engine.dial(carol, CallPlacement.CONFERENCE_MEMBER)
        runCurrent()
        fixture.gateway.emitCall(second.value, StackCallState.CONNECTED, remoteUri = carol.render())
        runCurrent()

        assertTrue(engine.setAudioRoute(second, AudioRoute.SPEAKER) is Outcome.Success)

        assertEquals(listOf(first to AudioRoute.SPEAKER), fixture.platform.requestedRoutes)
        engine.stop()
    }

    @Test
    fun `a promoted leg is given the route the call was on`() = runTest {
        // A fresh connection starts on the earpiece; the call was on the speaker.
        val engine = with(fixture) { registeredEngine() }
        val first = engine.dial(bob)
        val second = engine.dial(carol, CallPlacement.CONFERENCE_MEMBER)
        runCurrent()
        fixture.gateway.emitCall(second.value, StackCallState.CONNECTED, remoteUri = carol.render())
        runCurrent()
        engine.setAudioRoute(second, AudioRoute.SPEAKER)
        runCurrent()

        fixture.gateway.emitCall(first.value, StackCallState.ENDED)
        runCurrent()

        assertEquals(
            listOf(first to AudioRoute.SPEAKER, second to AudioRoute.SPEAKER),
            fixture.platform.requestedRoutes,
            "the new connection is told where the call already is",
        )
        engine.stop()
    }

    @Test
    fun `a promotion reports the call as it is once the platform has answered, not as it was`() = runTest {
        // Registration waits on Telecom. The survivor was ringing when it was chosen and
        // answered while the platform was deciding; the connection must be told active,
        // and the answer must not be overwritten by the ringing snapshot the promotion
        // started from.
        val engine = with(fixture) { registeredEngine() }
        val first = engine.dial(bob)
        val second = engine.dial(carol, CallPlacement.CONFERENCE_MEMBER)
        runCurrent()
        fixture.platform.onRegisterOutgoing = { registering ->
            if (registering.callId == second) {
                fixture.gateway.emitCall(second.value, StackCallState.CONNECTED, remoteUri = carol.render())
            }
        }

        fixture.gateway.emitCall(first.value, StackCallState.ENDED)
        runCurrent()

        val survivor = engine.activeCalls.value.single()
        assertTrue(survivor.platformManaged)
        assertTrue(survivor.state.isEstablished, "the answer that landed mid-registration is kept: ${survivor.state}")
        assertEquals(listOf(second), fixture.platform.connected)
        engine.stop()
    }

    @Test
    fun `a survivor that ends while the platform is deciding has the connection ended too`() = runTest {
        val engine = with(fixture) { registeredEngine() }
        val first = engine.dial(bob)
        val second = engine.dial(carol, CallPlacement.CONFERENCE_MEMBER)
        runCurrent()
        fixture.platform.onRegisterOutgoing = { registering ->
            if (registering.callId == second) {
                fixture.gateway.emitCall(second.value, StackCallState.ENDED, remoteUri = carol.render())
            }
        }

        fixture.gateway.emitCall(first.value, StackCallState.ENDED)
        runCurrent()

        assertTrue(engine.activeCalls.value.isEmpty())
        assertTrue(fixture.platform.ended.any { it.first == second }, "the connection made for it is not left dialling")
        engine.stop()
    }

    @Test
    fun `a leg asked to take the connection twice in one event is registered once`() = runTest {
        // The registered leg ends and, in the same turn, the survivor connects: the
        // ending chooses it and the connecting checks for it. One connection, not two.
        val engine = with(fixture) { registeredEngine() }
        val first = engine.dial(bob)
        val second = engine.dial(carol, CallPlacement.CONFERENCE_MEMBER)
        runCurrent()

        fixture.gateway.emitCall(first.value, StackCallState.ENDED)
        fixture.gateway.emitCall(second.value, StackCallState.CONNECTED, remoteUri = carol.render())
        runCurrent()

        assertEquals(listOf(first, second), fixture.platform.registeredOutgoing.map { it.callId })
        assertEquals(listOf(second), fixture.platform.connected)
        engine.stop()
    }

    @Test
    fun `nothing is promoted while a registered leg remains`() = runTest {
        val engine = with(fixture) { registeredEngine() }
        val first = engine.dial(bob)
        val second = engine.dial(carol, CallPlacement.CONFERENCE_MEMBER)
        val third = engine.dial(dave, CallPlacement.CONFERENCE_MEMBER)
        runCurrent()

        fixture.gateway.emitCall(second.value, StackCallState.ENDED, remoteUri = carol.render())
        runCurrent()

        assertEquals(listOf(first), fixture.platform.registeredOutgoing.map { it.callId })
        assertEquals(setOf(first, third), engine.activeCalls.value.mapTo(HashSet()) { it.callId })
        engine.stop()
    }
}
