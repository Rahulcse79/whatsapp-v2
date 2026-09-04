package com.whatsappv2.ui

import com.whatsappv2.ui.navigation.AppDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppDestinationTest {

    @Test
    fun `routes are unique, so no two tabs can resolve to the same screen`() {
        val routes = AppDestination.entries.map { it.route }
        assertEquals(routes.size, routes.distinct().size, "duplicate route: $routes")
    }

    @Test
    fun `every destination has a label, because the bottom bar always shows one`() {
        for (destination in AppDestination.entries) {
            assertTrue(destination.label.isNotBlank(), "${destination.name} has no label")
        }
    }

    @Test
    fun `the start destination is the dialer, the app's primary job`() {
        assertEquals(AppDestination.DIALER, AppDestination.START)
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
