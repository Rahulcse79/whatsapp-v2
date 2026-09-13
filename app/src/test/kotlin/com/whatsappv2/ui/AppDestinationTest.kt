package com.whatsappv2.ui

import com.whatsappv2.ui.navigation.AppDestination
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppDestinationTest {

    @Test
    fun `routes are unique, so no two destinations resolve to the same screen`() {
        val routes = AppDestination.entries.map { it.route }
        assertEquals(routes.size, routes.distinct().size, "duplicate route: $routes")
    }

    @Test
    fun `every destination has a label, because something always names it`() {
        // It used to be a bottom-bar item; now it is a button, a top-bar action or a row.
        // Either way a destination nothing can name is one nothing can be built to open.
        for (destination in AppDestination.entries) {
            assertTrue(destination.label.isNotBlank(), "${destination.name} has no label")
        }
    }

    @Test
    fun `the app opens on Chats, which is the bar's first tab`() {
        // It opened on Calls, so the first tab of a two-tab bar was never the tab you
        // landed on — which makes the user's first action every launch a correction.
        assertEquals(AppDestination.CHATS, AppDestination.START)
    }

    @Test
    fun `the start destination is the first item in the bar`() {
        // The rule rather than the value, so reordering the bar cannot quietly leave the
        // app opening on its second tab again.
        assertEquals(AppDestination.TOP_LEVEL.first(), AppDestination.START)
    }

    @Test
    fun `the bar carries Chats and Calls, and Settings is not in it`() {
        // Settings is behind the gear on Chats. A tab for it was the arrangement this
        // replaced, and a test is how that decision stays made.
        assertEquals(listOf(AppDestination.CHATS, AppDestination.HISTORY), AppDestination.TOP_LEVEL)
        assertFalse(AppDestination.SETTINGS.isTopLevel)
    }

    @Test
    fun `a bar needs at least two destinations, or it navigates nowhere`() {
        // Task 70's test, kept live: a one-item bottom bar is chrome that costs a strip of
        // every screen and switches to nothing. If Chats ever leaves, this fails and the
        // bar has to go with it rather than linger.
        assertTrue(AppDestination.TOP_LEVEL.size >= 2, "a bar with ${AppDestination.TOP_LEVEL.size} item(s)")
    }

    @Test
    fun `routes round-trip`() {
        for (destination in AppDestination.entries) {
            assertEquals(destination, AppDestination.fromRoute(destination.route))
        }
        assertNull(AppDestination.fromRoute("nope"))
        assertNull(AppDestination.fromRoute(null))
    }
}
