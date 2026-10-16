package com.whatsappv2.core.common.time

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * Darwin: `NSDate`'s seconds-since-1970, scaled to milliseconds.
 *
 * `timeIntervalSince1970` is a `Double` of seconds, so the conversion is explicit rather
 * than a cast — at the magnitudes involved (1.7e12 ms) a `Float` would already have lost
 * the minute, and rounding rather than truncating keeps the two platforms agreeing to
 * the millisecond on the same instant.
 */
actual object SystemClock : Clock {
    private const val MILLIS_PER_SECOND = 1_000.0

    actual override fun nowEpochMillis(): Long =
        (NSDate().timeIntervalSince1970 * MILLIS_PER_SECOND).toLong()
}
