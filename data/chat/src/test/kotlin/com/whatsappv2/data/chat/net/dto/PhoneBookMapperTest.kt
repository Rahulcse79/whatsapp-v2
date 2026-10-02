package com.whatsappv2.data.chat.net.dto

import com.whatsappv2.domain.chat.ChatContact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which field of a directory row becomes the address a message is sent to.
 *
 * This is the whole reason the mapper has its own file. Getting it wrong does not look
 * like a bug: the conversation opens, the bubble says Sent, and it is delivered to a
 * `guest-8102@guest.local` that nobody is ever signed in as. The only visible symptom is
 * that the other person never answers.
 */
class PhoneBookMapperTest {

    private fun row(
        name: String? = null,
        designation: String? = null,
        offExtension: String? = null,
        type: String? = "phone",
        department: String? = "coral-test",
    ) = PhoneBookRow(
        id = 1,
        name = name,
        type = type,
        designation = designation,
        offExtension = offExtension,
        userType = null,
        department = department,
    )

    @Test
    fun `the designation is the id, not the extension`() {
        val contact = row(designation = "mcx8102", offExtension = "8102").toChatContact()

        // The bug this pins. chat-node keys a guest identity by deviceKey, this app signs
        // in with deviceKey = userName, so `8102` addresses a phantom.
        assertEquals("mcx8102", contact?.id)
        assertEquals("mcx8102", contact?.username)
        assertEquals("8102", contact?.extension)
    }

    @Test
    fun `a live row reads 8102 and its designation, because name is null on every one`() {
        val contact = row(name = null, designation = "mcx8102", offExtension = "8102").toChatContact()

        assertEquals("8102 (mcx8102)", contact?.label)
    }

    @Test
    fun `a row with no designation falls back to the extension, which is the ec type`() {
        val contact = row(type = "ec", designation = null, offExtension = "8300").toChatContact()

        // An endpoint rather than a person: there is no chat user behind it, so the only
        // candidate for an id is the extension. It still has to be addressable.
        assertEquals("8300", contact?.id)
        assertNull(contact?.username)
        assertEquals("8300", contact?.extension)
        assertEquals("8300", contact?.label)
    }

    @Test
    fun `a row with neither is dropped rather than given an empty id`() {
        assertNull(row(designation = null, offExtension = null).toChatContact())
        assertNull(row(designation = "  ", offExtension = "").toChatContact())
    }

    @Test
    fun `a real name wins over the number for display, but never for the id`() {
        val contact: ChatContact? =
            row(name = "Rahul Singh", designation = "mcx8102", offExtension = "8102").toChatContact()

        assertEquals("Rahul Singh", contact?.displayName, "a directory that grows names should use them")
        assertEquals("mcx8102", contact?.id, "a display name must never become an address")
        // The label is still both numbers: the extension is what somebody dials and the
        // designation is what the server addresses, and a name answers neither question.
        assertEquals("8102 (mcx8102)", contact?.label)
    }

    @Test
    fun `a blank department is dropped rather than drawn as an empty line`() {
        assertNull(row(designation = "mcx8102", department = "  ").toChatContact()?.department)
    }
}
