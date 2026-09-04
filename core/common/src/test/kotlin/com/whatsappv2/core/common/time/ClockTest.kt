package com.whatsappv2.core.common.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SystemClockTest {

    @Test
    fun `reports a plausible wall-clock time`() {
        // Asserted as a lower bound rather than an exact value: this is the one place
        // that reads the real clock, so there is nothing to compare it against.
        val now = SystemClock.nowEpochMillis()
        assertTrue(now > MutableClock.DEFAULT_START, "expected a time after 2023, got $now")
    }

    @Test
    fun `does not go backwards between reads`() {
        val first = SystemClock.nowEpochMillis()
        val second = SystemClock.nowEpochMillis()
        assertTrue(second >= first)
    }
}

class MutableClockTest {

    @Test
    fun `time stands still until the test moves it`() {
        val clock = MutableClock()
        val first = clock.nowEpochMillis()
        repeat(3) { clock.nowEpochMillis() }
        assertEquals(first, clock.nowEpochMillis())
    }

    @Test
    fun `advanceBy moves time forward by exactly that much`() {
        val clock = MutableClock(1_000L)
        clock.advanceBy(500L)
        assertEquals(1_500L, clock.nowEpochMillis())
        clock.advanceBy(0L)
        assertEquals(1_500L, clock.nowEpochMillis())
    }

    @Test
    fun `advanceBy refuses to go backwards, because that is never what a test means`() {
        assertFailsWith<IllegalArgumentException> { MutableClock().advanceBy(-1L) }
    }

    @Test
    fun `set moves time anywhere, including backwards for clock-skew tests`() {
        val clock = MutableClock(5_000L)
        clock.set(1_000L)
        assertEquals(1_000L, clock.nowEpochMillis())
    }

    @Test
    fun `the default start is recognisable, so a stray zero stands out`() {
        assertEquals(MutableClock.DEFAULT_START, MutableClock().nowEpochMillis())
    }

    @Test
    fun `mutators chain`() {
        assertEquals(2_000L, MutableClock().set(1_000L).advanceBy(1_000L).nowEpochMillis())
    }

    @Test
    fun `it satisfies the Clock interface`() {
        val clock: Clock = MutableClock(42L)
        assertEquals(42L, clock.nowEpochMillis())
    }
}
