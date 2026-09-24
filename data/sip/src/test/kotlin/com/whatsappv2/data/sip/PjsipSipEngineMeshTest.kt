package com.whatsappv2.data.sip

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.data.sip.call.ConferenceInfoWriter
import com.whatsappv2.data.sip.call.StackCallState
import com.whatsappv2.data.sip.call.StackParticipant
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The full-mesh conference at the engine (`ConferenceMesh`).
 *
 * Every assertion here is about something that could otherwise only be seen with four
 * handsets in one room: whether a member dials the peers it owes, whether it dials one
 * twice, whether the device relays audio it should not, and whether removing somebody
 * reaches the members who were not removed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PjsipSipEngineMeshTest {

    private val fixture = PjsipSipEngineTest()

    /** This device is `alice`; the others sort above and below it on purpose. */
    private val bob = uri("sip:bob@sip.example.com")
    private val carol = uri("sip:carol@sip.example.com")
    private val self = uri("sip:alice@sip.example.com")

    private fun uri(value: String): SipUri = requireNotNull(SipUri.parse(value).getOrNull())

    /** Two connected calls to *different* people, which is what a merge starts from. */
    private suspend fun TestScope.twoConnected(): PjsipSipEngine = with(fixture) {
        val engine = registeredEngine()
        listOf(bob, carol).forEach { peer ->
            val id = engine.placeCall(fixture.account.id, peer, MediaProfile.AUDIO).getOrNull()!!
            runCurrent()
            gateway.emitCall(id.value, StackCallState.CONNECTED, remoteUri = peer.render())
            runCurrent()
        }
        engine
    }

    @Test
    fun `a conference mixed here relays nothing and composes nothing`() = runTest {
        val engine = twoConnected()

        engine.mixCalls(engine.activeCalls.value.map { it.callId }.toSet())
        advanceUntilIdle()

        // The two halves of "no duplicates". Every pair in a mesh holds a dialog of its
        // own, so a cross-link on the audio bridge would be that pair heard twice and a
        // composed canvas would be every participant drawn twice.
        assertEquals(false, fixture.gateway.conferenceRelays.last(), "the mesh relayed audio between members")
        assertEquals(false, fixture.gateway.videoConferenceComposes.last(), "the mesh composed a canvas")
    }

    @Test
    fun `the focus announces a mesh roster naming everybody`() = runTest {
        val engine = twoConnected()

        engine.mixCalls(engine.activeCalls.value.map { it.callId }.toSet())
        advanceUntilIdle()

        val announced = fixture.gateway.announcedRosters
        assertEquals(2, announced.size, "the roster did not reach both members")
        announced.forEach { (_, document) ->
            assertTrue(
                document.contains("<${ConferenceInfoWriter.TOPOLOGY}>${ConferenceInfoWriter.MESH}"),
                "the roster did not say it was a mesh: $document",
            )
            // Everybody, this device included: a member filters itself out by address,
            // and a roster that omitted the focus would have every member drop the one
            // leg they certainly need.
            listOf("alice", "bob", "carol").forEach { member ->
                assertTrue(document.contains(member), "$member is missing from the roster: $document")
            }
        }
    }

    @Test
    fun `the focus is the one device that may remove a member`() = runTest {
        val engine = twoConnected()
        assertFalse(engine.hostsConference.value)

        engine.mixCalls(engine.activeCalls.value.map { it.callId }.toSet())
        advanceUntilIdle()

        assertTrue(engine.hostsConference.value)
    }

    @Test
    fun `a member told the roster calls the peer it owes a leg to`() = runTest {
        // One call, to the focus. `alice` sorts below `carol`, so this end of that pair
        // dials — and it must not dial `bob`, which it is already talking to.
        val engine = with(fixture) { registeredEngine() }
        val toBob = engine.placeCall(fixture.account.id, bob, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()
        fixture.gateway.emitCall(toBob.value, StackCallState.CONNECTED, remoteUri = bob.render())
        runCurrent()
        val placedBefore = fixture.gateway.placedCalls.size

        fixture.gateway.emitConference(
            callKey = toBob.value,
            participants = listOf(participant(self), participant(bob), participant(carol)),
            mesh = true,
            entity = bob.render(),
        )
        advanceUntilIdle()

        val dialled = fixture.gateway.placedCalls.drop(placedBefore)
        assertEquals(1, dialled.size, "a member dialled the wrong number of peers: $dialled")
        assertTrue(dialled.single().destination.contains("carol"), "dialled ${dialled.single().destination}")
        // Tagged, so the far end answers it as a conference leg instead of ringing.
        assertEquals(bob.render(), dialled.single().conferenceEntity)
    }

    @Test
    fun `a member does not dial a peer that is expected to call it`() = runTest {
        // `alice` sorts above neither `bob` nor... it sorts below both, so instead the
        // roster is read from the other side: a device whose own address sorts above the
        // missing peer waits rather than dialling, or the pair would open two dialogs.
        val engine = with(fixture) { registeredEngine() }
        val toCarol = engine.placeCall(fixture.account.id, carol, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()
        fixture.gateway.emitCall(toCarol.value, StackCallState.CONNECTED, remoteUri = carol.render())
        runCurrent()
        val placedBefore = fixture.gateway.placedCalls.size

        // A roster naming somebody below this device: `aaron` sorts under `alice`.
        val aaron = uri("sip:aaron@sip.example.com")
        fixture.gateway.emitConference(
            callKey = toCarol.value,
            participants = listOf(participant(self), participant(carol), participant(aaron)),
            mesh = true,
            entity = carol.render(),
        )
        advanceUntilIdle()

        assertEquals(
            placedBefore,
            fixture.gateway.placedCalls.size,
            "dialled a peer that was going to dial us: ${fixture.gateway.placedCalls}",
        )
    }

    @Test
    fun `an inbound leg of the conference this device is in is answered without ringing`() = runTest {
        val engine = with(fixture) { registeredEngine() }
        val toBob = engine.placeCall(fixture.account.id, bob, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()
        fixture.gateway.emitCall(toBob.value, StackCallState.CONNECTED, remoteUri = bob.render())
        fixture.gateway.emitConference(
            callKey = toBob.value,
            participants = listOf(participant(self), participant(bob), participant(carol)),
            mesh = true,
            entity = bob.render(),
        )
        advanceUntilIdle()

        fixture.gateway.emitCall(
            callKey = "mesh-leg",
            state = StackCallState.INCOMING_RECEIVED,
            remoteUri = carol.render(),
            conferenceEntity = bob.render(),
        )
        advanceUntilIdle()

        assertTrue(
            fixture.gateway.answeredCalls.any { it.first == "mesh-leg" },
            "a mesh leg was not answered: ${fixture.gateway.answeredCalls}",
        )
    }

    @Test
    fun `an inbound call naming a conference this device is not in still rings`() = runTest {
        // The header is a claim anybody can put on an INVITE. Answering on the strength of
        // it alone would let a stranger open this device's microphone without it ringing.
        val engine = with(fixture) { registeredEngine() }
        val toBob = engine.placeCall(fixture.account.id, bob, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()
        fixture.gateway.emitCall(toBob.value, StackCallState.CONNECTED, remoteUri = bob.render())
        fixture.gateway.emitConference(
            callKey = toBob.value,
            participants = listOf(participant(self), participant(bob)),
            mesh = true,
            entity = bob.render(),
        )
        advanceUntilIdle()

        fixture.gateway.emitCall(
            callKey = "stranger",
            state = StackCallState.INCOMING_RECEIVED,
            remoteUri = "sip:mallory@elsewhere.example.com",
            conferenceEntity = "sip:someone@elsewhere.example.com",
        )
        advanceUntilIdle()

        assertFalse(
            fixture.gateway.answeredCalls.any { it.first == "stranger" },
            "answered a call for a conference this device is not in",
        )
    }

    @Test
    fun `removing a member takes them out of the roster the others are told`() = runTest {
        val engine = twoConnected()
        engine.mixCalls(engine.activeCalls.value.map { it.callId }.toSet())
        advanceUntilIdle()

        val carolLeg = engine.activeCalls.value.first { it.remote.user == "carol" }.callId
        fixture.gateway.announcedRosters.clear()

        engine.removeFromConference(carolLeg)
        advanceUntilIdle()

        // Their leg is gone here, and — the part that only a restated roster can do — the
        // members who were *not* removed are told, so each of them closes its own leg.
        assertTrue(engine.activeCalls.value.none { it.callId == carolLeg })
        assertTrue(
            fixture.gateway.terminatedCalls.contains(carolLeg.value),
            "the removed member's leg was not ended",
        )
    }

    @Test
    fun `ending one member does not end the conference`() = runTest {
        val engine = twoConnected()
        val ids = engine.activeCalls.value.map { it.callId }.toSet()
        engine.mixCalls(ids)
        advanceUntilIdle()

        val carolLeg = engine.activeCalls.value.first { it.remote.user == "carol" }.callId
        engine.removeFromConference(carolLeg)
        advanceUntilIdle()

        // The deliberate difference from `hangup`, which fans out across the mix.
        assertEquals(1, engine.activeCalls.value.size, "removing one member ended the rest")
    }

    private fun participant(uri: SipUri) = StackParticipant(
        id = uri.render(),
        uri = uri.render(),
        displayName = null,
        isMuted = false,
        isSpeaking = false,
        isSelf = false,
        hasVideoStream = false,
        joinedAtEpochMillis = null,
    )

    @Test
    fun `an inbound leg from an awaited peer is answered even with no header on the INVITE`() = runTest {
        // FreeSWITCH is a B2BUA: it builds a fresh INVITE for the outbound leg and carries
        // no custom header it was not configured to copy, so the marker this app puts on a
        // mesh INVITE does not survive the server it ships against. Answering has to work
        // without it.
        val engine = with(fixture) { registeredEngine() }
        val toCarol = engine.placeCall(fixture.account.id, carol, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()
        fixture.gateway.emitCall(toCarol.value, StackCallState.CONNECTED, remoteUri = carol.render())
        // `aaron` sorts below `alice`, so under the glare rule `aaron` is the end that
        // dials and this device is the end that waits — which is exactly the case the
        // fallback has to cover.
        val aaron = uri("sip:aaron@sip.example.com")
        fixture.gateway.emitConference(
            callKey = toCarol.value,
            participants = listOf(participant(self), participant(carol), participant(aaron)),
            mesh = true,
            entity = carol.render(),
        )
        advanceUntilIdle()

        fixture.gateway.emitCall(
            callKey = "from-aaron",
            state = StackCallState.INCOMING_RECEIVED,
            remoteUri = aaron.render(),
        )
        advanceUntilIdle()

        assertTrue(
            fixture.gateway.answeredCalls.any { it.first == "from-aaron" },
            "an awaited mesh peer was left ringing: ${fixture.gateway.answeredCalls}",
        )
    }

    @Test
    fun `a second call from somebody already in the conference still rings`() = runTest {
        // The fallback above must not swallow a genuine call. A participant this device
        // already holds a leg to is not awaited, so nothing auto-answers them.
        val engine = twoConnected()
        engine.mixCalls(engine.activeCalls.value.map { it.callId }.toSet())
        advanceUntilIdle()

        fixture.gateway.emitCall(
            callKey = "second-call",
            state = StackCallState.INCOMING_RECEIVED,
            remoteUri = carol.render(),
        )
        advanceUntilIdle()

        assertFalse(
            fixture.gateway.answeredCalls.any { it.first == "second-call" },
            "a genuine second call was answered without ringing",
        )
    }
}
