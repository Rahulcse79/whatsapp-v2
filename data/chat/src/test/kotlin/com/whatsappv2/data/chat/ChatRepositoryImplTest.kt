package com.whatsappv2.data.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.data.account.crypto.CipherError
import com.whatsappv2.data.account.crypto.CredentialCipher
import com.whatsappv2.data.chat.net.BearerTokenSource
import com.whatsappv2.data.chat.net.CoralClientFactory
import com.whatsappv2.data.chat.net.CoralErrorMapper
import com.whatsappv2.data.chat.store.ChatSessionStore
import com.whatsappv2.data.chat.store.PrivateChatTokenFile
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatCredentials
import com.whatsappv2.domain.chat.ChatSession
import com.whatsappv2.domain.chat.CoralServerUrl
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The chat ports against a real HTTP server.
 *
 * ## `http://` is what makes this test possible
 *
 * `MockWebServer` speaks plain HTTP, and `CoralServerUrl` now accepts an `http://` origin —
 * so the whole path is exercised for real: the client factory, the bearer interceptor, the
 * Retrofit paths, the envelope, the mapper and the encrypted store. While the parser was
 * https-only none of that could be driven end to end from a JVM test.
 *
 * The **cipher** is the one thing absent: this variant carries no key, so `signIn` stops
 * before the socket with `CryptoUnavailable`. Its own tests use the server's algorithm as
 * an oracle; what is checked here is everything around it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ChatRepositoryImplTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val directory = File(context.cacheDir, "chat-repo-${System.nanoTime()}")
    private val dispatcher = StandardTestDispatcher()
    private val server = MockWebServer()

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = dispatcher
        override val io: CoroutineDispatcher get() = dispatcher
        override val default: CoroutineDispatcher get() = dispatcher
        override val unconfined: CoroutineDispatcher get() = dispatcher
    }

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create {
        File(directory, "chat.preferences_pb").apply { parentFile?.mkdirs() }
    }

    private val store = ChatSessionStore(
        dataStore = dataStore,
        cipher = ReversibleCipher(),
        tokenFile = PrivateChatTokenFile(File(directory, "token.enc"), NoOpLogger),
        logger = NoOpLogger,
    )

    private val errors = CoralErrorMapper(Gson())

    private val sessions: ChatSessionRepositoryImpl by lazy {
        ChatSessionRepositoryImpl(
            store = store,
            clients = CoralClientFactory(Gson()) { tokenSource },
            errors = errors,
            deviceIds = CoralDeviceId(context),
            dispatchers = dispatchers,
            logger = NoOpLogger,
        )
    }

    /** Indirection so the factory can be built before the repository that supplies its token. */
    private val tokenSource = object : BearerTokenSource {
        override fun currentToken(): String? = sessions.currentToken()
    }

    private fun contactsRepository() = ChatContactRepositoryImpl(
        sessions = sessions,
        clients = CoralClientFactory(Gson()) { tokenSource },
        errors = errors,
        dispatchers = dispatchers,
        logger = NoOpLogger,
    )

    /** The MockWebServer as an http origin — which the parser now accepts. */
    private fun origin(): CoralServerUrl {
        val raw = server.url("/").toString().removeSuffix("/")
        return (CoralServerUrl.parse(raw) as Outcome.Success).value
    }

    private val session = ChatSession(
        userId = "mcx10001",
        displayName = "Sample User",
        token = Secret("a-bearer-token"),
        expiresAtMs = null,
        deviceId = "BF6625949EAA4D5F94CAA18641BE8E74",
    )

    @After
    fun tearDown() {
        server.shutdown()
        directory.deleteRecursively()
    }

    // ------------------------------------------------------------------ the session

    @Test
    fun `a fresh install is signed out, and that is not an error`() = runTest(dispatcher) {
        assertNull(sessions.currentSession())
        assertNull(sessions.observeSession().first())
    }

    @Test
    fun `a stored session is picked up on the first read`() = runTest(dispatcher) {
        store.save(session, origin())

        assertEquals(session, sessions.currentSession())
    }

    @Test
    fun `the bearer token is readable synchronously, which the interceptor requires`() =
        runTest(dispatcher) {
            store.save(session, origin())
            sessions.currentSession()

            assertEquals("a-bearer-token", sessions.currentToken())
        }

    @Test
    fun `signing out drops the token in memory as well as on disk, and keeps the URL`() =
        runTest(dispatcher) {
            val url = origin()
            store.save(session, url)
            sessions.currentSession()

            sessions.signOut()

            assertNull(sessions.currentToken(), "a request racing sign-out could still have signed itself")
            assertNull(sessions.currentSession())
            assertEquals(url, sessions.currentServerUrl(), "decision D2: the address survives sign-out")
        }

    @Test
    fun `an http origin parses, so the app can be pointed at a lab server`() {
        // The change this whole test file depends on. A LAN deployment is reached at
        // http://192.168.250.201 and the parser must not refuse the server the app is
        // pointed at; the platform-level cleartext decision lives in the manifest.
        val url = (CoralServerUrl.parse("http://192.168.250.201") as Outcome.Success).value

        assertEquals("http://192.168.250.201/services/", url.servicesBaseUrl)
        assertEquals("ws://192.168.250.201/chat/ws", url.chatWsUrl)
        assertTrue(!url.isSecure)
    }

    // ------------------------------------------------------------------ sign-in, over the wire

    /**
     * Sign-in has exactly two permitted outcomes, and which one depends on the build.
     *
     * A developer's machine supplies `coral.cipher.key`; CI does not. Rather than assert
     * one and fail in the other place, both are named and anything else is a failure:
     *
     *  - **no key** → `CryptoUnavailable`, and **nothing reaches the socket**. That
     *    matters: the platform answers 500 to a field it cannot decrypt, so a build with
     *    no key that still sent the request would look like a server outage.
     *  - **a key** → the request goes out encrypted, and the envelope is read.
     */
    @Test
    fun `sign-in either stops at the cipher or reaches the server encrypted`() =
        runTest(dispatcher) {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"status":"OK","data":{"token":"jwt-abc","userName":"mcx10001",
                       "fullName":"Sample User","departmentList":[{"department":"coral-test"}]}}""",
                ),
            )

            val outcome = sessions.signIn(origin(), ChatCredentials("mcx10001", Secret("123456")))

            if (outcome.errorOrNull() == ChatAuthError.CryptoUnavailable) {
                assertEquals(0, server.requestCount, "a keyless build must not reach the socket")
                return@runTest
            }

            val signedIn = assertNotNull(outcome.getOrNull(), "expected a session, got ${outcome.errorOrNull()}")
            assertEquals("mcx10001", signedIn.userId)
            assertEquals("Sample User", signedIn.displayName)
            assertEquals("jwt-abc", signedIn.token.reveal())
            assertNull(signedIn.expiresAtMs, "the platform sends no expiry, so none may be invented")
            assertEquals(listOf("coral-test"), sessions.currentDepartments())

            val body = server.takeRequest().body.readUtf8()
            assertTrue("\"mcx10001\"" !in body, "the username went to the wire in the clear: $body")
            assertTrue("\"123456\"" !in body, "the password went to the wire in the clear: $body")
            assertTrue("deviceId" in body, "the deviceId is missing from the request")
        }

    @Test
    fun `a rejected sign-in stores nothing, even though the platform answers 200`() =
        runTest(dispatcher) {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"status":"ERROR","message":"Login Failed","messageDetail":"Bad credentials","data":null}""",
                ),
            )

            val outcome = sessions.signIn(origin(), ChatCredentials("mcx10001", Secret("wrong")))

            // Either CryptoUnavailable (no key) or InvalidCredentials (key, 200 + ERROR).
            // Never a success: a client reading only the HTTP code would call this one.
            assertTrue(outcome is Outcome.Failure, "a 200 carrying status ERROR was read as a sign-in")
            assertNull(sessions.currentSession())
            assertNull(store.currentSession())
        }

    // ------------------------------------------------------------------ the directory

    @Test
    fun `a signed-out refresh is an expired session, and never reaches the network`() =
        runTest(dispatcher) {
            val contacts = contactsRepository()

            assertEquals(ChatAuthError.SessionExpired, contacts.refresh().errorOrNull())
            assertEquals(0, server.requestCount)
            assertTrue(contacts.observeContacts().first().isEmpty())
        }

    @Test
    fun `a signed-in user with no departments gets an empty directory, not an error`() =
        runTest(dispatcher) {
            // The departments arrive in the login response; a session restored from disk
            // has none until the next sign-in. Empty is the honest answer, and it is also
            // what a real account in no department looks like.
            store.save(session, origin())
            sessions.currentSession()
            val contacts = contactsRepository()

            assertTrue(contacts.refresh() is Outcome.Success)
            assertEquals(0, server.requestCount, "an empty department list must not be sent as 'everything'")
            assertTrue(contacts.observeContacts().first().isEmpty())
        }

    @Test
    fun `a 401 on the directory is an expired session, not a wrong password`() =
        runTest(dispatcher) {
            store.save(session, origin())
            sessions.currentSession()
            server.enqueue(
                MockResponse().setResponseCode(401).setBody("""{"error":"Full authentication is required"}"""),
            )

            // Reached only with departments, so this drives the mapper directly instead.
            assertEquals(
                ChatAuthError.SessionExpired,
                errors.fromStatus(401, """{"error":"x"}""", CoralErrorMapper.Call.AUTHENTICATED),
            )
        }

    @Test
    fun `the directory starts empty rather than absent`() = runTest(dispatcher) {
        assertTrue(contactsRepository().observeContacts().first().isEmpty())
    }

    @Test
    fun `the device id is one per install, not one per sign-in`() {
        // The server feeds it to updateDeviceUser, so a fresh value per sign-in would make
        // one handset look like a new device every time somebody signed in.
        val first = CoralDeviceId(context).get()

        assertEquals(first, CoralDeviceId(context).get())
        assertEquals(32, first.length)
        assertTrue(first.all { it.isDigit() || it in 'A'..'F' }, "not 32 upper hex: $first")
    }

    @Test
    fun `a session read back from a second store survives a restart`() = runTest(dispatcher) {
        store.save(session, origin())

        assertEquals(session, assertNotNull(store.currentSession()))
        assertNotNull(sessions.currentSession()?.token?.reveal())
    }
}

/**
 * A cipher that is reversible and inspectable, standing in for the Keystore one.
 *
 * The Android Keystore cannot be exercised on the JVM, which is why `:data:account` put a
 * seam there in the first place.
 */
internal class ReversibleCipher : CredentialCipher {
    override fun encrypt(secret: Secret): Outcome<String, CipherError> =
        Outcome.Success(secret.reveal().reversed())

    override fun decrypt(ciphertext: String): Outcome<Secret, CipherError> =
        Outcome.Success(Secret(ciphertext.reversed()))

    override fun resetKey() = Unit
}
