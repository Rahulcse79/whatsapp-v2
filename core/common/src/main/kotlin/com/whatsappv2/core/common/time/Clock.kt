package com.whatsappv2.core.common.time

/**
 * Wall-clock time, injected rather than read from a static.
 *
 * Call durations, log timestamps and registration expiry all need the current time,
 * and every one of them becomes untestable the moment it calls
 * `System.currentTimeMillis()` directly — the test either sleeps or asserts a range.
 *
 * Epoch milliseconds rather than a date-time type: the values cross a module that must
 * stay dependency-free, and formatting is a UI concern.
 */
fun interface Clock {
    fun nowEpochMillis(): Long
}

/** The real clock. The only place `System.currentTimeMillis()` should be called. */
object SystemClock : Clock {
    override fun nowEpochMillis(): Long = System.currentTimeMillis()
}
