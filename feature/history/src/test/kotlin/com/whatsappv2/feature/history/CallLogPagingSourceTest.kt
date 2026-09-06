package com.whatsappv2.feature.history

import androidx.paging.PagingSource
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.engine.CallDirection
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.repository.CallLogFilter
import com.whatsappv2.domain.testing.FakeCallLogRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Paging the call log (Task 48).
 *
 * Against the fake repository rather than Room: what is under test is the windowing and
 * the keys, which are this class's, not the store's. The store's own ordering and filter
 * are asserted in `:data:calllog` against real SQLite.
 */
class CallLogPagingSourceTest {

    private val repository = FakeCallLogRepository()

    @Test
    fun `the first page starts at the top and has no previous key`() = runTest {
        given(entries = PAGE + 1)

        val page = load(offset = null, size = PAGE)

        assertEquals(PAGE, page.data.size)
        assertNull(page.prevKey)
        assertEquals(PAGE, page.nextKey)
    }

    @Test
    fun `a short page is the end of the list`() = runTest {
        // Fewer rows than asked for means there are no more. Asking again to be sure
        // would cost a query per page for the same answer.
        given(entries = PAGE - 1)

        val page = load(offset = null, size = PAGE)

        assertEquals(PAGE - 1, page.data.size)
        assertNull(page.nextKey)
    }

    @Test
    fun `a later page carries a key back to the one before it`() = runTest {
        given(entries = PAGE * 2)

        val page = load(offset = PAGE, size = PAGE)

        assertEquals(0, page.prevKey)
        assertEquals(PAGE * 2, page.nextKey)
    }

    @Test
    fun `an empty log is one empty page and nothing further`() = runTest {
        val page = load(offset = null, size = PAGE)

        assertEquals(emptyList(), page.data)
        assertNull(page.prevKey)
        assertNull(page.nextKey)
    }

    @Test
    fun `the missed filter pages over missed calls, not over everything`() = runTest {
        repeat(PAGE) { index ->
            repository.record(entry(index, direction = CallDirection.OUTGOING, answered = true))
        }
        repository.record(entry(PAGE, direction = CallDirection.INCOMING, answered = false))

        val page = load(offset = null, size = PAGE, filter = CallLogFilter.MISSED)

        // One missed call, not a page of answered ones with one missed among them.
        assertEquals(1, page.data.size)
        assertNull(page.nextKey)
    }

    // ---------------------------------------------------------------- fixture

    private suspend fun given(entries: Int) {
        repeat(entries) { repository.record(entry(it)) }
    }

    private suspend fun load(
        offset: Int?,
        size: Int,
        filter: CallLogFilter = CallLogFilter.ALL,
    ): PagingSource.LoadResult.Page<Int, CallLogEntry> {
        val source = CallLogPagingSource(repository, filter)
        val result = source.load(
            PagingSource.LoadParams.Refresh(key = offset, loadSize = size, placeholdersEnabled = false),
        )
        return assertIs(result)
    }

    private fun entry(
        index: Int,
        direction: CallDirection = CallDirection.OUTGOING,
        answered: Boolean = false,
    ) = CallLogEntry(
        id = CallLogId.UNSAVED,
        accountId = AccountId("acct-1"),
        remote = REMOTE,
        remoteDisplayName = null,
        contactName = null,
        direction = direction,
        startedAtEpochMillis = STARTED_AT + index,
        answeredAtEpochMillis = if (answered) STARTED_AT + index else null,
        endedAtEpochMillis = STARTED_AT + index,
        reason = HangupReason.LOCAL_HANGUP,
        media = MediaProfile.AUDIO,
    )

    private companion object {
        val REMOTE: SipUri = SipUri.parse("sip:bob@sip.example.com").getOrNull()!!
        const val STARTED_AT = 1_700_000_000_000L
        const val PAGE = 10
    }
}
