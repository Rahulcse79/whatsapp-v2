package com.whatsappv2.feature.dialer

import kotlin.test.Test
import kotlin.test.assertEquals

/** The redial shortcuts, until Task 47's call log replaces the source. */
class RecentDialsTest {

    @Test
    fun `the most recent dial comes first`() {
        val recents = RecentDials()

        recents.record("1001")
        recents.record("1002")

        assertEquals(listOf("1002", "1001"), recents.recent.value)
    }

    @Test
    fun `redialling moves an entry to the front rather than duplicating it`() {
        // A redial list where the same extension appears four times is a list with one
        // useful entry.
        val recents = RecentDials()

        recents.record("1001")
        recents.record("1002")
        recents.record("1001")

        assertEquals(listOf("1001", "1002"), recents.recent.value)
    }

    @Test
    fun `case does not make a second entry`() {
        val recents = RecentDials()

        recents.record("sip:Carol@example.com")
        recents.record("sip:carol@example.com")

        assertEquals(listOf("sip:carol@example.com"), recents.recent.value)
    }

    @Test
    fun `the list is capped, because a row of shortcuts is what it is for`() {
        val recents = RecentDials()

        repeat(TOO_MANY) { recents.record("100$it") }

        assertEquals(CAP, recents.recent.value.size)
        assertEquals("100${TOO_MANY - 1}", recents.recent.value.first())
    }

    @Test
    fun `blank input is not a dial`() {
        val recents = RecentDials()

        recents.record("   ")

        assertEquals(emptyList(), recents.recent.value)
    }

    @Test
    fun `surrounding whitespace is trimmed, so two spellings are one entry`() {
        val recents = RecentDials()

        recents.record(" 1001 ")
        recents.record("1001")

        assertEquals(listOf("1001"), recents.recent.value)
    }

    private companion object {
        const val CAP = 4
        const val TOO_MANY = 7
    }
}
