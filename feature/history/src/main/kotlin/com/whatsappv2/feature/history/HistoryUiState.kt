package com.whatsappv2.feature.history

import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.repository.CallLogFilter

/**
 * One line in the history list.
 *
 * A sealed type rather than a list of entries with an optional header on each, because a
 * day heading is not a property of a call: it belongs between two of them, and the last
 * call of a day has no way to know it was the last.
 */
sealed interface HistoryRow {

    /** The day every following [Call] happened on, until the next one of these. */
    data class DayHeader(val epochDay: Long) : HistoryRow

    /**
     * One call, together with what to call the person on it.
     *
     * The title is carried rather than computed by the row that draws it, because working
     * it out means asking the address book (`CallLogTitles`) and a composable that did
     * that would do it again on every recomposition. Resolved once, as the page loads.
     */
    data class Call(val entry: CallLogEntry, val title: String) : HistoryRow
}

/**
 * What the screen is showing besides the list.
 *
 * The list itself is not in here: it is `PagingData`, which is a stream the screen
 * collects rather than a value a state object can hold, and putting it here would mean
 * rebuilding the pager every time the filter or a dialog changed.
 */
data class HistoryUiState(
    val filter: CallLogFilter = CallLogFilter.ALL,

    /**
     * The row whose detail is open, or null for the list.
     *
     * The row and not the bare entry, so the sheet's heading is the name the list showed.
     * Re-deriving it here would be a second address-book read for a title already resolved,
     * and the two could disagree.
     */
    val openEntry: HistoryRow.Call? = null,

    /** True while the "clear all history" confirmation is up. */
    val confirmingClearAll: Boolean = false,
)

/**
 * Something that happened once, that the list cannot show.
 *
 * A channel rather than state, for the same reason the dialler's events are: opening a
 * call screen must happen once, not again on the next recomposition.
 */
sealed interface HistoryEvent {

    /** The redial is on its way; the caller opens the call screen for it. */
    data class CallPlaced(val callId: CallId) : HistoryEvent

    /** The redial was refused, with the sentence Task 44 gives that failure. */
    data class Refused(val message: String) : HistoryEvent

    /**
     * The call went out, but not quite as asked (Task 75).
     *
     * A video redial on a device whose camera cannot be used is placed as an audio call
     * rather than refused, and saying so is the difference between a considered downgrade
     * and a video button that appears not to work.
     */
    data class Notice(val message: String) : HistoryEvent
}
