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

    /**
     * Two connected calls to *different* people, which is what a merge starts from.
     *
     * @param coralx which of the two far ends is another build of this app. Both by
     *   default, because that is what a mesh is: a peer that does not name itself cannot
     *   reconcile a roster, so the focus carries it instead and the conference is no
     *   longer a pure mesh. Tests that want the mixed case pass a smaller set.
     */
    private suspend fun TestScope.twoConnected(
        coralx: Set<SipUri> = setOf(bob, carol),
    ): PjsipSipEngine = with(fixture) {
        val engine = registeredEngine()
        listOf(bob, carol).forEach { peer ->
            val id = engine.placeCall(fixture.account.id, peer, MediaProfile.AUDIO).getOrNull()!!
            runCurrent()
            if (peer in coralx) gateway.coralxPeers += id.value
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
        assertEquals(
            emptySet(),
            fixture.gateway.conferenceRelayed.last(),
            "the mesh relayed audio between members",
        )
        assertEquals(false, fixture.gateway.videoConferenceComposes.last(), "the mesh composed a canvas")
    }

    @Test
    fun `a member that is not this app is carried, and only that member`() = runTest {
        // The reported failure, at the engine. `carol` is a desk phone: she cannot
        // reconcile a roster, so nobody will ever dial her and nobody she could dial.

        // Before this, the whole conference was handed `relay = false` and the bridge
        // opened nothing — so the focus heard both and `bob` and `carol` heard only the
        // focus. Now exactly `carol` is carried, and `bob`, who can mesh, is not.
        val engine = twoConnected(coralx = setOf(bob))
        val carolLeg = engine.activeCalls.value.single { it.remote.user == "carol" }.callId

        engine.mixCalls(engine.activeCalls.value.map { it.callId }.toSet())
        advanceUntilIdle()

        assertEquals(
            setOf(carolLeg.value),
            fixture.gateway.conferenceRelayed.last(),
            "exactly the member that cannot mesh should be carried",
        )
    }

    @Test
    fun `the roster tells everybody which participant is being carried`() = runTest {
        // The other half of "no duplicates", and the part that reaches the *other*
        // devices: `bob` must not dial `carol`, because the focus is already relaying
        // her and a direct leg would be carol heard twice.
        val engine = twoConnected(coralx = setOf(bob))

        engine.mixCalls(engine.activeCalls.value.map { it.callId }.toSet())
        advanceUntilIdle()

        val document = fixture.gateway.announcedRosters.last().second
        assertTrue(document.contains("<${ConferenceInfoWriter.RELAYED}/>"), "no participant was marked: $document")
        // Exactly one of them, and it is carol: a marker on bob would stop the one pair
        // that can mesh from meshing.
        assertEquals(1, Regex("<${ConferenceInfoWriter.RELAYED}/>").findAll(document).count())
        val carolBlock = document.substringAfter("carol@").substringBefore("</user>")
        assertTrue(
            carolBlock.contains("<${ConferenceInfoWriter.RELAYED}/>"),
            "the marker is not on carol: $document",
        )
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
    fun `a member whose mesh collapses stops being in a conference`() = runTest {
        // Modelled from the *member* side, which is the side the defect lived on. A member's
        // `ConferenceSession` is born from the roster arriving in-dialog on its leg to the
        // focus -- not from `mixCalls` -- so a focus-side fixture cannot reach it, and the
        // first attempt at this test passed against the unfixed engine for that reason.
        //
        // Measured on a TC15, 2026-09-27: 1005 left a three-party mesh, FreeSWITCH collapsed
        // to one pair and the media followed, and the surviving member still read "3 people in
        // this conference" eight minutes later, because nothing retired the session the roster
        // had opened. `ConferenceSession.toUiState` builds the roster from `invited`, which
        // still named everybody who was ever in the room.
        val engine = with(fixture) { registeredEngine() }
        val toBob = engine.placeCall(fixture.account.id, bob, MediaProfile.AUDIO).getOrNull()!!
        runCurrent()
        fixture.gateway.emitCall(toBob.value, StackCallState.CONNECTED, remoteUri = bob.render())
        runCurrent()

        // The focus announces a three-way mesh on this device's leg to it. That is what opens
        // the session, and it is also what makes this device dial `carol`.
        fixture.gateway.emitConference(
            callKey = toBob.value,
            participants = listOf(participant(self), participant(bob), participant(carol)),
            mesh = true,
            entity = bob.render(),
        )
        advanceUntilIdle()
        val toCarol = fixture.gateway.placedCalls.last { it.destination.contains("carol") }
        fixture.gateway.emitCall(toCarol.callKey, StackCallState.CONNECTED, remoteUri = carol.render())
        advanceUntilIdle()
        assertEquals(
            1, engine.conferences.value.size,
            "the roster did not open a session on the member's leg, so this proves nothing",
        )

        // `carol` leaves. One peer is left, which is a call and not a conference.
        fixture.gateway.emitCall(toCarol.callKey, StackCallState.ENDED, remoteUri = carol.render(), statusCode = 200)
        advanceUntilIdle()

        assertTrue(
            engine.conferences.value.isEmpty(),
            "the surviving member is still in a conference: ${engine.conferences.value}",
        )
        // And the survivor is still a call. Retiring the conference must take the roster and
        // nothing else with it: `CallSnapshot.isConference` deliberately stays set, because the
        // call log records what happened, and the leg itself has to stay connected.
        val survivor = engine.activeCalls.value.single()
        assertEquals(bob.user, survivor.remote.user, "the wrong leg survived: ${survivor.remote.render()}")
        assertTrue(survivor.state.isEstablished, "retiring the conference ended the call: ${survivor.state}")
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

    // ------------------------------------------------- learning who can mesh

    @Test
    fun `a member that answers the roster is never carried`() = runTest {
        // The acknowledgement is the signal that survives the deployed B2BUA: a
        // handset-to-handset INVITE arrives stamped FreeSWITCH, so the far end's
        // User-Agent says nothing about it. A MESSAGE body crosses intact.
        val engine = twoConnected(coralx = emptySet())
        val legs = engine.activeCalls.value.map { it.callId }

        engine.mixCalls(legs.toSet())
        advanceUntilIdle()
        // Both unknown at first, so both are carried rather than left inaudible.
        assertEquals(legs.mapTo(mutableSetOf()) { it.value }, fixture.gateway.conferenceRelayed.last())

        legs.forEach { fixture.gateway.emitConference(it.value, meshAck = true) }
        advanceUntilIdle()

        assertEquals(
            emptySet(),
            fixture.gateway.conferenceRelayed.last(),
            "a conference whose members both answered should carry nobody",
        )
    }

    @Test
    fun `an acknowledgement carries no membership and must not empty the room`() = runTest {
        // The trap in reusing the roster document: an ack has no <users>, and feeding it
        // to the mapper would read as "everybody left".
        val engine = twoConnected()
        val legs = engine.activeCalls.value.map { it.callId }
        engine.mixCalls(legs.toSet())
        advanceUntilIdle()
        val before = engine.activeCalls.value.size

        fixture.gateway.emitConference(legs.first().value, meshAck = true)
        advanceUntilIdle()

        assertEquals(before, engine.activeCalls.value.size, "an ack changed the call list")
    }
}
