package com.whatsappv2.domain.registration

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The anti-stampede properties from §2.1.
 *
 * These are the client's entire contribution to "5,000 concurrent users": when a registrar
 * restarts, every client fails at the same instant, and the only thing stopping them from
 * knocking it over again is that no two of them wait the same amount of time.
 */
class RegistrationBackoffTest {

    private val backoff = RegistrationBackoff()

    @Test
    fun `the window grows exponentially`() {
        assertEquals(2.seconds, backoff.windowFor(0))
        assertEquals(4.seconds, backoff.windowFor(1))
        assertEquals(8.seconds, backoff.windowFor(2))
        assertEquals(16.seconds, backoff.windowFor(3))
        assertEquals(32.seconds, backoff.windowFor(4))
    }

    @Test
    fun `the window stops at the ceiling`() {
        // A device offline overnight must still retry within minutes of the network
        // returning, not hours.
        assertEquals(RegistrationBackoff.DEFAULT_CEILING, backoff.windowFor(20))
        assertEquals(RegistrationBackoff.DEFAULT_CEILING, backoff.windowFor(100))
    }

    @Test
    fun `a very large attempt count cannot wrap around to a tiny delay`() {
        // The shift is capped: an unbounded exponent would overflow and could produce a
        // hot retry loop on the device that has been failing longest.
        assertEquals(RegistrationBackoff.DEFAULT_CEILING, backoff.windowFor(Int.MAX_VALUE))
    }

    @Test
    fun `every delay stays inside its window and above the floor`() {
        // 100 iterations per attempt, as the done-when requires.
        val random = Random(seed = 42)
        for (attempt in 0..10) {
            val window = backoff.windowFor(attempt)
            repeat(100) {
                val delay = backoff.delayFor(attempt, random = random)
                assertTrue(delay >= RegistrationBackoff.MINIMUM_DELAY, "below the floor: $delay")
                assertTrue(delay <= window, "outside the window: $delay > $window")
            }
        }
    }

    @Test
    fun `the same seed reproduces the same schedule`() {
        // Determinism is what makes the rest of these assertions meaningful.
        val first = (0..20).map { backoff.delayFor(it, random = Random(7)) }
        val second = (0..20).map { backoff.delayFor(it, random = Random(7)) }
        assertEquals(first, second)
    }

    @Test
    fun `two clients with different seeds do not retry together`() {
        // The stampede test. If these agreed, 5,000 clients would return in lockstep and
        // knock over the registrar that has just come up (§2.1).
        val clientA = (0..30).map { backoff.delayFor(it, random = Random(1)) }
        val clientB = (0..30).map { backoff.delayFor(it, random = Random(2)) }

        assertTrue(clientA != clientB, "two clients produced an identical retry schedule")

        val identical = clientA.zip(clientB).count { (a, b) -> a == b }
        assertTrue(identical < clientA.size / 2, "$identical of ${clientA.size} attempts collided")
    }

    @Test
    fun `many clients spread across the window rather than clustering`() {
        // Full jitter samples the WHOLE window. Fixed backoff with a little noise would
        // pass the test above and still cluster, which is the failure that matters.
        val attempt = 6
        val window = backoff.windowFor(attempt).inWholeSeconds
        val delays = (1..500).map { seed ->
            backoff.delayFor(attempt, random = Random(seed)).inWholeSeconds
        }

        val distinct = delays.toSet().size
        assertTrue(distinct > window / 4, "only $distinct distinct delays across a ${window}s window")

        // And they should not all sit in one half of the window.
        val lowerHalf = delays.count { it < window / 2 }
        assertTrue(lowerHalf in 100..400, "delays clustered in one half: $lowerHalf of 500 below midpoint")
    }

    // ---------------------------------------------------------------- Retry-After

    @Test
    fun `Retry-After is never undercut, whatever the attempt number`() {
        // Ignoring it produces a second stampede immediately (§2.1).
        val requested = 120.seconds
        for (attempt in 0..20) {
            repeat(20) { seed ->
                val delay = backoff.delayFor(attempt, retryAfter = requested, random = Random(seed))
                assertTrue(
                    delay >= requested,
                    "attempt $attempt returned $delay, shorter than the requested $requested",
                )
            }
        }
    }

    @Test
    fun `Retry-After still gets jitter, so clients do not return in lockstep`() {
        // Obeying it exactly means 5,000 clients come back in the same instant. The
        // instruction is respected; the herd is still spread.
        val delays = (1..200).map {
            backoff.delayFor(attempt = 0, retryAfter = 120.seconds, random = Random(it))
        }
        assertTrue(delays.toSet().size > 1, "every client would return at the same moment")
        assertTrue(delays.all { it >= 120.seconds })
        assertTrue(
            delays.all { it <= 120.seconds + RegistrationBackoff.DEFAULT_SERVER_JITTER },
            "jitter must be small relative to the delay the server asked for",
        )
    }

    @Test
    fun `Retry-After overrides the exponential window entirely`() {
        // A short Retry-After on a high attempt number must not be lengthened by backoff:
        // the server said when to come back.
        val delay = backoff.delayFor(attempt = 30, retryAfter = 5.seconds, random = Random(1))
        assertTrue(delay < backoff.windowFor(30), "backoff overrode an explicit Retry-After")
        assertTrue(delay >= 5.seconds)
    }

    @Test
    fun `a negative attempt is rejected rather than silently treated as zero`() {
        val error = runCatching { backoff.delayFor(attempt = -1) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun `a custom base and ceiling are honoured`() {
        val custom = RegistrationBackoff(baseDelay = 5.seconds, ceiling = 20.seconds)
        assertEquals(5.seconds, custom.windowFor(0))
        assertEquals(10.seconds, custom.windowFor(1))
        assertEquals(20.seconds, custom.windowFor(2))
        assertEquals(20.seconds, custom.windowFor(3))
    }
}

class ExpiryRefreshPolicyTest {

    @Test
    fun `the server's expiry wins when it is lower`() {
        // Refreshing against the requested value when the server granted less means the
        // binding lapses first, and inbound calls stop with nothing in the logs.
        assertEquals(
            300.seconds,
            ExpiryRefreshPolicy.effectiveExpiry(requested = 3_600.seconds, granted = 300.seconds),
        )
    }

    @Test
    fun `the client's preference stands when the server grants more`() {
        assertEquals(
            600.seconds,
            ExpiryRefreshPolicy.effectiveExpiry(requested = 600.seconds, granted = 3_600.seconds),
        )
    }

    @Test
    fun `refresh fires inside the 50 to 90 percent window`() {
        // The done-when names these three expiries explicitly.
        for (expiry in listOf(60.seconds, 300.seconds, 3_600.seconds)) {
            repeat(200) { seed ->
                val delay = ExpiryRefreshPolicy.refreshDelay(expiry, Random(seed))
                assertTrue(
                    ExpiryRefreshPolicy.isWithinWindow(delay, expiry),
                    "for $expiry the refresh delay $delay fell outside 50-90%",
                )
            }
        }
    }

    @Test
    fun `two clients on the same expiry refresh at different moments`() {
        // Otherwise clients that registered together refresh together, forever.
        val delays = (1..200).map { ExpiryRefreshPolicy.refreshDelay(3_600.seconds, Random(it)) }
        assertTrue(delays.toSet().size > 50, "only ${delays.toSet().size} distinct refresh moments")
    }

    @Test
    fun `a refresh always leaves time for a retry before the binding lapses`() {
        // The 50% floor exists so a failed refresh can be retried before expiry.
        for (expiry in listOf(60.seconds, 300.seconds, 3_600.seconds)) {
            repeat(50) { seed ->
                val delay = ExpiryRefreshPolicy.refreshDelay(expiry, Random(seed))
                assertTrue(delay < expiry, "refresh at $delay would fire after expiry $expiry")
                assertTrue(
                    expiry - delay >= expiry * 0.10,
                    "only ${expiry - delay} of headroom before $expiry",
                )
            }
        }
    }

    @Test
    fun `a very short expiry collapses to an immediate refresh rather than throwing`() {
        val delay = ExpiryRefreshPolicy.refreshDelay(1.seconds, Random(1))
        assertTrue(delay.isPositive())
        assertTrue(delay <= 1.seconds)
    }

    @Test
    fun `a non-positive expiry is rejected`() {
        val error = runCatching { ExpiryRefreshPolicy.refreshDelay(Duration.ZERO) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
