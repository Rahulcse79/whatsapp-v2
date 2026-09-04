package com.whatsappv2.core.common.time

/**
 * A [Clock] a test drives by hand.
 *
 * Time only moves when [advanceBy] is called, so a test that checks a call duration or
 * a registration expiry is exact rather than approximate, and takes no real time.
 */
class MutableClock(private var currentMillis: Long = DEFAULT_START) : Clock {

    override fun nowEpochMillis(): Long = currentMillis

    /** Moves time forward. Rejects going backwards, which is never what a test means. */
    fun advanceBy(millis: Long): MutableClock = apply {
        require(millis >= 0) { "Time cannot move backwards; use set() if that is deliberate" }
        currentMillis += millis
    }

    /** Sets the clock outright, including backwards, for testing clock skew. */
    fun set(epochMillis: Long): MutableClock = apply { currentMillis = epochMillis }

    companion object {
        /** An arbitrary but recognisable start, so a stray 0 stands out in a failure. */
        const val DEFAULT_START: Long = 1_700_000_000_000L
    }
}
