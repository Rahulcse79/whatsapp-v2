package com.whatsappv2.permission

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionStatusTest {

    @Test
    fun `only PermanentlyDenied needs the settings route`() {
        // Task 15 done-when #3: a permanently denied permission must be sent to app
        // settings, because the system dialog will never appear again and a button that
        // merely re-requests would silently do nothing.
        assertTrue(PermissionStatus.PermanentlyDenied.requiresSettings)
        assertFalse(PermissionStatus.Denied.requiresSettings)
        assertFalse(PermissionStatus.NotRequested.requiresSettings)
        assertFalse(PermissionStatus.Granted.requiresSettings)
    }

    @Test
    fun `only Granted counts as held`() {
        assertTrue(PermissionStatus.Granted.isGranted)
        assertFalse(PermissionStatus.Denied.isGranted)
        assertFalse(PermissionStatus.NotRequested.isGranted)
        assertFalse(PermissionStatus.PermanentlyDenied.isGranted)
    }
}
