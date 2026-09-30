package com.whatsappv2.data.chat.net

import com.google.gson.Gson
import com.whatsappv2.domain.chat.ChatAuthError
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Coral platform's failures, turned into something a screen can act on.
 *
 * The distinction these tests exist for is the 401 split: on login it means "your password
 * is wrong" and belongs under the password field; anywhere else it means "your session aged
 * out" and belongs on the sign-in screen. Collapsing them tells a signed-in user to change
 * a password that was never broken.
 */
class CoralErrorMapperTest {

    private val mapper = CoralErrorMapper(Gson())

    @Test
    fun `401 on login is a rejected credential`() {
        assertEquals(
            ChatAuthError.InvalidCredentials,
            mapper.fromStatus(401, null, CoralErrorMapper.Call.LOGIN),
        )
    }

    @Test
    fun `401 on any other call is an expired session`() {
        assertEquals(
            ChatAuthError.SessionExpired,
            mapper.fromStatus(401, null, CoralErrorMapper.Call.AUTHENTICATED),
        )
    }

    @Test
    fun `403 is an expired session too, because the user's next step is the same`() {
        assertEquals(
            ChatAuthError.SessionExpired,
            mapper.fromStatus(403, null, CoralErrorMapper.Call.AUTHENTICATED),
        )
    }

    @Test
    fun `the platform's own wording is carried through`() {
        // The real body, verified against the live deployment on 2026-09-30.
        val body = """{"error":"Full authentication is required to access this resource"}"""

        val error = assertIs<ChatAuthError.Server>(
            mapper.fromStatus(400, body, CoralErrorMapper.Call.LOGIN),
        )

        assertEquals("Full authentication is required to access this resource", error.message)
        assertEquals(400, error.httpStatus)
    }

    @Test
    fun `an HTML error page becomes a status with no message, not a fragment of markup`() {
        // A proxy or a load balancer answers HTML. The first line of an HTML document is
        // worse to show a user than the status code on its own.
        val error = assertIs<ChatAuthError.Server>(
            mapper.fromStatus(502, "<html><head><title>502 Bad Gateway</title>", CoralErrorMapper.Call.AUTHENTICATED),
        )

        assertNull(error.message)
        assertEquals(502, error.httpStatus)
    }

    @Test
    fun `an empty or absent body becomes a status with no message`() {
        assertNull(assertIs<ChatAuthError.Server>(mapper.fromStatus(500, null, CoralErrorMapper.Call.LOGIN)).message)
        assertNull(assertIs<ChatAuthError.Server>(mapper.fromStatus(500, "", CoralErrorMapper.Call.LOGIN)).message)
        assertNull(assertIs<ChatAuthError.Server>(mapper.fromStatus(500, "   ", CoralErrorMapper.Call.LOGIN)).message)
    }

    @Test
    fun `a JSON body with no error field becomes a status with no message`() {
        assertNull(
            assertIs<ChatAuthError.Server>(
                mapper.fromStatus(404, """{"path":"/services/nope"}""", CoralErrorMapper.Call.LOGIN),
            ).message,
        )
    }

    @Test
    fun `a 5xx is retryable and a 4xx is not`() {
        assertTrue(mapper.fromStatus(503, null, CoralErrorMapper.Call.AUTHENTICATED).isRetryable)
        assertTrue(!mapper.fromStatus(400, null, CoralErrorMapper.Call.AUTHENTICATED).isRetryable)
    }

    @Test
    fun `every transport failure is one retryable Network error`() {
        // Four causes, one action: check the network and try again. Four messages would be
        // four ways of saying the same thing.
        listOf(
            SocketTimeoutException("timeout"),
            IOException("Connection refused"),
            java.net.UnknownHostException("gujlogin.coraltele.com"),
        ).forEach { cause ->
            assertEquals(ChatAuthError.Network, mapper.fromTransport(cause), "for $cause")
        }
        assertTrue(ChatAuthError.Network.isRetryable)
    }
}
