package com.whatsappv2.feature.history

import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.CallLogEntry
import com.whatsappv2.domain.repository.CallLogQuery

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
    data class Call(
        val entry: CallLogEntry,
        val title: String,
        /**
         * Every leg of the conference this entry began, oldest first — [entry] included —
         * or empty for a call that was not one (ADR-009).
         *
         * A conference this device mixed is one row per leg in the log, for the reasons
         * [CallLogEntry.isConference] gives; on screen it is one entry, the way a group
         * call is one entry in any messaging app, with the people in it listed rather
         * than scattered down the day as calls to strangers. The legs are carried so the
         * detail can name each member and the whole thing can be deleted or called back
         * as one.
         */
        val legs: List<Member> = emptyList(),
    ) : HistoryRow {

        /** True when this row stands for a conference with more than one leg. */
        val isConferenceGroup: Boolean get() = legs.size > 1

        /** When the conference started: its first leg. */
        val startedAtEpochMillis: Long
            get() = legs.firstOrNull()?.entry?.startedAtEpochMillis ?: entry.startedAtEpochMillis

        /** Whether anybody in it was ever reached. */
        val wasAnswered: Boolean get() = if (legs.isEmpty()) entry.wasAnswered else legs.any { it.entry.wasAnswered }

        /**
         * How long the conference lasted: from the first answer to the last ending. A
         * single call is its own duration, as before.
         */
        val durationSeconds: Long
            get() {
                if (legs.isEmpty()) return entry.durationSeconds
                val answered = legs.mapNotNull { it.entry.answeredAtEpochMillis }.minOrNull() ?: return 0L
                val ended = legs.maxOf { it.entry.endedAtEpochMillis }
                return (ended - answered).coerceAtLeast(0L) / MILLIS_PER_SECOND
            }
    }

    /** One leg of a conference, with what to call the person on it. */
    data class Member(val entry: CallLogEntry, val title: String)
}

private const val MILLIS_PER_SECOND = 1_000L

/**
 * What the screen is showing besides the list.
 *
 * The list itself is not in here: it is `PagingData`, which is a stream the screen
 * collects rather than a value a state object can hold, and putting it here would mean
 * rebuilding the pager every time the filter or a dialog changed.
 */
data class HistoryUiState(
    /**
     * Everything narrowing the list: the tab, the search text and the advanced filters.
     *
     * One value rather than a tab field beside a query field. The All/Missed tabs ARE a
     * direction filter, and holding the same axis in two places is how a screen ends up
     * showing "All" over a list of missed calls.
     */
    val query: CallLogQuery = CallLogQuery.MATCH_ALL,

    /** True while the search field is open, which is a view concern and not a criterion. */
    val searching: Boolean = false,

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
