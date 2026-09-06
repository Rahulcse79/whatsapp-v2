package com.whatsappv2.domain.testing

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.engine.ConferenceParticipant
import com.whatsappv2.domain.engine.ParticipantId
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 59's second done-when, verbatim: participant join, leave and mute transitions,
 * driven through [FakeSipEngine].
 *
 * Through the fake rather than against [com.whatsappv2.domain.engine.ConferenceSession]
 * alone, because the task asks for the engine seam to be exercised — a model that composes
 * correctly but is never published on `conferences` is a model no screen can read.
 */
class FakeSipEngineConferenceTest {

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

    private val room = requireNotNull(SipUri.parse("sip:3000@sip.example.com").getOrNull())

    private fun participant(id: String, self: Boolean = false) = ConferenceParticipant(
        id = ParticipantId(id),
        uri = null,
        displayName = id.replaceFirstChar { it.uppercase() },
        isSelf = self,
    )

    private suspend fun FakeSipEngine.joined() =
        requireNotNull(joinConference(account.id, room, MediaProfile.AUDIO).getOrNull())

    @Test
    fun `participants join one at a time and the roster grows`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val callId = engine.joined()

        engine.simulateParticipantJoined(callId, participant("me", self = true))
        engine.simulateParticipantJoined(callId, participant("bob"))
        engine.simulateParticipantJoined(callId, participant("carol"))

        val session = engine.conferences.value.single()
        assertEquals(3, session.participantCount)
        // N > 2, with nothing dial-in-specific in the way (Task 59's first done-when).
        assertEquals(listOf("bob", "carol"), session.others.map { it.id.value })
    }

    @Test
    fun `a participant leaving is removed and the rest stay`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val callId = engine.joined()
        engine.simulateParticipantJoined(callId, participant("bob"))
        engine.simulateParticipantJoined(callId, participant("carol"))

        engine.simulateParticipantLeft(callId, ParticipantId("bob"))

        val session = engine.conferences.value.single()
        assertEquals(listOf("carol"), session.participants.map { it.id.value })
        // The bridge still publishes; it just has less to say.
        assertTrue(session.rosterAvailable)
    }

    @Test
    fun `the bridge muting somebody marks only them`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val callId = engine.joined()
        engine.simulateParticipantJoined(callId, participant("bob"))
        engine.simulateParticipantJoined(callId, participant("carol"))

        engine.simulateParticipantMuted(callId, ParticipantId("bob"), muted = true)

        val session = engine.conferences.value.single()
        assertTrue(session.participants.single { it.id.value == "bob" }.isMuted)
        assertFalse(session.participants.single { it.id.value == "carol" }.isMuted)

        engine.simulateParticipantMuted(callId, ParticipantId("bob"), muted = false)
        assertFalse(engine.conferences.value.single().participants.single { it.id.value == "bob" }.isMuted)
    }

    @Test
    fun `an active speaker is reported, and only one at a time`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val callId = engine.joined()
        engine.simulateParticipantJoined(callId, participant("bob"))
        engine.simulateParticipantJoined(callId, participant("carol"))

        engine.simulateActiveSpeaker(callId, ParticipantId("bob"))
        assertEquals("bob", engine.conferences.value.single().activeSpeaker?.id?.value)

        engine.simulateActiveSpeaker(callId, ParticipantId("carol"))
        val session = engine.conferences.value.single()
        assertEquals("carol", session.activeSpeaker?.id?.value)
        assertEquals(1, session.participants.count { it.isSpeaking })
    }

    @Test
    fun `a bridge that publishes no roster leaves the session saying so`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        engine.joined()

        val session = engine.conferences.value.single()
        assertFalse(session.rosterAvailable)
        // Not zero. "We cannot see" is not "nobody is here" (Task 60's third done-when).
        assertNull(session.participantCount)
    }

    @Test
    fun `every roster change is published, so a screen reading the flow sees each one`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val callId = engine.joined()

        // Read from the published StateFlow after each change rather than from a session
        // held on the side: a transition that composes correctly but never reaches
        // `conferences` is one no screen can see.
        val counts = buildList {
            add(engine.conferences.value.single().participantCount)

            engine.simulateParticipantJoined(callId, participant("bob"))
            add(engine.conferences.value.single().participantCount)

            engine.simulateParticipantJoined(callId, participant("carol"))
            add(engine.conferences.value.single().participantCount)

            engine.simulateParticipantLeft(callId, ParticipantId("bob"))
            add(engine.conferences.value.single().participantCount)
        }

        assertEquals(listOf(null, 1, 2, 1), counts)
    }

    @Test
    fun `hanging up the leg takes the conference with it`() = runTest {
        val engine = FakeSipEngine().givenRegistered(account)
        val callId = engine.joined()
        engine.simulateParticipantJoined(callId, participant("bob"))

        engine.simulateRemoteHangup(callId)

        assertTrue(engine.conferences.value.isEmpty())
    }
}
