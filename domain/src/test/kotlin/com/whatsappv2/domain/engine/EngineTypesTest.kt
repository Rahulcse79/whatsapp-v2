package com.whatsappv2.domain.engine

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.call.CallControls
import com.whatsappv2.domain.call.CallState
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ALICE = requireNotNull(SipUri.parse("sip:alice@example.com").getOrNull())
private val ROOM = requireNotNull(SipUri.parse("sip:3000@conf.example.com").getOrNull())

class CallSnapshotTest {

    private fun snapshot(connectedAt: Long? = null) = CallSnapshot(
        callId = CallId("call-1"),
        accountId = AccountId("acct-1"),
        remote = ALICE,
        remoteDisplayName = "Alice",
        direction = CallDirection.OUTGOING,
        state = CallState.Connected(CallControls.DEFAULT),
        media = MediaProfile.AUDIO,
        startedAtEpochMillis = 1_000L,
        connectedAtEpochMillis = connectedAt,
    )

    @Test
    fun `duration is null until the call connects`() {
        assertNull(snapshot().durationMillis(nowEpochMillis = 5_000L))
    }

    @Test
    fun `duration counts from the moment media started, not from dialling`() {
        assertEquals(3_000L, snapshot(connectedAt = 2_000L).durationMillis(nowEpochMillis = 5_000L))
    }

    @Test
    fun `duration never goes negative when clocks disagree`() {
        // Wall-clock time can move backwards; a negative call duration on screen is worse
        // than a zero.
        assertEquals(0L, snapshot(connectedAt = 5_000L).durationMillis(nowEpochMillis = 1_000L))
    }

    @Test
    fun `wasAnswered reflects whether media ever flowed`() {
        assertFalse(snapshot().wasAnswered)
        assertTrue(snapshot(connectedAt = 2_000L).wasAnswered)
    }

    @Test
    fun `toString redacts the remote party`() {
        val text = snapshot().toString()
        assertFalse("alice" in text, "toString leaked the remote user part: $text")
        assertTrue("example.com" in text, "the host is diagnostic and should survive")
    }
}

class IncomingCallTest {

    private val incoming = IncomingCall(
        callId = CallId("call-1"),
        accountId = AccountId("acct-1"),
        from = ALICE,
        fromDisplayName = "Alice",
        offeredMedia = MediaProfile.AUDIO_VIDEO,
        receivedAtEpochMillis = 1_000L,
        viaPush = true,
    )

    @Test
    fun `toString redacts the caller`() {
        assertFalse("alice" in incoming.toString())
    }

    @Test
    fun `the push origin is recorded so the wake path can be diagnosed`() {
        assertTrue(incoming.viaPush)
        assertFalse(incoming.copy(viaPush = false).viaPush)
    }

    @Test
    fun `an offered video call is visible so the UI can offer a video answer`() {
        assertTrue(incoming.offeredMedia.hasVideo)
    }
}

class ConferenceSessionTest {

    private fun participant(
        id: String,
        isSelf: Boolean = false,
        isSpeaking: Boolean = false,
        hasVideo: Boolean = false,
    ) = ConferenceParticipant(
        id = ParticipantId(id),
        uri = null,
        displayName = id,
        isSelf = isSelf,
        isSpeaking = isSpeaking,
        hasVideoStream = hasVideo,
    )

    private fun session(
        participants: List<ConferenceParticipant> = emptyList(),
        rosterAvailable: Boolean = true,
    ) = ConferenceSession(
        callId = CallId("call-1"),
        accountId = AccountId("acct-1"),
        conferenceUri = ROOM,
        participants = participants,
        rosterAvailable = rosterAvailable,
    )

    @Test
    fun `an absent roster is distinguishable from an empty one`() {
        // Task 60: the UI must say "roster unavailable" rather than render a fabricated
        // list or imply nobody has joined.
        assertFalse(session(rosterAvailable = false).rosterAvailable)
        assertTrue(session(rosterAvailable = true).participants.isEmpty())
        assertTrue(session(rosterAvailable = true).rosterAvailable)
    }

    @Test
    fun `others excludes the local user`() {
        val roster = listOf(participant("me", isSelf = true), participant("bob"), participant("carol"))
        assertEquals(listOf("bob", "carol"), session(roster).others.map { it.displayName })
    }

    @Test
    fun `the active speaker is reported when the bridge names one`() {
        val roster = listOf(participant("bob"), participant("carol", isSpeaking = true))
        assertEquals("carol", session(roster).activeSpeaker?.displayName)
        assertNull(session(listOf(participant("bob"))).activeSpeaker)
    }

    @Test
    fun `per-participant video distinguishes an SFU from a mixing MCU`() {
        // §2.2: the domain is shaped so moving to an SFU is an implementation swap.
        // Under the dial-in MCU (ADR-003) one composed stream carries everyone.
        assertFalse(session(listOf(participant("bob"))).hasPerParticipantVideo)
        assertTrue(session(listOf(participant("bob", hasVideo = true))).hasPerParticipantVideo)
    }

    @Test
    fun `the model supports more than two participants without assuming a mixer`() {
        val roster = (1..8).map { participant("p$it") }
        assertEquals(8, session(roster).participants.size)
    }

    @Test
    fun `a participant id must not be blank`() {
        assertFailsWith<IllegalArgumentException> { ParticipantId("") }
        assertFailsWith<IllegalArgumentException> { ParticipantId("  ") }
        assertEquals("p1", ParticipantId("p1").toString())
    }
}

class PushTokenTest {

    private val token = PushToken(provider = "fcm", param = "sender-123", prid = "abcdefghijkl")

    @Test
    fun `toString redacts all but the tail of the device token`() {
        val text = token.toString()
        assertFalse("abcdefghijkl" in text, "toString leaked the full token: $text")
        assertTrue("ijkl" in text, "a tail is kept so two log lines can be correlated")
        assertTrue("fcm" in text)
    }

    @Test
    fun `a blank provider or token is rejected`() {
        assertFailsWith<IllegalArgumentException> { PushToken("", "s", "t") }
        assertFailsWith<IllegalArgumentException> { PushToken("fcm", "s", "  ") }
    }
}
