package com.whatsappv2.domain.testing

import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.repository.CallLogFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The call-log fake, held to the same promises the Room-backed store makes.
 *
 * Newest first, ids that increase from one, and a missed filter that means inbound and
 * unanswered. A fake that got any of those wrong would let a test pass here and fail
 * against SQLite, which is the worst kind of fake.
 */
class FakeCallLogRepositoryTest {

    private val log = FakeCallLogRepository()

    @Test
    fun `recording assigns an id, starting at one`() = runTest {
        assertEquals(CallLogId(1), log.record(entry()).id)
        assertEquals(CallLogId(2), log.record(entry()).id)
    }

    @Test
    fun `the newest call is first`() = runTest {
        val older = log.record(entry())
        val newer = log.record(entry())

        assertEquals(listOf(newer.id, older.id), log.observe().first().map { it.id })
    }

    @Test
    fun `missed means inbound and unanswered, and nothing else`() = runTest {
        val missed = log.record(entry(direction = CallDirection.INCOMING, answered = false))
        log.record(entry(direction = CallDirection.INCOMING, answered = true))
        // Nobody misses their own call.
        log.record(entry(direction = CallDirection.OUTGOING, answered = false))

        assertEquals(listOf(missed.id), log.observe(CallLogFilter.MISSED).first().map { it.id })
    }

    @Test
    fun `paging windows the filtered list`() = runTest {
        repeat(THREE) { log.record(entry()) }

        assertEquals(1, log.page(CallLogFilter.ALL, offset = 0, limit = 1).size)
        assertEquals(2, log.page(CallLogFilter.ALL, offset = 1, limit = TEN).size)
        assertTrue(log.page(CallLogFilter.ALL, offset = TEN, limit = TEN).isEmpty())
    }

    @Test
    fun `an entry can be observed until it is deleted`() = runTest {
        val saved = log.record(entry())
        assertEquals(saved, log.observeEntry(saved.id).first())

        log.delete(saved.id)

        assertNull(log.observeEntry(saved.id).first())
    }

    @Test
    fun `clearing removes everything, and changes emit either way`() = runTest {
        log.record(entry())
        log.clear()

        assertTrue(log.observe().first().isEmpty())
        // The value carries nothing; that there is one at all is the signal.
        log.changes().first()
    }

    private fun entry(
        direction: CallDirection = CallDirection.OUTGOING,
        answered: Boolean = false,
    ) = CallLogEntry(
        id = CallLogId.UNSAVED,
        accountId = AccountId("acct-1"),
        remote = REMOTE,
        remoteDisplayName = null,
        contactName = null,
        direction = direction,
        startedAtEpochMillis = STARTED_AT,
        answeredAtEpochMillis = if (answered) STARTED_AT else null,
        endedAtEpochMillis = STARTED_AT,
        reason = HangupReason.LOCAL_HANGUP,
        media = MediaProfile.AUDIO,
    )

    private companion object {
        val REMOTE: SipUri = SipUri.parse("sip:bob@sip.example.com").getOrNull()!!
        const val STARTED_AT = 1_700_000_000_000L
        const val THREE = 3
        const val TEN = 10
    }
}
