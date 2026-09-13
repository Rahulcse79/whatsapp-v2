package com.whatsappv2.domain.repository

import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import kotlinx.coroutines.flow.Flow

/** Which calls the history screen is showing. */
enum class CallLogFilter {
    /** Everything, answered or not. */
    ALL,

    /** Inbound calls that were never answered. */
    MISSED,
}

/**
 * Which calls have happened (Task 47, §5.2).
 *
 * ## Two ways to read, for two different readers
 *
 * [observe] is for anything that wants the whole list and will re-render when it changes —
 * a "recent calls" strip, a test. [page] is for the history screen, which is paged over
 * ten thousand entries and must not load them to show twenty.
 *
 * ## Why paging is offset-and-limit rather than `PagingData`
 *
 * `PagingData` is an `androidx` type and `:domain` may not import one — the architecture
 * test enforces that, and a feature may not reach past the domain into `:data:*` to get
 * around it. So the seam is the plainest thing that supports paging, and the `PagingSource`
 * that adapts it lives in `:feature:history` where androidx belongs. [changes] is how that
 * source knows to reload: a paged reader cannot notice a write the way a [Flow] of the
 * whole list does.
 *
 * ## Filtering here, not in the reader
 *
 * [CallLogFilter.MISSED] is asked of the store rather than applied to what [observe]
 * emits, because filtering after the fact would page through answered calls to find the
 * missed ones and show a short page whenever a page held few of them.
 */
interface CallLogRepository {

    /** Every entry matching [filter], newest first. */
    fun observe(filter: CallLogFilter = CallLogFilter.ALL): Flow<List<CallLogEntry>>

    /** One entry, or null once it has been deleted. */
    fun observeEntry(id: CallLogId): Flow<CallLogEntry?>

    /**
     * One page of entries matching [filter], newest first.
     *
     * [offset] and [limit] are rows, not pages, because that is what the store indexes on
     * and what a `PagingSource` asks for.
     */
    suspend fun page(filter: CallLogFilter, offset: Int, limit: Int): List<CallLogEntry>

    /**
     * One page of the calls matching [query], newest first.
     *
     * The searching half of [page], and separate from it because the plain filter is what
     * the tabs use and this is what the search bar uses — collapsing them would make every
     * tab switch build a query object to say "no criteria".
     *
     * Every criterion is applied **in the store**, not after the page is read. Filtering a
     * loaded page would search the twenty rows on screen and call it a search, which is
     * the failure mode worth naming: it looks like it works until the match is on row
     * four hundred.
     */
    suspend fun search(query: CallLogQuery, offset: Int, limit: Int): List<CallLogEntry>

    /**
     * Emits whenever the log changes.
     *
     * The value carries nothing; it is the fact of the change that matters. A paged
     * reader invalidates on it, which is what makes the list update when a call ends
     * rather than when the screen is next opened.
     */
    fun changes(): Flow<Unit>

    /**
     * Records a finished call and returns it with the id the store assigned.
     *
     * Called once per terminal transition. The entry passed in carries
     * [CallLogId.UNSAVED], because only the store can say what a row is called.
     */
    suspend fun record(entry: CallLogEntry): CallLogEntry

    /** Removes one entry. Deleting an entry that is already gone is not an error. */
    suspend fun delete(id: CallLogId)

    /** Removes every entry. */
    suspend fun clear()

    /**
     * Removes every call that started before [cutoffEpochMillis], and returns how many.
     *
     * Time alone, so one retention setting covers audio and video without either being
     * named — see `AppSettings.callHistoryRetention`.
     */
    suspend fun deleteStartedBefore(cutoffEpochMillis: Long): Int
}
