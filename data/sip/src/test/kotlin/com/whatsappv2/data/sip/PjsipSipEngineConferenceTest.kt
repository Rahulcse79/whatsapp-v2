package com.whatsappv2.data.sip

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.data.sip.call.StackCallState
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.engine.ConferenceRoom
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Local mixing at the engine, where a resume actually takes time (ADR-009).
 *
 * `FakeSipEngine` resumes synchronously, so the ViewModel's merge tests cannot see the
 * defect these cover: a resume is a **re-INVITE**, and a call stays Held until the far end
 * answers, then passes through Resuming before media flows. The fake gateway here models
 * that faithfully — `resumeCall` emits RESUMING and leaves the rest to the test — which is
 * why this is the level that guards it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PjsipSipEngineConferenceTest {

    private val fixture = PjsipSipEngineTest()

    /** The bridge these merges go to, resolved as a dialled extension would be. */
    private val room: SipUri = requireNotNull(SipUri.parse(ROOM).getOrNull())

    /** Somebody added to a conference that is already running. */
    private val newcomer: SipUri = requireNotNull(SipUri.parse("sip:carol@sip.example.com").getOrNull())

    @Test
    fun `a bridge merge resumes, REFERs every leg to the room, and dials it`() = runTest {
        // The whole of the video path in one assertion set. Order matters and is asserted:
        // a leg REFERred while still held arrives at the bridge with its media stopped.
        val engine = with(fixture) { twoCallsOneHeld() }
        val ids = engine.activeCalls.value.map { it.callId }.toSet()

        val merging = async { engine.mergeIntoConference(ids, room) }
        runCurrent()

        // Nothing has been REFERred yet: the held leg is still resuming.
        assertTrue(
            fixture.gateway.blindTransfers.isEmpty(),
            "REFERred before the resume landed: ${fixture.gateway.blindTransfers}",
        )

        engine.activeCalls.value
            .filterNot { it.state is CallState.Connected }
            .forEach { fixture.gateway.emitCall(it.callId.value, StackCallState.STREAMS_RUNNING) }
        advanceUntilIdle()

        val joined = assertIs<Outcome.Success<CallId>>(merging.await()).value

        // Every merged leg was sent to the room, and only to the room.
        assertEquals(ids.map { it.value }.toSet(), fixture.gateway.blindTransfers.map { it.first }.toSet())
        assertTrue(fixture.gateway.blindTransfers.all { it.second == room.render() })

        // And this device followed them in, with video and marked as a conference.
        val ourLeg = engine.activeCalls.value.single { it.callId == joined }
        assertTrue(ourLeg.isConference)
        assertEquals(MediaProfile.AUDIO_VIDEO, ourLeg.media)
        assertEquals(room, engine.conferences.value.single { it.callId == joined }.conferenceUri)

        // The legs are left alone: the REFER travels in the dialog, and ending it here
        // would cut the transfer off before the peer had followed it.
        assertTrue(ids.all { id -> engine.activeCalls.value.any { it.callId == id } })
        engine.stop()
    }

    @Test
    fun `a bridge merge stamps every leg and the room leg with one key, and records who was sent`() = runTest {
        // The call log writes one row per leg. Without a shared key a bridged conference
        // reached history as two calls to strangers beside a call to "3000", and nothing
        // on the screen connected them.
        val engine = with(fixture) { twoCallsOneHeld() }
        val legs = engine.activeCalls.value.map { it.callId }.toSet()

        val merging = async { engine.mergeIntoConference(legs, room) }
        runCurrent()
        engine.activeCalls.value
            .filterNot { it.state is CallState.Connected }
            .forEach { fixture.gateway.emitCall(it.callId.value, StackCallState.STREAMS_RUNNING) }
        advanceUntilIdle()
        val joined = assertIs<Outcome.Success<CallId>>(merging.await()).value

        val calls = engine.activeCalls.value
        val key = assertNotNull(calls.single { it.callId == joined }.conferenceKey, "the room leg carries no key")
        assertTrue(calls.all { it.isConference && it.conferenceKey == key }, "one conference, one key: $calls")

        // And the room knows whom this device sent into it — the legs' addresses, which is
        // all the room will ever learn about them once their transfers complete.
        val session = engine.conferences.value.single { it.callId == joined }
        assertEquals(legs.map { it.value }.toSet(), session.invited.map { it.id.value }.toSet())
        assertTrue(session.invited.all { it.uri == PjsipSipEngineFixture.TARGET && !it.isSelf })
        // Still not a roster: the bridge has said nothing, and the count must stay unknown.
        assertFalse(session.rosterAvailable)
        assertEquals(null, session.participantCount)
        engine.stop()
    }

    @Test
    fun `merging a new leg while already in the room REFERs that leg alone and keeps the room leg`() = runTest {
        // Add, then Merge, from a bridged conference. The room leg was among the calls
        // being merged, and it used to be REFERred to the room it was already in while a
        // second call to the room was placed beside it.
        val engine = with(fixture) { twoCallsOneHeld() }
        val legs = engine.activeCalls.value.map { it.callId }.toSet()
        val merging = async { engine.mergeIntoConference(legs, room) }
        runCurrent()
        engine.activeCalls.value
            .filterNot { it.state is CallState.Connected }
            .forEach { fixture.gateway.emitCall(it.callId.value, StackCallState.STREAMS_RUNNING) }
        advanceUntilIdle()
        val roomLeg = assertIs<Outcome.Success<CallId>>(merging.await()).value
        fixture.gateway.emitCall(roomLeg.value, StackCallState.CONNECTED, remoteUri = room.render())
        legs.forEach { fixture.gateway.emitCall(it.value, StackCallState.ENDED) }
        runCurrent()
        val key = engine.activeCalls.value.single().conferenceKey

        // Telecom holds the conference while the new call is placed and answered.
        engine.setHold(roomLeg, held = true)
        fixture.gateway.emitCall(roomLeg.value, StackCallState.PAUSED, remoteUri = room.render())
        runCurrent()
        val third = engine.placeCall(fixture.account.id, newcomer, MediaProfile.AUDIO_VIDEO).getOrNull()!!
        runCurrent()
        fixture.gateway.emitCall(third.value, StackCallState.CONNECTED, remoteUri = newcomer.render())
        runCurrent()
        fixture.gateway.blindTransfers.clear()
        val placedBefore = fixture.gateway.placedCalls.size

        val adding = async { engine.mergeIntoConference(setOf(roomLeg, third), room) }
        runCurrent()
        // The room leg is resumed — a conference leg left on hold is a member the bridge
        // cannot hear — and nothing goes out until that resume has landed.
        assertTrue(fixture.gateway.blindTransfers.isEmpty(), "REFERred before the room leg resumed")
        engine.activeCalls.value
            .filterNot { it.state is CallState.Connected }
            .forEach { fixture.gateway.emitCall(it.callId.value, StackCallState.STREAMS_RUNNING, remoteUri = ROOM) }
        advanceUntilIdle()

        val followed = assertIs<Outcome.Success<CallId>>(adding.await()).value
        assertEquals(roomLeg, followed, "the leg to follow is the one already in the room")
        assertEquals(listOf(third.value to room.render()), fixture.gateway.blindTransfers, "only the new leg goes")
        assertEquals(placedBefore, fixture.gateway.placedCalls.size, "the room must not be dialled a second time")

        // The newcomer joins the conference the room leg is already in, under its key.
        assertEquals(key, engine.activeCalls.value.single { it.callId == third }.conferenceKey)
        val session = engine.conferences.value.single { it.callId == roomLeg }
        assertTrue(session.invited.any { it.uri == newcomer }, "the newcomer is missing from ${session.invited}")
        assertEquals(legs.size + 1, session.invited.size, "the members merged earlier must still be listed")
        engine.stop()
    }

    @Test
    fun `a REFER into the room ties the new leg to the leg it arrived on`() = runTest {
        // The transferee's half: it knows only who sent it. That is enough for its history
        // to read as one conference rather than a transferred call beside a call to 3000,
        // and for its screen to name the one member it does know.
        val engine = with(fixture) { connectedCall(room = ConferenceRoom("3000")) }
        val parent = engine.activeCalls.value.single()

        fixture.gateway.emitCall(
            callKey = "transferred-leg",
            state = StackCallState.OUTGOING_TRANSFERRED,
            remoteUri = room.render(),
            videoActive = true,
        )
        runCurrent()

        val created = engine.activeCalls.value.single { it.callId == CallId("transferred-leg") }
        val parentNow = engine.activeCalls.value.single { it.callId == parent.callId }
        val key = assertNotNull(created.conferenceKey, "the transferred leg carries no key")
        assertEquals(key, parentNow.conferenceKey, "the leg the REFER arrived on must share the key")
        assertTrue(created.isConference && parentNow.isConference)

        fixture.gateway.emitCall("transferred-leg", StackCallState.CONNECTED, remoteUri = room.render())
        runCurrent()
        val session = engine.conferences.value.single { it.callId == created.callId }
        assertEquals(listOf(parent.remote), session.invited.map { it.uri })
        assertFalse(session.rosterAvailable)
        engine.stop()
    }

    @Test
    fun `a bridge merge tears the local mix down before it transfers anybody`() = runTest {
        val engine = with(fixture) { twoCallsOneHeld() }
        val ids = engine.activeCalls.value.map { it.callId }.toSet()

        // Mixed here first, as an audio conference that then gains video would be. The
        // resume has to complete for the held leg to become a member, which is why this
        // goes through the same async dance as the test above rather than mixing directly.
        val mixing = async { engine.mixCalls(ids) }
        runCurrent()
        engine.activeCalls.value
            .filterNot { it.state is CallState.Connected }
            .forEach { fixture.gateway.emitCall(it.callId.value, StackCallState.STREAMS_RUNNING) }
        advanceUntilIdle()
        mixing.await()
        assertEquals(ids, engine.mixedCalls.value, "the local mix must exist before it can be torn down")

        engine.mergeIntoConference(ids, room)
        advanceUntilIdle()

        // A device that is both hosting and joining holds native ports open for legs that
        // are on their way out.
        assertEquals(emptySet(), fixture.gateway.conferenceMemberships.last())
        assertEquals(emptySet(), engine.mixedCalls.value)
        engine.stop()
    }

    @Test
    fun `a call the stack creates by following a REFER reaches the app`() = runTest {
        // Until OUTGOING_TRANSFERRED existed, every event for this call was dropped as
        // "unknown call": pjsua accepts a REFER and places the INVITE by itself, so
        // nothing above the stack had ever asked for it. The transferee's own screen
        // showed the leg it had just left while its media was already at the bridge.
        val engine = with(fixture) { connectedCall() }
        val before = engine.activeCalls.value.map { it.callId }.toSet()

        fixture.gateway.emitCall(
            callKey = "transferred-leg",
            state = StackCallState.OUTGOING_TRANSFERRED,
            remoteUri = room.render(),
            videoActive = true,
        )
        runCurrent()

        val created = engine.activeCalls.value.single { it.callId !in before }
        assertEquals(CallId("transferred-leg"), created.callId)
        assertEquals(CallDirection.OUTGOING, created.direction)
        assertIs<CallState.Outgoing.Calling>(created.state)
        // The media the stack carried over from the leg being replaced — a video call
        // transferred into the bridge must arrive at the bridge with video.
        assertEquals(MediaProfile.AUDIO_VIDEO, created.media)

        // And it then behaves like any other outgoing call.
        fixture.gateway.emitCall("transferred-leg", StackCallState.CONNECTED, remoteUri = room.render())
        runCurrent()
        assertIs<CallState.Connected>(engine.activeCalls.value.single { it.callId == created.callId }.state)
        engine.stop()
    }

    @Test
    fun `merging waits for a held call to finish resuming before it mixes`() = runTest {
        // The first run on hardware reported "Mixing 1 call(s)" and wired only
        // microphone-to-call links, never call-to-call, because the call list was read in
        // the same breath as the resume was issued.
        val engine = with(fixture) { twoCallsOneHeld() }
        val ids = engine.activeCalls.value.map { it.callId }.toSet()

        val merging = async { engine.mixCalls(ids) }
        runCurrent()

        assertTrue(
            fixture.gateway.conferenceMemberships.isEmpty(),
            "mixed before the resume landed: ${fixture.gateway.conferenceMemberships}",
        )

        // The far end answers the re-INVITE and media runs again.
        engine.activeCalls.value
            .filterNot { it.state is CallState.Connected }
            .forEach { fixture.gateway.emitCall(it.callId.value, StackCallState.STREAMS_RUNNING) }
        advanceUntilIdle()

        val mixed = assertIs<Outcome.Success<Set<CallId>>>(merging.await())
        assertEquals(ids, mixed.value, "both calls should be mixed once media is running")
        assertEquals(ids.mapTo(mutableSetOf()) { it.value }, fixture.gateway.conferenceMemberships.last())
        engine.stop()
    }

    @Test
    fun `merging audio-only calls renegotiates nothing`() = runTest {
        // It used to re-INVITE every member down to audio unconditionally — ADR-009's
        // gate, applied whether or not the leg had a camera. On an audio conference that
        // is one pointless re-INVITE per member, and a re-INVITE rebuilds the call's
        // media ports, which is the very thing the conference bridges then have to
        // recover from. Video is dropped on the legs that have video and cannot be in
        // the picture, and on no others.
        val engine = with(fixture) { twoCallsOneHeld() }
        val ids = engine.activeCalls.value.map { it.callId }.toSet()
        fixture.gateway.videoRequests.clear()

        val merging = async { engine.mixCalls(ids) }
        runCurrent()
        engine.activeCalls.value
            .filterNot { it.state is CallState.Connected }
            .forEach { fixture.gateway.emitCall(it.callId.value, StackCallState.STREAMS_RUNNING) }
        advanceUntilIdle()
        assertIs<Outcome.Success<Set<CallId>>>(merging.await())

        assertTrue(
            fixture.gateway.videoRequests.isEmpty(),
            "no leg had video, so none may be renegotiated: ${fixture.gateway.videoRequests}",
        )
        engine.stop()
    }

    @Test
    fun `merging legs that carry video composes the picture here and leaves their video up`() = runTest {
        // The other half of the same rule, and the regression that cost a live
        // conference on 2026-09-24. Legs carrying video keep it and become the picture;
        // `pjmedia`'s video bridge composes a canvas per peer, so nothing is REFERred
        // anywhere and no video is torn down to make a conference possible.
        val engine = with(fixture) { twoCallsOneHeld() }
        val ids = engine.activeCalls.value.map { it.callId }.toSet()
        // The leg that is already connected reports its video; the held one reports its
        // own as it comes back, which is the resume the merge itself asks for.
        engine.activeCalls.value
            .filter { it.state is CallState.Connected }
            .forEach { fixture.gateway.emitCall(it.callId.value, StackCallState.STREAMS_RUNNING, videoActive = true) }
        runCurrent()
        fixture.gateway.videoRequests.clear()
        fixture.gateway.videoConferenceMemberships.clear()

        val merging = async { engine.mixCalls(ids) }
        runCurrent()
        engine.activeCalls.value
            .filterNot { it.state is CallState.Connected }
            .forEach { fixture.gateway.emitCall(it.callId.value, StackCallState.STREAMS_RUNNING, videoActive = true) }
        advanceUntilIdle()
        assertIs<Outcome.Success<Set<CallId>>>(merging.await())

        assertEquals(
            ids.map { it.value }.toSet(),
            fixture.gateway.videoConferenceMemberships.last(),
            "every leg carrying video is in the picture",
        )
        assertTrue(
            fixture.gateway.videoRequests.none { !it.second },
            "and none of them is renegotiated down to audio: ${fixture.gateway.videoRequests}",
        )
        assertTrue(fixture.gateway.blindTransfers.isEmpty(), "no leg was sent to a conference room")
        engine.stop()
    }

    @Test
    fun `the mixed set is published while the conference lasts, and empties when it is a call again`() = runTest {
        // What keeps Telecom from breaking the mix: the platform holds every other active
        // call the moment one goes active, and the bridge that carries its holds reads
        // this set to decline them for a member (ADR-009's "RX 0pkt on two legs").
        val engine = with(fixture) { twoCallsOneHeld() }
        val ids = engine.activeCalls.value.map { it.callId }.toSet()
        assertEquals(emptySet(), engine.mixedCalls.value)

        val merging = async { engine.mixCalls(ids) }
        runCurrent()
        engine.activeCalls.value
            .filterNot { it.state is CallState.Connected }
            .forEach { fixture.gateway.emitCall(it.callId.value, StackCallState.STREAMS_RUNNING) }
        advanceUntilIdle()
        merging.await()

        assertEquals(ids, engine.mixedCalls.value)

        // One member hangs up. One left is a call, not a conference, so the set empties
        // rather than naming a lone member the bridge would then refuse to hold.
        fixture.gateway.emitCall(ids.first().value, StackCallState.ENDED)
        advanceUntilIdle()

        assertEquals(emptySet(), engine.mixedCalls.value)
        engine.stop()
    }

    @Test
    fun `a mixed call is stamped as a conference, and stays stamped after it ends`() = runTest {
        // The history defect: the call log is written from the TERMINAL snapshot, which
        // arrives long after `mixedCalls` has emptied. A flag that lived only in that set
        // was gone by the time anybody wrote it down, so a merged three-way reached
        // history as two unrelated calls to two people (TC15, 2026-09-15).
        val engine = with(fixture) { twoCallsOneHeld() }
        val ids = engine.activeCalls.value.map { it.callId }.toSet()
        assertTrue(engine.activeCalls.value.none { it.isConference }, "a call is not a conference")

        val merging = async { engine.mixCalls(ids) }
        runCurrent()
        engine.activeCalls.value
            .filterNot { it.state is CallState.Connected }
            .forEach { fixture.gateway.emitCall(it.callId.value, StackCallState.STREAMS_RUNNING) }
        advanceUntilIdle()
        merging.await()

        assertTrue(engine.activeCalls.value.all { it.isConference }, "every mixed leg is marked")

        // The ending is what the log reads, and it must still say so — including for the
        // leg that leaves first, which was in the conference for as long as it lasted.
        val ending = async { engine.endedCalls.first() }
        runCurrent()
        fixture.gateway.emitCall(ids.first().value, StackCallState.ENDED)
        advanceUntilIdle()

        assertTrue(ending.await().isConference, "the terminal snapshot still carries it")
        assertEquals(emptySet(), engine.mixedCalls.value, "and the live set has already emptied")
        engine.stop()
    }

    @Test
    fun `mixing more than eight is refused here, not by a native error later`() = runTest {
        // PJSUA_MAX_CALLS refusing the ninth call is a native failure at the wrong moment;
        // the ceiling is a number the user can be told about (ADR-009).
        val engine = with(fixture) { twoCallsOneHeld() }
        val tooMany = (1..9).mapTo(mutableSetOf()) { CallId("call-$it") }

        val result = engine.mixCalls(tooMany)

        assertIs<Outcome.Failure<*>>(result)
        assertTrue(fixture.gateway.conferenceMemberships.isEmpty(), "the stack was asked anyway")
        engine.stop()
    }

    // ------------------------------------------------ the device's own controls (2026-09-19)

    @Test
    fun `muting a conference mutes every member, because the device has one microphone`() = runTest {
        // The defect this exists for, measured on a TC15 against a seven-member mix: one
        // tap on Mute produced exactly one `Port 0 (Android JNI) stop transmitting to
        // port 6` in pjmedia's own log, and the other six participants went on hearing
        // the room. `pjmedia_conf` gives every member its own link from the capture port,
        // so a mute addressed to the leg the screen happens to be watching is a mute of
        // one seventh of the microphone.
        val engine = mixedConferenceOfThree()
        val members = engine.mixedCalls.value
        assertEquals(3, members.size, "the fixture must build a real conference")
        fixture.gateway.mutedCalls.clear()

        assertIs<Outcome.Success<Unit>>(engine.setMuted(members.first(), muted = true))
        advanceUntilIdle()

        assertEquals(
            members.associate { it.value to true },
            fixture.gateway.mutedCalls,
            "every member's link from the capture device must be closed",
        )
        assertTrue(
            engine.activeCalls.value.all { it.state.controlsOrNull?.isMuted == true },
            "and every member's own state must say so, or the screen and the bridge disagree",
        )
        engine.stop()
    }

    @Test
    fun `unmuting a conference repairs members that were already muted when they joined`() = runTest {
        // The reported symptom, in its exact shape: the user is muted, presses unmute, and
        // stays inaudible to everyone but one participant. A single early return on the
        // watched call's state — the idempotence shortcut this used to take for the whole
        // request — makes the repair a no-op precisely when a conference needs it most.
        val engine = with(fixture) { connectedCall() }
        val first = engine.activeCalls.value.single().callId
        engine.setMuted(first, muted = true)
        runCurrent()

        val members = joinTwoMore(engine)
        engine.mixCalls(members)
        advanceUntilIdle()
        fixture.gateway.mutedCalls.clear()

        // Asked of a member that is *not* the muted one, which is what the screen does:
        // the button reads the watched leg, and the watched leg may be live already.
        val watched = members.first { it != first }
        assertIs<Outcome.Success<Unit>>(engine.setMuted(watched, muted = false))
        advanceUntilIdle()

        assertEquals(
            false,
            fixture.gateway.mutedCalls[first.value],
            "the member that was muted must have its link from the capture device reopened",
        )
        assertTrue(
            engine.activeCalls.value.none { it.state.controlsOrNull?.isMuted == true },
            "and no member may be left muted behind an unmuted button",
        )
        // Idempotence is per call and stays per call: a member that was never muted is
        // not asked again, so this cannot become a storm of `pjmedia_conf` calls on every
        // press in a conference of eight.
        assertEquals(
            setOf(first.value),
            fixture.gateway.mutedCalls.keys,
            "only the member whose state actually moved should reach the stack",
        )
        engine.stop()
    }

    @Test
    fun `holding a conference holds every member, not only the leg on screen`() = runTest {
        // One Hold button sits under the title "Conference call", so it is the conference
        // that is being held. Holding the watched leg alone left the others mixed and
        // live: the user had stepped out of a room that could still hear them.
        val engine = mixedConferenceOfThree()
        val members = engine.mixedCalls.value
        fixture.gateway.holdRequests.clear()

        assertIs<Outcome.Success<Unit>>(engine.setHold(members.first(), held = true))
        advanceUntilIdle()

        assertEquals(
            members.mapTo(mutableSetOf()) { it.value to true },
            fixture.gateway.holdRequests.toSet(),
            "every member must be asked to hold",
        )
        engine.stop()
    }

    @Test
    fun `a merge resumes exactly the held legs, and asks nothing of the live ones`() = runTest {
        // The fan-out is for the user's controls, not the engine's own. `mixCalls`
        // publishes the membership and then resumes each held member; had those resumes
        // gone through the fanned-out `setHold`, every one of them would have been
        // re-issued across the members that were never held — rejected by the FSM, but
        // a warning per live member per merge, and a log in which the real refusal is
        // one line among many.
        val engine = with(fixture) { twoCallsOneHeld() }
        val ids = engine.activeCalls.value.map { it.callId }.toSet()
        val held = engine.activeCalls.value.single { it.state is CallState.Held }.callId
        fixture.gateway.holdRequests.clear()

        val merging = async { engine.mixCalls(ids) }
        runCurrent()
        fixture.gateway.emitCall(held.value, StackCallState.STREAMS_RUNNING)
        advanceUntilIdle()

        assertIs<Outcome.Success<Set<CallId>>>(merging.await())
        assertEquals(
            listOf(held.value to false),
            fixture.gateway.holdRequests,
            "one resume, for the one member that was held",
        )
        engine.stop()
    }

    @Test
    fun `hanging up a conference ends every member, because one End button is what the screen offers`() = runTest {
        // Pressing End under "Conference call" ended the leg the screen was watching and
        // left the user in a conference of the other six (TC15, 2026-09-19).
        val engine = mixedConferenceOfThree()
        val members = engine.mixedCalls.value
        fixture.gateway.terminatedCalls.clear()

        assertIs<Outcome.Success<Unit>>(engine.hangup(members.first(), HangupReason.LOCAL_HANGUP))
        advanceUntilIdle()

        assertEquals(members.mapTo(mutableSetOf()) { it.value }, fixture.gateway.terminatedCalls.toSet())
        assertTrue(engine.activeCalls.value.isEmpty(), "no leg may outlive the conference the user ended")
        assertEquals(emptySet(), engine.mixedCalls.value)
        engine.stop()
    }

    @Test
    fun `a member leaving does not end the conference`() = runTest {
        // The other direction, which must stay per leg: the far end's BYE is that member
        // going, and the rest of the room keeps talking.
        val engine = mixedConferenceOfThree()
        val members = engine.mixedCalls.value
        fixture.gateway.terminatedCalls.clear()

        fixture.gateway.emitCall(members.first().value, StackCallState.ENDED)
        advanceUntilIdle()

        assertTrue(fixture.gateway.terminatedCalls.isEmpty(), "nobody else was hung up")
        assertEquals(members - members.first(), engine.mixedCalls.value, "the other two are still a conference")
        engine.stop()
    }

    @Test
    fun `every leg of one conference carries one key, and a leg added later carries the same one`() = runTest {
        // The history screen groups legs by this key, so a six-way call reads as one
        // conference rather than as six rows to six people. A member added after the
        // mix formed joins *that* conference, not a new one.
        val engine = mixedConferenceOfThree()
        val keys = engine.activeCalls.value.map { it.conferenceKey }.toSet()
        assertEquals(1, keys.size, "one conference, one key: $keys")
        val key = keys.single()
        assertTrue(!key.isNullOrBlank(), "the key must be something the log can store")

        val late = engine.placeCall(fixture.account.id, PjsipSipEngineFixture.TARGET, MediaProfile.AUDIO)
            .getOrNull()!!
        runCurrent()
        fixture.gateway.emitCall(late.value, StackCallState.CONNECTED)
        runCurrent()
        engine.mixCalls(engine.activeCalls.value.mapTo(mutableSetOf()) { it.callId })
        advanceUntilIdle()

        assertEquals(key, engine.activeCalls.value.single { it.callId == late }.conferenceKey)
        engine.stop()
    }

    @Test
    fun `a second conference gets a key of its own`() = runTest {
        // Two conferences an hour apart must not be read back as one, however alike.
        val engine = mixedConferenceOfThree()
        val first = engine.activeCalls.value.first().conferenceKey
        engine.hangup(engine.mixedCalls.value.first(), HangupReason.LOCAL_HANGUP)
        advanceUntilIdle()
        assertTrue(engine.activeCalls.value.isEmpty(), "the first conference has ended")

        val again = engine.placeCall(fixture.account.id, PjsipSipEngineFixture.TARGET, MediaProfile.AUDIO)
            .getOrNull()!!
        runCurrent()
        fixture.gateway.emitCall(again.value, StackCallState.CONNECTED)
        runCurrent()
        engine.mixCalls(joinTwoMore(engine))
        advanceUntilIdle()

        val second = engine.activeCalls.value.map { it.conferenceKey }.toSet().single()
        assertTrue(second != first, "a new conference must not reuse the old key")
        engine.stop()
    }

    @Test
    fun `a call that is not in a conference is still muted by itself`() = runTest {
        // The fan-out is the membership's doing and nothing else's. A second call on hold
        // beside a live one is not a conference, and muting one must not silence the other.
        val engine = with(fixture) { twoCallsOneHeld() }
        val connected = engine.activeCalls.value.first { it.state is CallState.Connected }.callId
        assertEquals(emptySet(), engine.mixedCalls.value, "these calls are not mixed")
        fixture.gateway.mutedCalls.clear()

        engine.setMuted(connected, muted = true)
        advanceUntilIdle()

        assertEquals(mapOf(connected.value to true), fixture.gateway.mutedCalls)
        engine.stop()
    }

    /** Three connected calls, mixed — the smallest conference that can show a partial fan-out. */
    private suspend fun TestScope.mixedConferenceOfThree(): PjsipSipEngine {
        val engine = with(fixture) { connectedCall() }
        engine.mixCalls(joinTwoMore(engine))
        advanceUntilIdle()
        return engine
    }

    /**
     * Two further connected calls, and the whole membership afterwards.
     *
     * Every leg is already `Connected`, so `mixCalls` has nothing to resume and the merge
     * lands without the re-INVITE dance the tests above exist to model. Deliberate: these
     * tests are about a control reaching every member, and a resume in the middle would
     * only make a partial fan-out harder to read.
     */
    private suspend fun TestScope.joinTwoMore(engine: PjsipSipEngine): Set<CallId> {
        repeat(2) {
            val id = engine.placeCall(fixture.account.id, PjsipSipEngineFixture.TARGET, MediaProfile.AUDIO)
                .getOrNull()!!
            runCurrent()
            fixture.gateway.emitCall(id.value, StackCallState.CONNECTED)
            runCurrent()
        }
        return engine.activeCalls.value.mapTo(mutableSetOf()) { it.callId }
    }

    private companion object {
        const val ROOM = "sip:3000@sip.example.com"
    }
}
