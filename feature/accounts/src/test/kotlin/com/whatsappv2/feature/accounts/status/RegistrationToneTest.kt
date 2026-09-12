package com.whatsappv2.feature.accounts.status

import com.whatsappv2.core.designsystem.component.StatusTone
import com.whatsappv2.feature.accounts.list.AccountStatus
import com.whatsappv2.feature.accounts.list.label
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Which colour the Chats bar shows for each registration state.
 *
 * Asserted on the mapping rather than on the rendered dot, because colour cannot be read
 * from the semantics tree — which is the same reason every state on that chip also carries
 * words. [RegistrationIndicatorTest] covers the words.
 */
class RegistrationToneTest {

    @Test
    fun `an offline extension is red, because it is missing calls`() {
        // It was grey. Grey reads as "nothing to see", and the thing to see is that no
        // call can reach this extension until somebody does something about it.
        assertEquals(StatusTone.FAILED, AccountStatus.OFFLINE.tone())
    }

    @Test
    fun `the two red states are still told apart by their words`() {
        // Colour is never the only channel. Offline and a bad password share a dot, so the
        // sentence beside it has to carry the difference.
        assertEquals(AccountStatus.FAILED_NEEDS_ATTENTION.tone(), AccountStatus.OFFLINE.tone())
        assertNotEquals(AccountStatus.FAILED_NEEDS_ATTENTION.label(), AccountStatus.OFFLINE.label())
    }

    @Test
    fun `a retry is orange, not red — the app is handling it`() {
        assertEquals(StatusTone.CONNECTING, AccountStatus.FAILED_RETRYING.tone())
        assertEquals(StatusTone.CONNECTING, AccountStatus.REGISTERING.tone())
    }

    @Test
    fun `registered is green`() {
        assertEquals(StatusTone.ONLINE, AccountStatus.REGISTERED.tone())
    }
}
