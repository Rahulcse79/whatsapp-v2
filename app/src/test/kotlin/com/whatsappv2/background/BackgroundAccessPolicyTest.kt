package com.whatsappv2.background

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the "run in the background" question is asked. Once, and only once somebody has
 * something to keep running.
 */
class BackgroundAccessPolicyTest {

    @Test
    fun `asked once somebody is logged in and the app is not yet exempt`() {
        assertTrue(BackgroundAccessPolicy.shouldAsk(wantsRegistration = true, exempt = false, askedBefore = false))
    }

    @Test
    fun `not asked before anybody has logged in`() {
        // A fresh install with no account has nothing to keep running and no reason to
        // explain itself.
        assertFalse(BackgroundAccessPolicy.shouldAsk(wantsRegistration = false, exempt = false, askedBefore = false))
    }

    @Test
    fun `not asked when the app is already exempt`() {
        assertFalse(BackgroundAccessPolicy.shouldAsk(wantsRegistration = true, exempt = true, askedBefore = false))
    }

    @Test
    fun `not asked twice, whatever the answer was`() {
        // "No" is an answer. The setting stays reachable from Settings for whoever changes
        // their mind; a dialog on every launch is an app people uninstall.
        assertFalse(BackgroundAccessPolicy.shouldAsk(wantsRegistration = true, exempt = false, askedBefore = true))
    }
}
