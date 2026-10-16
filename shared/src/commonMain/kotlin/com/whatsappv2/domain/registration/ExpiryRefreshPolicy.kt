package com.whatsappv2.domain.registration

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * When to refresh a registration before it lapses.
 *
 * ## The server's expiry wins
 *
 * A registrar may grant less than the client asked for, and the binding expires on *its*
 * schedule. Refreshing against the requested value when the server granted less means the
 * binding lapses before the refresh fires, and inbound calls stop arriving with nothing
 * in the client's logs to explain it. [effectiveExpiry] is the only correct source.
 *
 * ## Why a window rather than a fixed fraction
 *
 * Refreshing at exactly 80% of expiry re-creates the stampede problem in slow motion:
 * clients that registered together refresh together, forever. Sampling between 50% and
 * 90% spreads them out and keeps spreading them, because each refresh re-randomises.
 *
 * The lower bound is 50% so a refresh that fails still leaves time for a retry before the
 * binding lapses; the upper bound is 90% so a slow round trip does not miss the deadline.
 */
object ExpiryRefreshPolicy {

    /** Fraction of the granted expiry at which a refresh may fire, earliest. */
    const val EARLIEST_FRACTION = 0.50

    /** Fraction at which it must have fired by, leaving headroom for a slow response. */
    const val LATEST_FRACTION = 0.90

    /**
     * The expiry actually in force.
     *
     * The server's value wins when it is lower. A server granting *more* than requested
     * is unusual, and the client's own preference is respected in that case.
     */
    fun effectiveExpiry(requested: Duration, granted: Duration): Duration =
        minOf(requested, granted)

    /**
     * How long to wait before refreshing, given the expiry the server granted.
     *
     * @param random injected so a test is deterministic and two clients can be shown to
     *   pick different moments.
     */
    fun refreshDelay(grantedExpiry: Duration, random: Random = Random.Default): Duration {
        require(grantedExpiry.isPositive()) { "granted expiry must be positive" }

        val seconds = grantedExpiry.inWholeSeconds
        val earliest = (seconds * EARLIEST_FRACTION).toLong()
        val latest = (seconds * LATEST_FRACTION).toLong()

        // A very short expiry can collapse the window to a single value; sampling an
        // empty range would throw, and refreshing immediately is the right answer there.
        if (latest <= earliest) return earliest.coerceAtLeast(1L).seconds

        return random.nextLong(earliest, latest + 1).seconds
    }

    /** True when [delay] falls inside the permitted refresh window for [grantedExpiry]. */
    fun isWithinWindow(delay: Duration, grantedExpiry: Duration): Boolean {
        val seconds = grantedExpiry.inWholeSeconds
        return delay.inWholeSeconds >= (seconds * EARLIEST_FRACTION).toLong() &&
            delay.inWholeSeconds <= (seconds * LATEST_FRACTION).toLong()
    }
}
