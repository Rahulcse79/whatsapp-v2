package com.whatsappv2.domain.repository

/**
 * Which calls the log should show.
 *
 * ## Why this is a value and not four parameters
 *
 * Because it travels: the screen builds it, the paging source carries it, the repository
 * turns it into SQL, and a test asserts on it. Four loose arguments would be four chances
 * to pass them in the wrong order, and adding a fifth criterion later would touch every
 * one of those call sites instead of this file.
 *
 * ## Every criterion is optional, and an empty query means "everything"
 *
 * [MATCH_ALL] is the default the screen opens on. That matters for paging: a query is the
 * paging key's identity, so "no search" has to be one stable value rather than a fresh
 * object per recomposition, or the list reloads from the top on every keystroke the user
 * did *not* type.
 */
data class CallLogQuery(
    /**
     * Matched against the contact name, the display name the far end sent, and the SIP
     * address — case-insensitively, anywhere in the field.
     *
     * One box for all three because the user does not know which of them holds what they
     * remember. They know "Rahul" or "1001", not which column it landed in.
     */
    val text: String = "",

    /** Which way the call went, and whether it was answered. */
    val direction: CallDirectionFilter = CallDirectionFilter.ANY,

    /** Inclusive lower bound on when the call started, or null for no bound. */
    val fromEpochMillis: Long? = null,

    /** Inclusive upper bound on when the call started, or null for no bound. */
    val toEpochMillis: Long? = null,
) {

    /** True when nothing is being narrowed, so the screen can say "no filters" plainly. */
    val isMatchAll: Boolean get() = this == MATCH_ALL

    /**
     * How many criteria are active, for a badge on the filter control.
     *
     * The text box is excluded: it is visible on screen as itself, and counting it would
     * make the badge say "1" for the thing the user is already looking at.
     */
    val activeFilterCount: Int
        get() = listOf(
            direction != CallDirectionFilter.ANY,
            fromEpochMillis != null,
            toEpochMillis != null,
        ).count { it }

    /** Which of the two tabs this query belongs under. */
    val tabFilter: CallLogFilter
        get() = if (direction == CallDirectionFilter.MISSED) CallLogFilter.MISSED else CallLogFilter.ALL

    /** What an empty result means, which depends on whether anything was searched for. */
    val emptyTitle: String
        get() = when {
            text.isNotEmpty() -> "No calls match"
            direction == CallDirectionFilter.MISSED -> "No missed calls"
            activeFilterCount > 0 -> "No calls match"
            else -> "No calls yet"
        }

    /** The sentence under [emptyTitle]: what to do next, not a restatement of the title. */
    val emptyDescription: String
        get() = if (isMatchAll) {
            "Calls you make and receive appear here."
        } else {
            "Try a different search, or clear the filters."
        }

    companion object {
        /** Everything, newest first. The screen's resting state. */
        val MATCH_ALL = CallLogQuery()

        /** Inbound and unanswered — the old `CallLogFilter.MISSED`, as a query. */
        val MISSED = CallLogQuery(direction = CallDirectionFilter.MISSED)
    }
}

/**
 * Direction, with "missed" as its own case.
 *
 * Missed is not a direction and belongs here anyway: it is what people actually look for,
 * it is inbound-and-unanswered rather than a fourth way a call can point, and putting it
 * beside the other three is the difference between one control and two.
 */
enum class CallDirectionFilter(val label: String) {
    ANY("All"),
    INCOMING("Incoming"),
    OUTGOING("Outgoing"),
    MISSED("Missed"),
}
