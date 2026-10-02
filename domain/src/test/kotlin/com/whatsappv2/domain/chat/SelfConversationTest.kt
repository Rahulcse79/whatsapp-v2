package com.whatsappv2.domain.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spotting the conversations an earlier build addressed to the user themselves.
 *
 * Read off the live server on 2 Oct 2026: signed in as `mcx8102`, chat-node returned a
 * conversation whose counterparty was `guest-8102@guest.local`. Unwrapped, that handle is
 * `8102` — this user's own extension. The row looked like a colleague and its call button
 * dialled the handset it was pressed on.
 */
class SelfConversationTest {

    @Test
    fun `the user's own extension is themselves, which is how the phantom presents`() {
        // The exact live case: addressed by extension, signed in by designation.
        assertTrue(isSelfConversation(handle = "8102", username = "mcx8102", extension = "8102"))
    }

    @Test
    fun `the user's own designation is themselves too`() {
        assertTrue(isSelfConversation(handle = "mcx8102", username = "mcx8102", extension = "8102"))
    }

    @Test
    fun `an actual colleague is not`() {
        assertFalse(isSelfConversation(handle = "mcx8101", username = "mcx8102", extension = "8102"))
        assertFalse(isSelfConversation(handle = "8101", username = "mcx8102", extension = "8102"))
    }

    @Test
    fun `a number that merely contains the extension is not`() {
        // `81020` is somebody else. Matching on containment rather than equality would hide
        // a real colleague's chat, which is a worse failure than showing a phantom.
        assertFalse(isSelfConversation(handle = "81020", username = "mcx8102", extension = "8102"))
        assertFalse(isSelfConversation(handle = "mcx81021", username = "mcx8102", extension = "8102"))
    }

    @Test
    fun `an unknown identity hides nobody`() {
        // Before the session loads there is nothing to compare against, and hiding rows on a
        // null would empty the whole list for the moment it takes to read the session.
        assertFalse(isSelfConversation(handle = "8102", username = null, extension = null))
        assertFalse(isSelfConversation(handle = null, username = "mcx8102", extension = "8102"))
    }

    @Test
    fun `blanks and whitespace do not match each other into a false positive`() {
        assertFalse(isSelfConversation(handle = "  ", username = "mcx8102", extension = "8102"))
        assertFalse(isSelfConversation(handle = "8102", username = "", extension = ""))
        // Padding on either side is still the same person.
        assertTrue(isSelfConversation(handle = " 8102 ", username = "mcx8102", extension = " 8102 "))
    }

    @Test
    fun `case does not make somebody a different person`() {
        assertTrue(isSelfConversation(handle = "MCX8102", username = "mcx8102", extension = "8102"))
    }
}
