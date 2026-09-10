package com.whatsappv2.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.call.userMessage
import com.whatsappv2.domain.engine.CameraAvailability
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.model.CallLogId
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.repository.CallLogFilter
import com.whatsappv2.domain.repository.CallLogRepository
import com.whatsappv2.domain.usecase.CallLogTitles
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
 * ## What refreshes the list
 *
 * [CallLogRepository.changes] is the store's own signal — Room re-runs it on every write
 * to the table, including one that leaves the row count alone — and [watchStoreChanges]
 * collects it and invalidates the live [CallLogPagingSource]. That is what makes a call
 * appear while the screen is open, and it is the same path a delete and a clear take, so
 * a row leaves for exactly the reason a new one arrives.
 *
 * Until Task 71 this collection did not exist. The signal was implemented in `:data:calllog`,
 * documented here and in [CallLogPagingSource] as though it were wired, and read by nothing —
 * so the list only changed when something else happened to rebuild it. The regression test is
 * `HistoryViewModelTest.a call recorded while the screen is open reaches the list`.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: CallLogRepository,
    private val placeCall: PlaceCallUseCase,
    /**
     * Read only to *describe* a video redial, never to decide one (Task 75).
     *
     * [PlaceCallUseCase] makes the downgrade decision, and it is the only place that may:
     * a second copy of the rule here would be one that drifts. This asks the same question
     * purely so the snackbar can say the call went out as audio.
     */
    private val camera: CameraAvailability,
    /**
     * What each row is called, asked once per entry as its page loads (Task 49).
     *
     * Here rather than in the row composable: the address book is a content provider, and
     * a title resolved during recomposition is a provider read per frame. Applied inside
     * [pagerFor], above `cachedIn`, so a scroll back over rows already seen re-reads
     * nothing.
     */
    private val titles: CallLogTitles,
) : ViewModel() {

    private val state = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = state.asStateFlow()

    private val eventChannel = Channel<HistoryEvent>(Channel.BUFFERED)
    val events: Flow<HistoryEvent> = eventChannel.receiveAsFlow()

    /** The device's zone, read once: a call's day must not change while the list is open. */
    private val zone: ZoneId = ZoneId.systemDefault()

    /**
     * The source currently feeding the list, so a store change has something to invalidate.
     *
     * Paging builds a fresh source after every invalidation, so this is rewritten each time
     * and always points at the live one. `@Volatile` because the factory runs on Paging's
     * dispatcher while [watchStoreChanges] reads it from the ViewModel's scope.
     */
    @Volatile
    private var liveSource: CallLogPagingSource? = null

    init {
        watchStoreChanges()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: Flow<PagingData<HistoryRow>> = state
        .map { it.filter }
        .distinctUntilChanged()
        .flatMapLatest { filter -> pagerFor(filter) }
        // Re-reads the cache rather than the database after a configuration change, which
        // is what keeps the position instead of snapping back to the top.
        .cachedIn(viewModelScope)

    /**
     * Reloads the list from the store on the store's own signal (Task 71).
     *
     * An invalidation rather than a rebuilt pager: `Pager` would restart the list at the
     * top, and [CallLogPagingSource.getRefreshKey] anchors a reload on the row the user is
     * looking at. Somebody reading last week's calls when a new one arrives keeps their
     * place.
     */
    private fun watchStoreChanges() {
        viewModelScope.launch {
            repository.changes().collect { liveSource?.invalidate() }
        }
    }

    /**
     * Reloads because the screen came back (Task 71).
     *
     * Driven by the screen's lifecycle, not a timer. Returning from a call must show that
     * call: the write happened while this ViewModel's collector was stopped, so the signal
     * that would have invalidated the source was never delivered.
     */
    fun refresh() {
        liveSource?.invalidate()
    }

    fun onFilterChanged(filter: CallLogFilter) = state.update { it.copy(filter = filter) }

    fun onEntryOpened(row: HistoryRow.Call) = state.update { it.copy(openEntry = row) }

    fun onDetailDismissed() = state.update { it.copy(openEntry = null) }

    fun onClearAllRequested() = state.update { it.copy(confirmingClearAll = true) }

    fun onClearAllDismissed() = state.update { it.copy(confirmingClearAll = false) }

    fun onDelete(id: CallLogId) {
        viewModelScope.launch {
            repository.delete(id)
            // The open detail is closed only if it was the entry deleted: deleting from
            // the list behind an open sheet must not shut the sheet on a different call.
            state.update { current ->
                current.copy(openEntry = current.openEntry?.takeIf { it.entry.id != id })
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
    fun onCallBack(entry: CallLogEntry) = callBack(entry, MediaProfile.AUDIO)

    /**
     * Redials with video (Task 75).
     *
     * Downgrades rather than refuses when the camera cannot be used — the use case does
     * that — and says so, because a video button that silently places an audio call is one
     * the user will press again expecting something different.
     */
    fun onVideoCallBack(entry: CallLogEntry) = callBack(entry, MediaProfile.AUDIO_VIDEO)

    private fun callBack(entry: CallLogEntry, media: MediaProfile) {
        val downgraded = media.hasVideo && !camera.isCameraUsable()
        viewModelScope.launch {
            val result = placeCall(
                input = entry.remote.render(),
                accountOverride = entry.accountId,
                media = media,
            )
            eventChannel.send(
                when (result) {
                    is Outcome.Success -> HistoryEvent.CallPlaced(result.value)
                    is Outcome.Failure -> HistoryEvent.Refused(result.error.describe())
                },
            )
            if (result is Outcome.Success && downgraded) {
                eventChannel.send(HistoryEvent.Notice(NO_CAMERA))
            }
        }
    }

    /** The refusal in a sentence, from the domain's table (Task 44). */
    private fun PlaceCallError.describe(): String = when (this) {
        is PlaceCallError.Rejected -> cause.userMessage()
        is PlaceCallError.NoAccountAvailable -> "That account is no longer set up"
        is PlaceCallError.UnknownAccount -> "That account is no longer set up"
        is PlaceCallError.InvalidTarget -> "That address could not be dialled"
        // Attempted and not finished, which is a different sentence from "not registered":
        // the app has already tried to fix it and the server has not answered yet.
        is PlaceCallError.NotRegistered -> "Could not reach the server for that account"
    }

    private fun pagerFor(filter: CallLogFilter): Flow<PagingData<HistoryRow>> =
        Pager(
            config = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false),
            pagingSourceFactory = {
                CallLogPagingSource(repository, filter, titles).also { liveSource = it }
            },
        ).flow.map { page ->
            // The source already emits rows with their names resolved, so all that is left
            // is to slot the day headings between them. The type argument is what widens
            // `HistoryRow.Call` to `HistoryRow`; before Task 49 this was a `map` followed
            // by two casts that could not fail.
            page.insertSeparators<HistoryRow.Call, HistoryRow> { before, after ->
                dayHeaderBetween(before, after, zone)
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

        /** Said once, here, so this screen and the dialler word the downgrade alike. */
        const val NO_CAMERA = "No camera available, so the call went out as audio"
    }
}
