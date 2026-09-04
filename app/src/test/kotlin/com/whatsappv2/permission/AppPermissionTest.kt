package com.whatsappv2.permission

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task 15 done-when #2: every permission must have a rationale **and** a defined
 * behaviour when it is refused.
 *
 * Asserted over the whole enum rather than per case, so a permission added later cannot
 * be shipped without both — which is exactly how an app ends up dead-ended on a screen
 * with no explanation.
 */
class AppPermissionTest {

    @Test
    fun `every permission states why it is needed`() {
        for (permission in AppPermission.entries) {
            assertTrue(permission.title.isNotBlank(), "${permission.name} has no title")
            assertTrue(
                permission.rationale.length > MINIMUM_EXPLANATION,
                "${permission.name} has no usable rationale",
            )
        }
    }

    @Test
    fun `every permission states what happens if it is refused`() {
        for (permission in AppPermission.entries) {
            assertTrue(
                permission.deniedExplanation.length > MINIMUM_EXPLANATION,
                "${permission.name} does not say what happens when it is denied",
            )
        }
    }

    @Test
    fun `only the microphone and call management block the app`() {
        // Everything else must degrade rather than dead-end: a user who refuses contacts
        // should still be able to place calls.
        val blocking = AppPermission.entries.filterNot { it.isOptional }.toSet()
        assertEquals(setOf(AppPermission.RECORD_AUDIO, AppPermission.MANAGE_OWN_CALLS), blocking)
    }

    @Test
    fun `version-gated permissions declare the version that introduced them`() {
        assertEquals(TIRAMISU, AppPermission.POST_NOTIFICATIONS.minimumSdk)
        assertEquals(ANDROID_S, AppPermission.BLUETOOTH_CONNECT.minimumSdk)
    }

    @Test
    fun `MANAGE_OWN_CALLS is not treated as a runtime permission`() {
        // It is declared `normal` protection level and granted at install. Requesting it
        // at run time returns granted immediately, which looks like a working flow while
        // verifying nothing.
        assertTrue(!AppPermission.MANAGE_OWN_CALLS.isRuntimePermission)
        for (other in AppPermission.entries - AppPermission.MANAGE_OWN_CALLS) {
            assertTrue(other.isRuntimePermission, "${other.name} should be a runtime permission")
        }
    }

    @Test
    fun `manifest names are distinct, so two entries cannot fight over one permission`() {
        val names = AppPermission.entries.map { it.manifestPermission }
        assertEquals(names.size, names.distinct().size, "duplicate manifest permission")
    }

    private companion object {
        const val MINIMUM_EXPLANATION = 20
        const val TIRAMISU = 33
        const val ANDROID_S = 31
    }
}
