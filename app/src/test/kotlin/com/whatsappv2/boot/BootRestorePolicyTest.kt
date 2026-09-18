package com.whatsappv2.boot

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The boot rule, asserted rather than rebooted.
 *
 * The receiver's only decision is whether a start is worth making, and the answer is the
 * same one `RestoreRegistrationsUseCase` gives: an account the user logged in and never
 * logged out. A phone that has never had an account must not show a notification on
 * every boot.
 */
class BootRestorePolicyTest {

    @Test
    fun `no accounts means no start`() {
        assertFalse(BootRestorePolicy.shouldStart(emptyList()))
    }

    @Test
    fun `accounts that were all logged out mean no start`() {
        assertFalse(BootRestorePolicy.shouldStart(listOf(false, false)))
    }

    @Test
    fun `one logged-in account among logged-out ones is enough`() {
        assertTrue(BootRestorePolicy.shouldStart(listOf(false, true, false)))
    }
}
