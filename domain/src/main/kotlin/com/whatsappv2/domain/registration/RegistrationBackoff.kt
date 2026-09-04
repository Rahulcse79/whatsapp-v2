package com.whatsappv2.domain.registration

import kotlin.math.min
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long to wait before retrying a failed REGISTER.
 *
 * ## Why this is a policy and not a constant
 *
 * §2.1 reframes "5,000 concurrent users" into the things the *client* controls, and this
 * is the main one. When a registrar restarts, every client that was registered to it
 * fails at the same instant. With a fixed retry interval they all come back at the same
 * instant too, and the server that just came up is immediately knocked over again. The
 * cure is that no two clients wait the same amount of time.
 *
 * ## Full jitter, not "exponential backoff plus a bit of noise"
 *
 * The delay is a random value in `[0, min(ceiling, base * 2^attempt)]` — the whole window
 * is random, not a fixed backoff with jitter added. Adding jitter to a fixed delay still
 * clusters clients around that delay; sampling the entire window spreads them evenly
 * across it, which is what actually flattens the recovery curve.
 *
 * A one-second floor keeps a failing account off a hot retry loop.
 *
 * ## Retry-After is honoured, and then some
 *
 * When a 503 carries `Retry-After`, the delay is never shorter than the server asked for
 * — ignoring it produces a second stampede immediately, which is the failure §2.1 names.
 *
 * But it is not obeyed *exactly* either. If 5,000 clients all wait precisely 120 seconds,
 * they return in the same instant and the server is hit by a perfectly synchronised wave.
 * A jitter window is added **on top of** the requested delay, so the instruction is
 * respected and the herd is still spread out.
 *
 * Stateless on purpose: the attempt number is the caller's, and a successful REGISTER
 * resets it to zero. Holding that count here would mean one shared object across
 * accounts, each resetting the others.
 */
class RegistrationBackoff(
    /** Delay window for the first retry. */
    private val baseDelay: Duration = DEFAULT_BASE_DELAY,

    /**
     * Longest the window may grow to.
     *
     * A ceiling exists so a device that has been offline overnight still retries within
     * minutes of the network returning, rather than in hours.
     */
    private val ceiling: Duration = DEFAULT_CEILING,

    /**
     * Extra spread added on top of a server-requested delay.
     *
     * Small relative to the delay itself: the point is to break simultaneity, not to keep
     * the client away for materially longer than the server asked.
     */
    private val serverRequestedJitter: Duration = DEFAULT_SERVER_JITTER,
) {

    /**
     * The delay before retry number [attempt].
     *
     * @param attempt zero-based; 0 is the first retry after the first failure.
     * @param retryAfter the server's `Retry-After`, when it sent one.
     * @param random injected so a test is deterministic, and so two clients can be given
     *   different seeds to prove they do not collide.
     */
    fun delayFor(
        attempt: Int,
        retryAfter: Duration? = null,
        random: Random = Random.Default,
    ): Duration {
        require(attempt >= 0) { "attempt must not be negative, was $attempt" }

        if (retryAfter != null) {
            val spread = random.nextLong(serverRequestedJitter.inWholeSeconds + 1).seconds
            return retryAfter + spread
        }

        val window = windowFor(attempt)
        val sampled = random.nextLong(window.inWholeSeconds + 1).seconds
        return maxOf(sampled, MINIMUM_DELAY)
    }

    /**
     * The upper bound of the random window for [attempt].
     *
     * Exposed because it is the part worth asserting directly: that it grows
     * exponentially and then stops at the ceiling.
     */
    fun windowFor(attempt: Int): Duration {
        val exponent = min(attempt, MAX_EXPONENT)
        val grown = baseDelay.inWholeSeconds shl exponent
        return min(grown, ceiling.inWholeSeconds).seconds
    }

    companion object {
        val DEFAULT_BASE_DELAY: Duration = 2.seconds

        /** Half an hour. Long enough to stop hammering, short enough to recover. */
        val DEFAULT_CEILING: Duration = 1_800.seconds

        val DEFAULT_SERVER_JITTER: Duration = 10.seconds

        /** Never retry faster than this, whatever the sample. */
        val MINIMUM_DELAY: Duration = 1.seconds

        /**
         * Caps the shift so the exponent cannot overflow a Long.
         *
         * The ceiling makes anything beyond this irrelevant anyway, but an attempt count
         * that keeps climbing for a device offline for days must not wrap around to a
         * tiny delay.
         */
        private const val MAX_EXPONENT = 32
    }
}
