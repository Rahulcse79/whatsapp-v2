package com.whatsappv2.feature.history

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.repository.CallLogFilter
import com.whatsappv2.domain.repository.CallLogRepository

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
 * ## Keys are offsets
 *
 * The key is the row offset rather than the last entry seen. A cursor would be sturdier
 * against rows being inserted mid-scroll, but the list is ordered newest first and the
 * only rows that appear are newer than everything on screen: they land above the window,
 * and [CallLogRepository.changes] invalidates the source anyway.
 */
class CallLogPagingSource(
    private val repository: CallLogRepository,
    private val filter: CallLogFilter,
) : PagingSource<Int, CallLogEntry>() {

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CallLogEntry> {
        val offset = params.key ?: 0

        return runCatching { repository.page(filter, offset, params.loadSize) }
            .fold(
                onSuccess = { entries ->
                    LoadResult.Page(
                        data = entries,
                        prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
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
     * newest one.
     */
    override fun getRefreshKey(state: PagingState<Int, CallLogEntry>): Int? =
        state.anchorPosition?.let { anchor ->
            val page = state.closestPageToPosition(anchor)
            page?.prevKey?.plus(state.config.pageSize) ?: page?.nextKey?.minus(state.config.pageSize)
        }
}
