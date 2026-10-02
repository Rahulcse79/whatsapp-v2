package com.whatsappv2.domain.chat

import com.whatsappv2.core.common.secret.Secret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The session's two promises: a token that cannot be logged by accident, and an expiry
 * that never signs somebody out early.
 */
class ChatSessionTest {

    private fun session(expiresAtMs: Long?) = ChatSession(
        userId = "sample-user",
        displayName = "Sample User",
        token = Secret("a-bearer-token"),
        expiresAtMs = expiresAtMs,
        deviceId = "BF6625949EAA4D5F94CAA18641BE8E74",
    )

    @Test
    fun `a token in the past is expired`() {
        assertTrue(session(expiresAtMs = 1_000).isExpiredAt(nowMs = 2_000))
    }

    @Test
    fun `a token expiring exactly now is expired, not still valid`() {
        // The boundary belongs on the safe side: a token the server considers dead at this
        // instant must not be sent one more time and reported to the user as a 401.
        assertTrue(session(expiresAtMs = 2_000).isExpiredAt(nowMs = 2_000))
    }

    @Test
    fun `a token in the future is not expired`() {
        assertFalse(session(expiresAtMs = 5_000).isExpiredAt(nowMs = 2_000))
    }

    @Test
    fun `no expiry is never expired`() {
        // "Assume it is good until a 401 says otherwise" is the only honest behaviour
        // without an expiry. Guessing one would sign the user out while their token worked.
        assertFalse(session(expiresAtMs = null).isExpiredAt(nowMs = Long.MAX_VALUE))
    }

    @Test
    fun `the token does not appear in the session's own toString`() {
        // The property that makes Secret worth having: a session logged whole - in a crash
        // report, a string template, a debugger - does not carry a usable credential.
        val rendered = session(expiresAtMs = null).toString()

        assertFalse("a-bearer-token" in rendered, "the token leaked through toString: $rendered")
        assertTrue("sample-user" in rendered, "the session is unidentifiable in a log")
    }

    @Test
    fun `credentials mask their password the same way`() {
        val rendered = ChatCredentials(username = "sample-user", password = Secret("not-a-real-password")).toString()

        assertFalse("not-a-real-password" in rendered, "the password leaked through toString: $rendered")
    }

    @Test
    fun `a contact is addressed by its designation and labelled with both`() {
        val contact = ChatContact(
            id = "mcx8102",
            username = "mcx8102",
            displayName = "8102",
            extension = "8102",
            department = "coral-test",
            avatarUrl = null,
        )

        // The id is what ChatSdk.openDirectConversation takes, and it is the DESIGNATION,
        // not the extension: chat-node keys a guest identity by the username, so a
        // conversation opened against `8102` reaches `guest-8102@guest.local` — a second
        // identity nobody signs in as.
        assertEquals("mcx8102", contact.id)
        assertNotEquals(contact.extension, contact.id)
        assertEquals(contact, contact.copy())
        assertEquals("8102 (mcx8102)", contact.label)
    }
}

/** The one rule for writing a person down, and what it does when half of it is missing. */
class ChatIdentityLabelTest {

    @Test
    fun `both halves when both are known`() {
        assertEquals("8102 (mcx8102)", chatIdentityLabel("8102", "mcx8102", fallback = "ignored"))
    }

    @Test
    fun `the extension alone rather than an empty bracket`() {
        assertEquals("8102", chatIdentityLabel("8102", null, fallback = "ignored"))
        assertEquals("8102", chatIdentityLabel("8102", "  ", fallback = "ignored"))
    }

    @Test
    fun `the username alone for a row the directory gave no extension`() {
        assertEquals("mcx8102", chatIdentityLabel(null, "mcx8102", fallback = "ignored"))
        assertEquals("mcx8102", chatIdentityLabel("", "mcx8102", fallback = "ignored"))
    }

    @Test
    fun `the fallback only when there is neither`() {
        assertEquals("Reception", chatIdentityLabel(null, null, fallback = "Reception"))
        assertEquals("Reception", chatIdentityLabel(" ", " ", fallback = "Reception"))
    }

    @Test
    fun `a contact with no extension labels itself by its username, not its display name`() {
        val contact = ChatContact(
            id = "mcx8102",
            username = "mcx8102",
            displayName = "Rahul Singh",
            extension = null,
            department = null,
            avatarUrl = null,
        )

        assertEquals("mcx8102", contact.label)
    }
}

/** The signed-in user's own label — the same rule, applied to the login response. */
class ChatSessionExtensionLabelTest {

    private fun session(extension: ChatExtension?) = ChatSession(
        userId = "mcx8101",
        displayName = null,
        token = Secret("not-a-real-token"),
        expiresAtMs = null,
        deviceId = "DEVICE",
        extension = extension,
    )

    private fun extension(number: String) = ChatExtension(
        number = number,
        name = null,
        sipPassword = null,
        domain = "pbx.example",
        port = 5061,
        secure = false,
    )

    @Test
    fun `the header reads the extension and the username that signed in`() {
        assertEquals("8101 (mcx8101)", session(extension("8101")).extensionLabel)
    }

    @Test
    fun `a login with no extension has no label, rather than an empty one`() {
        assertEquals(null, session(extension = null).extensionLabel)
    }
}

/** Which failures a retry can do anything about. */
class ChatAuthErrorTest {

    @Test
    fun `a dead network is retryable`() {
        assertTrue(ChatAuthError.Network.isRetryable)
    }

    @Test
    fun `a 5xx is retryable, because the fault is the server's and may pass`() {
        assertTrue(ChatAuthError.Server(500, "Internal error").isRetryable)
        assertTrue(ChatAuthError.Server(503, null).isRetryable)
    }

    @Test
    fun `a 4xx is not, because retrying the same request changes nothing`() {
        assertFalse(ChatAuthError.Server(400, "Bad request").isRetryable)
        assertFalse(ChatAuthError.Server(404, null).isRetryable)
    }

    @Test
    fun `a wrong password is not retryable - the same one will be wrong again`() {
        assertFalse(ChatAuthError.InvalidCredentials.isRetryable)
    }

    @Test
    fun `an expired session is not retryable - it needs a sign-in, not another go`() {
        assertFalse(ChatAuthError.SessionExpired.isRetryable)
    }

    @Test
    fun `a build with no cipher key is not retryable - it will not grow one`() {
        assertFalse(ChatAuthError.CryptoUnavailable.isRetryable)
        assertFalse(ChatAuthError.NotConfigured.isRetryable)
    }
}
