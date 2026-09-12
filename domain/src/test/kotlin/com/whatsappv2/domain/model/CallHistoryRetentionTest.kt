package com.whatsappv2.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallHistoryRetentionTest {

    @Test
    fun `a fresh install keeps twenty days`() {
        assertEquals(TWENTY, AppSettings.DEFAULT.callHistoryRetention.days)
        assertEquals(CallHistoryRetention.DEFAULT, AppSettings.DEFAULT.callHistoryRetention)
    }

    @Test
    fun `the cutoff is the retention counted back from now`() {
        val cutoff = CallHistoryRetention.ofDays(SEVEN).cutoffEpochMillis(NOW)

        assertEquals(NOW - SEVEN * CallHistoryRetention.MILLIS_PER_DAY, cutoff)
    }

    @Test
    fun `keeping everything has no cutoff`() {
        // Null, not the epoch: a caller must be able to tell "nothing to delete" from
        // "delete everything before 1970", and only one of those is a query worth running.
        assertTrue(CallHistoryRetention.KEEP_EVERYTHING.keepsEverything)
        assertNull(CallHistoryRetention.KEEP_EVERYTHING.cutoffEpochMillis(NOW))
        assertFalse(CallHistoryRetention.DEFAULT.keepsEverything)
    }

    @Test
    fun `any number of days is honoured, not only the presets`() {
        // "Keep it a bit longer" is a thing people mean, and a stored value from a build
        // with a different list is still a number of days this build understands.
        assertEquals(ELEVEN, CallHistoryRetention.ofDays(ELEVEN).days)
        assertFalse(CallHistoryRetention.ofDays(ELEVEN) in CallHistoryRetention.PRESETS)
    }

    @Test
    fun `a value that cannot mean what it says is clamped`() {
        // Negative reads as forever; absurdly large is capped so days-to-millis cannot
        // overflow into a cutoff in the far past or future.
        assertTrue(CallHistoryRetention.ofDays(-1).keepsEverything)
        assertEquals(CallHistoryRetention.MAXIMUM_DAYS, CallHistoryRetention.ofDays(Int.MAX_VALUE).days)
    }

    @Test
    fun `the presets read as a scale, with forever last`() {
        val days = CallHistoryRetention.PRESETS.dropLast(1).map { it.days }

        assertEquals(days.sorted(), days)
        assertTrue(days.all { it > 0 })
        assertEquals(CallHistoryRetention.KEEP_EVERYTHING, CallHistoryRetention.PRESETS.last())
        // The default must be offered, or the dropdown could not show what a fresh
        // install is set to.
        assertTrue(CallHistoryRetention.DEFAULT in CallHistoryRetention.PRESETS)
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val SEVEN = 7
        const val ELEVEN = 11
        const val TWENTY = 20
    }
}
