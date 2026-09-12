package com.whatsappv2.domain.model

/**
 * How long the call log is kept before it prunes itself.
 *
 * ## A number of days, not an enum
 *
 * Every other setting here is an enum, because every other setting is a choice between
 * behaviours. This one is a quantity: "keep it a bit longer" is a thing people mean, and
 * an enum can only offer the values somebody thought of. [PRESETS] is what the dropdown
 * shows, [ofDays] is what anything else may store, and the two do not have to agree —
 * a value that arrived from a future build with more presets is still a number of days
 * this build can honour.
 *
 * ## Zero means keep everything
 *
 * Rather than a nullable, or a separate flag, because "delete nothing older than zero days
 * ago" is not a thing anyone wants and the value is otherwise free. [keepsEverything] is
 * the name to read it by; nothing should be comparing [days] to 0 itself.
 *
 * ## The bound is a real one
 *
 * [MAXIMUM_DAYS] is ten years. Not a policy — a guard: the cutoff is computed by
 * multiplying days into milliseconds, and a number large enough to overflow that would
 * produce a cutoff in the far past or the far future, either of which deletes the wrong
 * rows. Clamping is the honest response to a value that cannot mean what it says.
 */
@JvmInline
value class CallHistoryRetention private constructor(val days: Int) {

    /** True when nothing is ever pruned. */
    val keepsEverything: Boolean get() = days == KEEP_EVERYTHING_DAYS

    /**
     * The instant before which a call is old enough to remove, or null when nothing is.
     *
     * Given the current time rather than reading a clock, so the decision is testable and
     * so a caller pruning a batch uses one "now" for all of it.
     */
    fun cutoffEpochMillis(nowEpochMillis: Long): Long? =
        if (keepsEverything) null else nowEpochMillis - days * MILLIS_PER_DAY

    companion object {
        /** The value that keeps the log forever. */
        const val KEEP_EVERYTHING_DAYS = 0

        /** Ten years, as a guard against arithmetic rather than as a policy. */
        const val MAXIMUM_DAYS = 3_650

        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000

        val KEEP_EVERYTHING = CallHistoryRetention(KEEP_EVERYTHING_DAYS)

        /**
         * Twenty days.
         *
         * Long enough that "who called me a couple of weeks ago" is still answerable, and
         * short enough that a phone in daily use does not accumulate a log nobody has ever
         * scrolled to the end of.
         */
        val DEFAULT = CallHistoryRetention(20)

        /**
         * What the dropdown offers, shortest first, with "keep everything" last.
         *
         * Ordered by length rather than by likelihood so the list reads as a scale — a
         * user looking for "longer than what I have" scans downwards and stops.
         */
        val PRESETS: List<CallHistoryRetention> = listOf(
            CallHistoryRetention(7),
            CallHistoryRetention(14),
            DEFAULT,
            CallHistoryRetention(30),
            CallHistoryRetention(60),
            CallHistoryRetention(90),
            CallHistoryRetention(180),
            CallHistoryRetention(365),
            KEEP_EVERYTHING,
        )

        /** Any number of days, clamped into what can be honoured. Negative means forever. */
        fun ofDays(days: Int): CallHistoryRetention =
            CallHistoryRetention(days.coerceIn(KEEP_EVERYTHING_DAYS, MAXIMUM_DAYS))
    }
}
