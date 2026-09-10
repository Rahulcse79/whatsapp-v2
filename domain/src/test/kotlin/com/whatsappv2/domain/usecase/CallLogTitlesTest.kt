package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.testing.FakeContactRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a history row is called, and why (Task 49).
 *
 * The reported defect is one line of this: a row for extension `7001` read
 * `sip:7001@192.168.80.145`, both because the contact was added after the call and because
 * the last-resort label was the whole URI.
 */
class CallLogTitlesTest {

    private val contacts = FakeContactRepository()
    private val titles = CallLogTitles(contacts)

    @Test
    fun `a contact added after the call still names the row`() = runTest {
        // The reported case. The row was written before this person was in the address
        // book, so its snapshot is null and always will be — the name has to come from
        // asking again.
        val entry = entry(contactName = null)
        contacts.given(entry.remote, name = "Priya Raman")

        assertEquals("Priya Raman", titles(entry))
    }

    @Test
    fun `a missed call is named the same way as any other`() = runTest {
        // The one people actually look up, and the one with no duration to pad the row
        // out: if any row is going to be read as a bare number it is this one.
        val missed = entry(
            contactName = null,
            direction = CallDirection.INCOMING,
            answeredAt = null,
        )
        contacts.given(missed.remote, name = "Priya Raman")

        assertEquals("Priya Raman", titles(missed))
        assertEquals(true, missed.wasMissed)
    }

    @Test
    fun `a renamed contact renames the row, and a deleted one leaves the snapshot`() = runTest {
        val entry = entry(contactName = "Priya R")

        // Renamed: the address book is the live answer, so it wins.
        contacts.given(entry.remote, name = "Priya Raman")
        assertEquals("Priya Raman", titles(entry))

        // Deleted: nothing to ask, and the snapshot is what the log meant at the time.
        // This is why the snapshot is kept rather than replaced by a join.
        assertEquals("Priya R", entry.titleWith(currentContactName = null))
    }

    @Test
    fun `the user's own word for someone beats what the far end called itself`() {
        // A PBX that puts "Reception" in every From header must not overwrite the name in
        // somebody's phone. This ordering already existed and is the one thing here that
        // must not be lost.
        val entry = entry(contactName = "Priya Raman", remoteDisplayName = "Reception")

        assertEquals("Priya Raman", entry.titleWith(currentContactName = null))
        assertEquals("Anita", entry.titleWith(currentContactName = "Anita"))
    }

    @Test
    fun `with no name anywhere the row is the extension, never the URI`() {
        val entry = entry(contactName = null, remoteDisplayName = null)

        assertEquals("7001", entry.titleWith(currentContactName = null))
    }

    @Test
    fun `the peer's display name is used when the address book has nothing`() {
        val entry = entry(contactName = null, remoteDisplayName = "Reception")

        assertEquals("Reception", entry.titleWith(currentContactName = null))
    }

    @Test
    fun `an address with no user part falls back to the host`() {
        // `sip:conference.example.com` is a real thing to have called, and "" would be a
        // row with nothing in it.
        val entry = entry(contactName = null, remoteDisplayName = null, remote = "sip:conf.example.com")

        assertEquals("conf.example.com", entry.titleWith(currentContactName = null))
    }

    @Test
    fun `the address book is asked once per entry, not once per field read`() = runTest {
        val entry = entry(contactName = null)

        titles(entry)

        assertEquals(1, contacts.lookups.size)
    }

    private fun entry(
        contactName: String?,
        remoteDisplayName: String? = null,
        direction: CallDirection = CallDirection.OUTGOING,
        answeredAt: Long? = 2_000L,
        remote: String = "sip:7001@192.168.80.145",
    ) = CallLogEntry(
        id = CallLogId(1L),
        accountId = AccountId("acct-1"),
        remote = checkNotNull(SipUri.parse(remote).getOrNull()),
        remoteDisplayName = remoteDisplayName,
        contactName = contactName,
        direction = direction,
        startedAtEpochMillis = 1_000L,
        answeredAtEpochMillis = answeredAt,
        endedAtEpochMillis = 3_000L,
        reason = HangupReason.LOCAL_HANGUP,
        media = MediaProfile.AUDIO,
    )
}
