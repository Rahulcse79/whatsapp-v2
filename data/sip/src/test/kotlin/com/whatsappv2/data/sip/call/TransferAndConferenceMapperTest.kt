package com.whatsappv2.data.sip.call

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.engine.ConferenceSession
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.TransferEvent
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.TransferType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two mappers Tasks 55 and 60 added.
 *
 * Both are pure, and both hold a decision that is easy to get wrong and impossible to spot
 * afterwards: that a REFER's 202 is not success, and that an empty roster is not the same
 * as no roster.
 */
class TransferAndConferenceMapperTest {

    private val callId = CallId("call-1")

    private fun transfer(state: StackCallState, statusCode: Int? = null) =
        StackTransferEvent(callId.value, state, statusCode)

    // ================================================================ transfer

    @Test
    fun `the REFER being accepted is reported as accepted, never as success`() {
        // The classic transfer bug: "transferred" on screen, transferor hangs up, caller
        // is dropped into a dead call.
        val event = TransferEventMapper.toDomain(transfer(StackCallState.OUTGOING_INIT), TransferType.BLIND)

        assertEquals(TransferEvent.Accepted(callId, TransferType.BLIND), event)
    }

    @Test
    fun `the transfer type is carried through, because the two read differently`() {
        val event = TransferEventMapper.toDomain(
            transfer(StackCallState.OUTGOING_INIT),
            TransferType.ATTENDED,
        )

        assertEquals(TransferEvent.Accepted(callId, TransferType.ATTENDED), event)
    }

    @Test
    fun `sipfrag progress carries its response code`() {
        listOf(
            StackCallState.OUTGOING_PROGRESS,
            StackCallState.OUTGOING_RINGING,
            StackCallState.OUTGOING_EARLY_MEDIA,
        ).forEach { state ->
            assertEquals(
                TransferEvent.Progressing(callId, 180),
                TransferEventMapper.toDomain(transfer(state, statusCode = 180), TransferType.BLIND),
                "expected $state to be progress",
            )
        }
    }

    @Test
    fun `only the transferee answering counts as success`() {
        assertEquals(
            TransferEvent.Succeeded(callId),
            TransferEventMapper.toDomain(transfer(StackCallState.CONNECTED), TransferType.BLIND),
        )
        assertEquals(
            TransferEvent.Succeeded(callId),
            TransferEventMapper.toDomain(transfer(StackCallState.STREAMS_RUNNING), TransferType.BLIND),
        )
    }

    @Test
    fun `a busy transferee is named as busy, not as a generic failure`() {
        // Task 55's second done-when: the caller is still on the line and can be told
        // something they can act on.
        val event = TransferEventMapper.toDomain(
            transfer(StackCallState.ERROR, statusCode = 486),
            TransferType.BLIND,
        )

        assertIs<SipError.Busy>(assertIs<TransferEvent.Failed>(event).cause)
    }

    @Test
    fun `a transfer that ends without connecting is a failure, not silence`() {
        val event = TransferEventMapper.toDomain(transfer(StackCallState.ENDED), TransferType.BLIND)

        assertIs<TransferEvent.Failed>(event)
    }

    @Test
    fun `a failure with no code at all is a transport problem, not a refusal`() {
        val event = TransferEventMapper.toDomain(transfer(StackCallState.ERROR), TransferType.BLIND)

        assertIs<SipError.TransportFailure>(assertIs<TransferEvent.Failed>(event).cause)
    }

    @Test
    fun `states a transfer cannot be in map to nothing`() {
        listOf(
            StackCallState.INCOMING_RECEIVED,
            StackCallState.PAUSED,
            StackCallState.PAUSED_BY_REMOTE,
            StackCallState.RESUMING,
            StackCallState.UPDATED_BY_REMOTE,
            StackCallState.REFERRED,
        ).forEach { state ->
            assertNull(
                TransferEventMapper.toDomain(transfer(state), TransferType.BLIND),
                "expected $state to carry no transfer news",
            )
        }
    }

    // ================================================================ conference

    private val session = ConferenceSession(
        callId = callId,
        accountId = AccountId("acct-1"),
        conferenceUri = requireNotNull(SipUri.parse("sip:3000@example.com").getOrNull()),
    )

    private fun stackParticipant(
        id: String,
        uri: String? = "sip:$id@example.com",
        self: Boolean = false,
        video: Boolean = false,
    ) = StackParticipant(
        id = id,
        uri = uri,
        displayName = id,
        isMuted = false,
        isSpeaking = false,
        isSelf = self,
        hasVideoStream = video,
        joinedAtEpochMillis = null,
    )

    @Test
    fun `a roster is applied in full and marks the session as having one`() {
        val applied = ConferenceMapper.apply(
            session,
            StackConferenceEvent(callId.value, listOf(stackParticipant("bob")), rosterAvailable = true),
        )

        assertTrue(applied.rosterAvailable)
        assertEquals(1, applied.participantCount)
    }

    @Test
    fun `a bridge with no roster leaves the session saying so`() {
        val withRoster = ConferenceMapper.apply(
            session,
            StackConferenceEvent(callId.value, listOf(stackParticipant("bob")), rosterAvailable = true),
        )

        val without = ConferenceMapper.apply(
            withRoster,
            StackConferenceEvent(callId.value, emptyList(), rosterAvailable = false),
        )

        assertFalse(without.rosterAvailable)
        assertNull(without.participantCount)
    }

    @Test
    fun `an empty roster that is published is empty, not absent`() {
        val applied = ConferenceMapper.apply(
            session,
            StackConferenceEvent(callId.value, emptyList(), rosterAvailable = true),
        )

        // The distinction Task 60's third done-when turns on.
        assertTrue(applied.rosterAvailable)
        assertEquals(0, applied.participantCount)
    }

    @Test
    fun `a participant whose address will not parse is kept, without one`() {
        val applied = ConferenceMapper.apply(
            session,
            StackConferenceEvent(
                callId.value,
                listOf(stackParticipant("mystery", uri = "not a uri")),
                rosterAvailable = true,
            ),
        )

        // Still a voice in the room. Dropping them would show a count lower than the
        // number of people who can be heard.
        assertEquals(1, applied.participantCount)
        assertNull(applied.participants.single().uri)
    }

    @Test
    fun `a participant with no address at all is kept`() {
        val applied = ConferenceMapper.apply(
            session,
            StackConferenceEvent(
                callId.value,
                listOf(stackParticipant("anon", uri = null)),
                rosterAvailable = true,
            ),
        )

        assertEquals(1, applied.participantCount)
        assertNull(applied.participants.single().uri)
    }

    @Test
    fun `a participant the bridge cannot identify at all is dropped`() {
        val applied = ConferenceMapper.apply(
            session,
            StackConferenceEvent(
                callId.value,
                listOf(stackParticipant("bob"), stackParticipant("", uri = null)),
                rosterAvailable = true,
            ),
        )

        // Not given a made-up id: one that changes on every roster is a participant who
        // appears to leave and rejoin continuously. `ParticipantId` refuses blank anyway.
        assertEquals(listOf("bob"), applied.participants.map { it.id.value })
    }

    @Test
    fun `the local participant is marked as self and excluded from others`() {
        val applied = ConferenceMapper.apply(
            session,
            StackConferenceEvent(
                callId.value,
                listOf(stackParticipant("me", self = true), stackParticipant("bob")),
                rosterAvailable = true,
            ),
        )

        assertEquals(listOf("bob"), applied.others.map { it.id.value })
    }

    @Test
    fun `per-participant video survives the mapping, which is the SFU case`() {
        val applied = ConferenceMapper.apply(
            session,
            StackConferenceEvent(
                callId.value,
                listOf(stackParticipant("bob", video = true)),
                rosterAvailable = true,
            ),
        )

        assertTrue(applied.hasPerParticipantVideo)
    }

    @Test
    fun `a blank display name is dropped rather than shown as an empty row`() {
        val applied = ConferenceMapper.apply(
            session,
            StackConferenceEvent(
                callId.value,
                listOf(stackParticipant("bob").copy(displayName = "  ")),
                rosterAvailable = true,
            ),
        )

        assertNull(applied.participants.single().displayName)
    }
}
