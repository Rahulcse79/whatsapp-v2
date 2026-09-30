package com.whatsappv2.domain.chat

import com.whatsappv2.core.common.secret.Secret
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `a contact carries what a row needs and nothing more`() {
        val contact = ChatContact(
            id = "1001",
            displayName = "Rahul Singh",
            extension = "1001",
            department = "coral-test",
            avatarUrl = null,
        )

        assertEquals("1001", contact.id)
        assertEquals(contact, contact.copy())
        // The id is what ChatSdk.openDirectConversation takes, and it is deliberately
        // separate from the extension: they are two different things in this deployment,
        // even when a directory happens to make them equal.
        assertEquals(contact.extension, contact.id)
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
