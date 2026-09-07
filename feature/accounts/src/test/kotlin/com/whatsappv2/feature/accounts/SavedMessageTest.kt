package com.whatsappv2.feature.accounts

import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.usecase.RegistrationAttempt
import com.whatsappv2.feature.accounts.editor.AccountEditorEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a save actually tells the user (Task 73).
 *
 * Three outcomes, three sentences, and only one of them is a warning. §5.1 is explicit
 * that a silent partial re-registration is a bug: an edit releases the old binding and
 * takes out a new one, and if that second half failed the account is stored but the user
 * is no longer reachable. "Saved" on its own would be true and useless.
 */
class SavedMessageTest {

    @Test
    fun `a new account that registered says both things`() {
        val message = saved(RegistrationAttempt.Succeeded).toMessage()

        assertEquals("Work saved and registered", message.text)
        assertFalse(message.isWarning)
    }

    @Test
    fun `an edit that re-registered says it re-registered`() {
        // Distinct wording because it is a distinct event: the old binding was released
        // first, and the user's phone was briefly unreachable in between.
        val message = saved(RegistrationAttempt.Succeeded, unregisteredFirst = true).toMessage()

        assertEquals("Work saved and re-registered", message.text)
        assertFalse(message.isWarning)
    }

    @Test
    fun `an account deliberately left logged out says only that it was saved`() {
        // Saving must not log someone back in behind their back, so there is nothing more
        // to report than the save.
        val message = saved(RegistrationAttempt.NotAttempted).toMessage()

        assertEquals("Work saved", message.text)
        assertFalse(message.isWarning)
    }

    @Test
    fun `a failed registration is a warning, and names the cause`() {
        // The case the whole type exists for. The account is stored and the user is not
        // receiving calls; a message that disappears on a timer is most of the way to
        // silent, which is why this one is flagged to stay on screen.
        val message = saved(RegistrationAttempt.Rejected(SipError.AuthenticationFailed(UNAUTHORIZED))).toMessage()

        assertTrue(message.isWarning, "a failed re-registration was not flagged")
        assertTrue(message.text.startsWith("Work saved, but registration failed"), message.text)
    }

    private companion object {
        const val UNAUTHORIZED = 401
    }

    private fun saved(
        registration: RegistrationAttempt,
        unregisteredFirst: Boolean = false,
    ) = AccountEditorEvent.Saved(
        label = "Work",
        unregisteredFirst = unregisteredFirst,
        registration = registration,
    )
}
