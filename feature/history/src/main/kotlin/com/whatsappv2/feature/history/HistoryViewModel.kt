package com.whatsappv2.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.call.userMessage
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.repository.CallLogFilter
import com.whatsappv2.domain.repository.CallLogRepository
import com.whatsappv2.domain.usecase.PlaceCallError
import com.whatsappv2.domain.usecase.PlaceCallUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId
import javax.inject.Inject

/**
 * The call history screen (Task 48, §5.2).
 *
 * ## The list is a stream, the rest is state
 *
 * `PagingData` is not something a UiState can hold — it is a stream the screen collects,
 * and rebuilding it would reset the user's place in the list. So [rows] is its own flow,
 * rebuilt only when the filter changes, and everything else the screen needs is in
 * [uiState]. Deriving it from the filter alone rather than from the whole state is the
 * point: opening a detail sheet must not send the list back to the newest call.
 *
 * ## Nothing here refreshes the list
 *
 * Deleting an entry and clearing the log both go to the store and stop. The store's
 * change signal invalidates the paging source, so a row leaves for the same reason a new
 * call arrives — which is also what makes the list update live while the screen is open.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: CallLogRepository,
    private val placeCall: PlaceCallUseCase,
) : ViewModel() {

    private val state = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = state.asStateFlow()

    private val eventChannel = Channel<HistoryEvent>(Channel.BUFFERED)
    val events: Flow<HistoryEvent> = eventChannel.receiveAsFlow()

    /** The device's zone, read once: a call's day must not change while the list is open. */
    private val zone: ZoneId = ZoneId.systemDefault()

    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: Flow<PagingData<HistoryRow>> = state
        .map { it.filter }
        .distinctUntilChanged()
        .flatMapLatest { filter -> pagerFor(filter) }
        // Re-reads the cache rather than the database after a configuration change, which
        // is what keeps the position instead of snapping back to the top.
        .cachedIn(viewModelScope)

    fun onFilterChanged(filter: CallLogFilter) = state.update { it.copy(filter = filter) }

    fun onEntryOpened(entry: CallLogEntry) = state.update { it.copy(openEntry = entry) }

    fun onDetailDismissed() = state.update { it.copy(openEntry = null) }

    fun onClearAllRequested() = state.update { it.copy(confirmingClearAll = true) }

    fun onClearAllDismissed() = state.update { it.copy(confirmingClearAll = false) }

    fun onDelete(id: CallLogId) {
        viewModelScope.launch {
            repository.delete(id)
            // The open detail is closed only if it was the entry deleted: deleting from
            // the list behind an open sheet must not shut the sheet on a different call.
            state.update { current ->
                current.copy(openEntry = current.openEntry?.takeIf { it.id != id })
            }
        }
    }

    fun onClearAllConfirmed() {
        viewModelScope.launch {
            repository.clear()
            state.update { it.copy(confirmingClearAll = false, openEntry = null) }
        }
    }

    /**
     * Redials an entry.
     *
     * Placed here rather than by navigating to the dialler with the address filled in: a
     * call back is one action, and routing it through another screen would make it two
     * and leave the user looking at a dialler they did not ask for. The account is the
     * one the original call used, so a call back goes out the way the call came in.
     */
    fun onCallBack(entry: CallLogEntry) {
        viewModelScope.launch {
            val result = placeCall(
                input = entry.remote.render(),
                accountOverride = entry.accountId,
            )
            eventChannel.send(
                when (result) {
                    is Outcome.Success -> HistoryEvent.CallPlaced(result.value)
                    is Outcome.Failure -> HistoryEvent.Refused(result.error.describe())
                },
            )
        }
    }

    /** The refusal in a sentence, from the domain's table (Task 44). */
    private fun PlaceCallError.describe(): String = when (this) {
        is PlaceCallError.Rejected -> cause.userMessage()
        is PlaceCallError.NoAccountAvailable -> "That account is no longer set up"
        is PlaceCallError.UnknownAccount -> "That account is no longer set up"
        is PlaceCallError.InvalidTarget -> "That address could not be dialled"
    }

    private fun pagerFor(filter: CallLogFilter): Flow<PagingData<HistoryRow>> =
        Pager(
            config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false),
            pagingSourceFactory = { CallLogPagingSource(repository, filter) },
        ).flow.map { page ->
            page.map<CallLogEntry, HistoryRow> { HistoryRow.Call(it) }
                .insertSeparators { before, after ->
                    dayHeaderBetween(before as? HistoryRow.Call, after as? HistoryRow.Call, zone)
                }
        }

    private companion object {
        /**
         * Rows per page.
         *
         * Enough to fill a phone screen twice over, so a fast scroll has somewhere to go
         * before the next query lands, and small enough that opening the screen is one
         * cheap query rather than a visible pause.
         */
        const val PAGE_SIZE = 40
    }
}
