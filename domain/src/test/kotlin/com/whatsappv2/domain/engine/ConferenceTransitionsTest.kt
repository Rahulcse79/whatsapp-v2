package com.whatsappv2.domain.engine

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.SipUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The roster transitions Task 59 added to [ConferenceSession].
 *
 * `ConferenceSessionTest` in `EngineTypesTest.kt` covers the shape of the model — N
 * participants, `others`, the active speaker, the SFU case, and an absent roster being
 * distinguishable from an empty one. This covers what *moves*: joins, departures, mutes,
 * and the difference between a full-state roster and an incremental one.
 */
class ConferenceTransitionsTest {

    private val uri = requireNotNull(SipUri.parse("sip:3000@example.com").getOrNull())

    private val session = ConferenceSession(
        callId = CallId("call-1"),
        accountId = AccountId("acct-1"),
        conferenceUri = uri,
    )

    private fun participant(id: String, self: Boolean = false) = ConferenceParticipant(
        id = ParticipantId(id),
        uri = null,
        displayName = id,
        isSelf = self,
    )

    @Test
    fun `somebody joining makes the roster available`() {
        val joined = session.withParticipantJoined(participant("alice"))

        assertTrue(joined.rosterAvailable)
        assertEquals(1, joined.participantCount)
    }

    @Test
    fun `re-announcing somebody replaces them rather than duplicating them`() {
        val room = session
            .withParticipantJoined(participant("alice"))
            .withParticipantJoined(participant("alice"))

        assertEquals(1, room.participantCount)
    }

    @Test
    fun `leaving removes them but the bridge still publishes a roster`() {
        val room = session
            .withParticipantJoined(participant("alice"))
            .withParticipantLeft(ParticipantId("alice"))

        assertTrue(room.participants.isEmpty())
        // Still true: "we can see, and nobody is here" is a different fact from "we
        // cannot see", and Task 60's UI has to tell them apart.
        assertTrue(room.rosterAvailable)
        assertEquals(0, room.participantCount)
    }

    @Test
    fun `muting at the bridge marks only that participant`() {
        val room = session
            .withParticipantJoined(participant("alice"))
            .withParticipantJoined(participant("bob"))
            .withParticipantMuted(ParticipantId("alice"), muted = true)

        assertTrue(room.participants.single { it.id.value == "alice" }.isMuted)
        assertFalse(room.participants.single { it.id.value == "bob" }.isMuted)
    }

    @Test
    fun `an active speaker is exclusive, so no UI can highlight two`() {
        val room = session
            .withParticipantJoined(participant("alice"))
            .withParticipantJoined(participant("bob"))
            .withActiveSpeaker(ParticipantId("alice"))
            .withActiveSpeaker(ParticipantId("bob"))

        assertEquals(ParticipantId("bob"), room.activeSpeaker?.id)
        assertEquals(1, room.participants.count { it.isSpeaking })
    }

    @Test
    fun `nobody speaking is a state the bridge can report`() {
        val room = session
            .withParticipantJoined(participant("alice"))
            .withActiveSpeaker(ParticipantId("alice"))
            .withActiveSpeaker(null)

        assertNull(room.activeSpeaker)
    }

    @Test
    fun `a full-state roster removes anyone it does not mention`() {
        val room = session
            .withParticipantJoined(participant("alice"))
            .withParticipantJoined(participant("bob"))
            .withRoster(listOf(participant("carol")))

        // Bob left without a departure being announced, which is exactly the case a full
        // state exists to correct.
        assertEquals(listOf("carol"), room.participants.map { it.id.value })
    }

    @Test
    fun `a bridge with no roster is recorded as having none`() {
        val room = session.withParticipantJoined(participant("alice")).withoutRoster()

        assertFalse(room.rosterAvailable)
        assertTrue(room.participants.isEmpty())
        assertNull(room.participantCount)
    }

    @Test
    fun `a transition naming somebody absent changes nothing`() {
        val room = session
            .withParticipantJoined(participant("alice"))
            .withParticipantMuted(ParticipantId("nobody"), muted = true)

        assertFalse(room.participants.single().isMuted)
    }
}
