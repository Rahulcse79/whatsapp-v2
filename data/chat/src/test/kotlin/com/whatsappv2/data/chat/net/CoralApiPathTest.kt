package com.whatsappv2.data.chat.net

import com.google.gson.Gson
import com.whatsappv2.data.chat.net.dto.LoginRequest
import com.whatsappv2.data.chat.net.dto.PhoneBookRequest
import com.whatsappv2.data.chat.net.dto.SearchRequest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where [CoralApi]'s two calls actually land, and what they carry.
 *
 * ## Why this builds its own Retrofit instead of going through [CoralClientFactory]
 *
 * Because the factory only accepts a `CoralServerUrl`, and a `CoralServerUrl` is always
 * `https://` — that refusal is the product rule, tested in `:domain`, and not something to
 * weaken for a test. `MockWebServer` here speaks plain HTTP, so this wires the same
 * interface, the same converter and the same interceptor against it by hand.
 *
 * What that leaves under test is exactly the risk worth testing: **path composition**. A
 * leading slash on a Retrofit path replaces the base URL's path, so `"/app/v2/auth/login"`
 * would silently drop `/services/` and 404 at run time with nothing in the code that looks
 * wrong. The chat SDK's own `ApiService` carries the same warning for the same reason.
 */
class CoralApiPathTest {

    private val server = MockWebServer()
    private var token: String? = null

    private val api: CoralApi = Retrofit.Builder()
        .baseUrl(server.url("/services/"))
        .client(OkHttpClient.Builder().addInterceptor(BearerInterceptor { token }).build())
        .addConverterFactory(GsonConverterFactory.create(Gson()))
        .build()
        .create(CoralApi::class.java)

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun respond(code: Int = 200, body: String = "{}") {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    @Test
    fun `login lands under services, not at the root`() = runTest {
        respond()

        api.login(LoginRequest("u", "p", "D"))

        assertEquals("/services/app/v2/auth/login", server.takeRequest().path)
    }

    @Test
    fun `the phonebook lands under services too`() = runTest {
        respond()

        api.listByDepartment(PhoneBookRequest(SearchRequest(), listOf("coral-test")))

        assertEquals("/services/api/v2/uc/phoneBook/listByDepartment", server.takeRequest().path)
    }

    @Test
    fun `the login body carries exactly the three fields the platform specifies`() = runTest {
        respond()

        // Already ciphertext by this point - the cipher runs before the request is built.
        api.login(LoginRequest(username = "ENCRYPTED-U", password = "ENCRYPTED-P", deviceId = "DEVICE"))

        assertEquals(
            """{"username":"ENCRYPTED-U","password":"ENCRYPTED-P","deviceId":"DEVICE"}""",
            server.takeRequest().body.readUtf8(),
        )
    }

    @Test
    fun `login never carries Authorization, even with a stale token present`() = runTest {
        // The usual case, not a corner: somebody was signed in, the session aged out, and
        // the sign-in screen came back. Handing the platform the credential this request
        // is replacing is how a correct password gets rejected.
        token = "a-stale-token"
        respond()

        api.login(LoginRequest("u", "p", "D"))

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `the phonebook body carries the search request and the department list`() = runTest {
        respond()

        api.listByDepartment(
            PhoneBookRequest(searchRequest = SearchRequest(), departmentList = listOf("coral-test", "another")),
        )

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.startsWith("""{"searchRequest":{"""), "the envelope changed shape: $body")
        assertTrue(
            """"departmentList":["coral-test","another"]""" in body,
            "the department list is what the server actually reads, and it is missing: $body",
        )
    }

    @Test
    fun `the status code survives, because on this platform it is the answer`() = runTest {
        // A bare body would throw it away, and a 401 on login means something different
        // from a 401 anywhere else - which is the whole of CoralErrorMapper's job.
        respond(code = 401, body = """{"error":"Bad credentials"}""")

        val response = api.login(LoginRequest("u", "p", "D"))

        assertEquals(401, response.code())
        assertEquals("""{"error":"Bad credentials"}""", response.errorBody()?.string())
    }

    @Test
    fun `a rejected login arrives as HTTP 200 with status ERROR, and is read as a failure`() = runTest {
        // The trap this envelope sets. The platform answers 200 and puts the verdict in
        // the body, so a client branching only on the HTTP code reports a wrong password
        // as a successful sign-in carrying a null token.
        respond(
            body = """{"status":"ERROR","message":"Login Failed","messageDetail":"Bad credentials","data":null}""",
        )

        val response = api.login(LoginRequest("u", "p", "D"))
        val envelope = response.body()

        assertTrue(response.isSuccessful, "the transport succeeded")
        assertTrue(envelope != null && !envelope.isOk, "the operation did not, and the envelope says so")
        assertEquals("Bad credentials", envelope?.detail)
        assertNull(envelope?.data)
    }

    @Test
    fun `a successful login yields the token and the departments it came with`() = runTest {
        // departmentList arrives HERE and nowhere else - the platform has no endpoint
        // that lists a user's departments, which is what the contacts call needs.
        respond(
            body = """{"status":"OK","message":"ok","messageDetail":null,"data":{
                "token":"jwt-abc","refreshToken":"r-1","userId":7,"userName":"mcx10001",
                "fullName":"Sample User","extension":"10001",
                "departmentList":[{"id":1,"department":"coral-test"}]}}""",
        )

        val data = api.login(LoginRequest("u", "p", "D")).body()?.data

        assertEquals("jwt-abc", data?.token)
        assertEquals("mcx10001", data?.userName)
        assertEquals(listOf("coral-test"), data?.departmentList?.map { it.department })
    }

    @Test
    fun `unknown response fields are ignored rather than fatal`() = runTest {
        // The real model has about seventy fields - menus, map centres, TURN credentials.
        // Naming only the few this app uses must not make the other sixty a parse error.
        respond(body = """{"status":"OK","data":{"token":"t","somethingNew":{"a":[1,2]}}}""")

        assertEquals("t", api.login(LoginRequest("u", "p", "D")).body()?.data?.token)
    }
}
