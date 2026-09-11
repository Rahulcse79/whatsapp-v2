package com.whatsappv2.feature.history

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.whatsappv2.domain.repository.CallLogQuery
import com.whatsappv2.domain.repository.CallLogRepository
import com.whatsappv2.domain.usecase.CallLogTitles

/**
 * Pages the call log, one window of rows at a time (Task 48).
 *
 * ## Why it lives in the feature
 *
 * `PagingSource` is an `androidx` type and `:domain` may not import one, so it cannot sit
 * behind the repository interface; a feature may not reach into `:data:*` to find one
 * either. What is left — and what is right — is for the domain to expose the plainest
 * paged read there is and for the adapter to androidx to live here, in the one module
 * that already depends on it.
 *
 * ## The query is the source's identity
 *
 * Every criterion is applied in the store, by [CallLogRepository.search]. Filtering a
 * loaded page would search the twenty rows on screen and call it a search — which looks
 * like it works right up until the match is on row four hundred. A new query means a new
 * source, which is why `CallLogQuery.MATCH_ALL` is a constant rather than a fresh object:
 * an equal query must be the same query, or the list reloads from the top on every
 * keystroke the user did not type.
 *
 * ## Keys are offsets, and every page starts on a multiple of [pageSize]
 *
 * The key is the row offset rather than the last entry seen. A cursor would be sturdier
 * against rows being inserted mid-scroll, but the list is ordered newest first and the
 * only rows that appear are newer than everything on screen: they land above the window,
 * and `HistoryViewModel` invalidates this source on [CallLogRepository.changes] anyway.
 *
 * That last clause was aspirational until Task 71 — the signal existed and nothing
 * collected it. It is wired now; see `HistoryViewModel.watchStoreChanges`.
 *
 * Alignment is what makes a refresh land where the user was. Paging's first load asks for
 * three pages at once and every later one for a single page, and [getRefreshKey] has to
 * name a row offset the next window starts at. If the previous key of a page were
 * computed from *that page's* load size, the arithmetic would differ between the first
 * window and the rest — which is how the previous version came to answer "row 80" for a
 * user looking at row 0 of a 147-row log: `nextKey (120) − pageSize (40)`. The screen
 * then opened on yesterday's calls with no day heading, and the newest call — the one the
 * screen exists to show — was eighty rows above the top. Every key is now a multiple of
 * the page size and the previous key is always one page back, so a window's start is
 * recoverable from its own keys and a refresh can be anchored on a row rather than guessed.
 *
 * ## Rows, not entries: the name is resolved here
 *
 * Each entry is paired with what to call the person on it before it leaves this class, and
 * this is the only place in the screen where that can happen cheaply. [CallLogTitles] asks
 * the address book, and a page is the coarsest unit that still gets the answer right: once
 * per row loaded, on Paging's fetch dispatcher, absorbed by the contact repository's own
 * cache when a log of a thousand calls is a log of six extensions. The alternative — a row
 * composable resolving its own name — is a content-provider read per recomposition.
 */
class CallLogPagingSource(
    private val repository: CallLogRepository,
    private val query: CallLogQuery,
    private val titles: CallLogTitles,
    /** The `PagingConfig.pageSize` this source is paged with; every key is a multiple of it. */
    private val pageSize: Int,
) : PagingSource<Int, HistoryRow.Call>() {

    init {
        require(pageSize > 0) { "pageSize must be positive, was $pageSize" }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, HistoryRow.Call> {
        val offset = params.key ?: 0

        return runCatching {
            repository.search(query, offset, params.loadSize).map { HistoryRow.Call(it, titles(it)) }
        }
            .fold(
                onSuccess = { entries ->
                    LoadResult.Page(
                        data = entries,
                        // One page back, whatever size this load was. The first load is
                        // three pages long, and a previous key derived from *that* would
                        // skip two pages when the user scrolled up.
                        prevKey = if (offset == 0) null else (offset - pageSize).coerceAtLeast(0),
                        // A short page is the end of the list. Asking for one more to be
                        // sure would cost a query per page for the same answer.
                        nextKey = if (entries.size < params.loadSize) null else offset + entries.size,
                    )
                },
                onFailure = { LoadResult.Error(it) },
            )
    }

    /**
     * Where to resume after an invalidation.
     *
     * Anchored on what the user is looking at rather than restarting at the top: a call
     * ending while they are reading last week's entries must not throw them back to the
     * newest one. And the reverse, which is the case that was broken: a user at the top
     * must be given a window that starts at the top, not one that starts eighty rows down.
     *
     * The anchor is Paging's index into the rows loaded so far. Adding the offset the
     * first loaded page starts at gives the row the user is on; the window starts one page
     * above it, aligned to a page boundary, so the row is inside the window with a page
     * of room to scroll up before anything has to be fetched.
     */
    override fun getRefreshKey(state: PagingState<Int, HistoryRow.Call>): Int? {
        val anchor = state.anchorPosition ?: return null
        val firstPageStart = state.pages.firstOrNull()?.prevKey?.plus(pageSize) ?: 0
        val anchorRow = firstPageStart + anchor
        return (anchorRow - pageSize).coerceAtLeast(0) / pageSize * pageSize
    }
}
