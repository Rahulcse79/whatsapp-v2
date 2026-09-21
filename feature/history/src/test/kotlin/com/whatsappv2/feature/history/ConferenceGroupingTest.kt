package com.whatsappv2.feature.history

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The legs of one conference fold into one entry (ADR-009).
 *
 * The page is newest first, as the log lists it; a conference's legs start over a few
 * minutes, and a call to somebody else can land between them.
 */
class ConferenceGroupingTest {

    @Test
    fun `the legs of a conference become one row, anchored where the conference began`() {
        // Newest first: the leg to 1003 was dialled last, 1001 first.
        val page = listOf(
            leg(id = 3, user = "1003", startedAt = 300, key = "k1"),
            leg(id = 2, user = "1002", startedAt = 200, key = "k1"),
            leg(id = 1, user = "1001", startedAt = 100, key = "k1"),
        )

        val rows = groupConferences(page)

        val conference = rows.single()
        assertTrue(conference.isConferenceGroup)
        assertEquals(1L, conference.entry.id.value, "the entry is the first leg, so the row sits where it started")
        assertEquals(listOf("1001", "1002", "1003"), conference.legs.map { it.title }, "members oldest first")
        assertEquals("1001, 1002, 1003", conference.title)
        assertEquals(100L, conference.startedAtEpochMillis)
    }

    @Test
    fun `a call to somebody else between the legs keeps its own row and its place`() {
        val page = listOf(
            leg(id = 3, user = "1003", startedAt = 300, key = "k1"),
            leg(id = 9, user = "9196", startedAt = 250, key = null),
            leg(id = 1, user = "1001", startedAt = 100, key = "k1"),
        )

        val rows = groupConferences(page)

        assertEquals(listOf(9L, 1L), rows.map { it.entry.id.value })
        assertFalse(rows[0].isConferenceGroup)
        assertTrue(rows[1].isConferenceGroup)
    }

    @Test
    fun `two conferences with different keys stay two rows`() {
        val page = listOf(
            leg(id = 4, user = "1002", startedAt = 400, key = "k2"),
            leg(id = 3, user = "1001", startedAt = 300, key = "k2"),
            leg(id = 2, user = "1002", startedAt = 200, key = "k1"),
            leg(id = 1, user = "1001", startedAt = 100, key = "k1"),
        )

        val rows = groupConferences(page)

        assertEquals(listOf(3L, 1L), rows.map { it.entry.id.value })
        assertTrue(rows.all { it.isConferenceGroup && it.legs.size == 2 })
    }

    @Test
    fun `a leg with no key, or alone on the page, is left exactly as it was`() {
        // Rows written before the column existed, and a conference whose other legs are
        // on the next page: neither is guessed at.
        val alone = leg(id = 1, user = "1001", startedAt = 100, key = "k1")
        val old = leg(id = 2, user = "1002", startedAt = 200, key = null)
        val page = listOf(old, alone)

        val rows = groupConferences(page)

        assertSame(old, rows[0])
        assertSame(alone, rows[1])
    }

    @Test
    fun `the conference lasts from the first answer to the last ending`() {
        val page = listOf(
            leg(id = 2, user = "1002", startedAt = 200, answeredAt = 260, endedAt = 900, key = "k1"),
            leg(id = 1, user = "1001", startedAt = 100, answeredAt = 130_000, endedAt = 500_000, key = "k1"),
        )

        val conference = groupConferences(page).single()

        assertTrue(conference.wasAnswered)
        assertEquals((500_000L - 260L) / 1_000L, conference.durationSeconds)
    }

    @Test
    fun `a conference held in the bridge is titled by its people, and the room is a leg`() {
        // The device that pressed Merge writes three rows: two legs that ended when their
        // transfers completed, and its own call to the room, which lasted the conference.
        val page = listOf(
            leg(id = 3, user = "3000", startedAt = 300, endedAt = 90_000, key = "k1", media = MediaProfile.AUDIO_VIDEO),
            leg(id = 2, user = "1005", startedAt = 200, endedAt = 400, key = "k1", media = MediaProfile.AUDIO_VIDEO),
            leg(id = 1, user = "1004", startedAt = 100, endedAt = 400, key = "k1", media = MediaProfile.AUDIO_VIDEO),
        )

        val conference = groupConferences(page) { it.remote.user == "3000" }.single()

        assertEquals("1004, 1005", conference.title, "the room is where it happened, not somebody who was there")
        assertEquals(listOf("1004", "1005"), conference.members.map { it.title })
        assertEquals(listOf("1004", "1005", "3000"), conference.legs.map { it.title }, "the room stays a leg")
        assertTrue(conference.legs.last().isRoom)
        assertTrue(conference.hasVideo)
        // Its duration runs to the room leg's ending — the conference outlived the transfers.
        assertEquals((90_000L - 101L) / 1_000L, conference.durationSeconds)
    }

    @Test
    fun `a page holding only the room leg of a conference reads as the room`() {
        // A page boundary can strand the room's row from its legs; an empty title would be
        // worse than the extension.
        val page = listOf(
            leg(id = 3, user = "3000", startedAt = 300, key = "k1"),
            leg(id = 4, user = "3000", startedAt = 310, key = "k1"),
        )

        val conference = groupConferences(page) { it.remote.user == "3000" }.single()

        assertEquals("3000, 3000", conference.title)
        assertTrue(conference.members.isEmpty())
    }

    @Test
    fun `a voice conference mixed on this device carries no video`() {
        val page = listOf(
            leg(id = 2, user = "1002", startedAt = 200, key = "k1"),
            leg(id = 1, user = "1001", startedAt = 100, key = "k1"),
        )
        assertFalse(groupConferences(page).single().hasVideo)
    }

    private fun leg(
        id: Long,
        user: String,
        startedAt: Long,
        key: String?,
        answeredAt: Long? = startedAt + 1,
        endedAt: Long = startedAt + 10,
        media: MediaProfile = MediaProfile.AUDIO,
    ) = HistoryRow.Call(
        entry = CallLogEntry(
            id = CallLogId(id),
            accountId = AccountId("acct-1"),
            remote = SipUri.parse("sip:$user@sip.example.com").getOrNull()!!,
            accountDomain = null,
            remoteDisplayName = null,
            contactName = null,
            direction = CallDirection.OUTGOING,
            startedAtEpochMillis = startedAt,
            answeredAtEpochMillis = answeredAt,
            endedAtEpochMillis = endedAt,
            reason = HangupReason.LOCAL_HANGUP,
            media = media,
            isConference = key != null,
            conferenceKey = key,
        ),
        title = user,
    )
}
