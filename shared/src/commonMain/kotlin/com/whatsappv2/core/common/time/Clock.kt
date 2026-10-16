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

/**
 * The real clock, and the only place either platform's "what time is it" call is made.
 *
 * `expect`, because there is no such call in common Kotlin: the JVM half reads
 * `System.currentTimeMillis()` and the Darwin half reads `NSDate`. Keeping the *name*
 * common is the point — `SystemClock` is named in Android's DI graph
 * (`RepositoryModule.provideClock`) and as a default argument in `PjsipSipEngine` and
 * `RegistrationRecoveryCoordinator`, so an `expect object` lets the shared move happen
 * without editing any of them.
 */
expect object SystemClock : Clock {
    override fun nowEpochMillis(): Long
}
