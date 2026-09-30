package com.whatsappv2.data.chat.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which requests carry `Authorization`, and which must not.
 *
 * Run against a real `MockWebServer` rather than a stubbed chain, because the assertion is
 * about the bytes that leave the socket — a chain stub would prove the interceptor built a
 * header, not that OkHttp sent one.
 */
class BearerInterceptorTest {

    private val server = MockWebServer()
    private var token: String? = "a-token"
    private val client = OkHttpClient.Builder()
        .addInterceptor(BearerInterceptor { token })
        .build()

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun post(path: String): okhttp3.mockwebserver.RecordedRequest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.newCall(
            Request.Builder()
                .url(server.url(path))
                .post("{}".toRequestBody())
                .build(),
        ).execute().close()
        return server.takeRequest()
    }

    @Test
    fun `an ordinary call carries the bearer token`() {
        val request = post("/services/api/v2/uc/phoneBook/listByDepartment")

        assertEquals("Bearer a-token", request.getHeader("Authorization"))
    }

    @Test
    fun `login never carries one, even when a stale token is present`() {
        // The usual case, not a corner: somebody was signed in, their session aged out, and
        // the sign-in screen came back. Handing the platform the credential this request is
        // trying to replace is how a correct password gets rejected.
        assertNull(post("/services/app/v2/auth/login").getHeader("Authorization"))
    }

    @Test
    fun `no session means no header rather than an empty one`() {
        token = null

        assertNull(post("/services/api/v2/uc/phoneBook/listByDepartment").getHeader("Authorization"))
    }

    @Test
    fun `the token is read per request, not captured when the client was built`() {
        // The interceptor outlives every session: sign in, sign out, sign in again all
        // happen behind one OkHttpClient. A captured value would send the first token for
        // ever, which is a bug that only appears on the second sign-in.
        assertEquals("Bearer a-token", post("/services/api/v2/uc/phoneBook/x").getHeader("Authorization"))

        token = "a-different-token"

        assertEquals("Bearer a-different-token", post("/services/api/v2/uc/phoneBook/x").getHeader("Authorization"))
    }
}
