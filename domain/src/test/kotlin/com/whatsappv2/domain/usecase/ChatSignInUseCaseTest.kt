package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatSession
import com.whatsappv2.domain.chat.ChatUrlViolation
import com.whatsappv2.domain.chat.CoralServerUrl
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeChatSessionRepository
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Sign-in composition: the URL parses, the fields are there, and only then is a request made.
 *
 * The assertions that matter most are the negative ones — a request the use case must
 * **not** make. A form that reaches the network with a URL it could not parse is how a
 * user ends up reading a timeout when the real fault was a typo they could see.
 */
class ChatSignInUseCaseTest {

    private val repository = FakeChatSessionRepository()
    private val accounts = FakeSipAccountRepository()
    private val engine = FakeSipEngine()
    private val signIn = ChatSignInUseCase(
        repository,
        EnsureChatExtensionUseCase(
            accounts = accounts,
            saveAccount = SaveAccountUseCase(accounts, engine, LoginUseCase(accounts, engine)),
            logger = NoOpLogger,
        ),
    )

    private fun violations(outcome: Outcome<ChatSession, ChatSignInError>): List<ChatSignInViolation> =
        assertIs<ChatSignInError.InvalidInput>(
            outcome.errorOrNull() ?: fail("expected a failure"),
        ).violations

    @Test
    fun `a valid form signs in and the parsed origin is what reaches the repository`() = runTest {
        val outcome = signIn("https://gujlogin.coraltele.com", "sample-user", Secret("not-a-real-password"))

        assertIs<Outcome.Success<ChatSession>>(outcome)
        assertEquals(1, repository.signInAttempts.size)
        assertEquals(CoralServerUrl.DEFAULT, repository.signInAttempts.single().url)
        assertEquals("sample-user", repository.signInAttempts.single().credentials.username)
        assertEquals("not-a-real-password", repository.signInAttempts.single().credentials.password.reveal())
    }

    @Test
    fun `a pasted API path still signs in against the origin`() = runTest {
        signIn(
            "https://gujlogin.coraltele.com/services/app/v2/auth/login",
            "sample-user",
            Secret("not-a-real-password"),
        )

        assertEquals(CoralServerUrl.DEFAULT, repository.signInAttempts.single().url)
    }

    @Test
    fun `the username is trimmed, because it arrives pasted`() = runTest {
        signIn("https://host.example", "  sample-user ", Secret("not-a-real-password"))

        assertEquals("sample-user", repository.signInAttempts.single().credentials.username)
    }

    @Test
    fun `the password is not trimmed, because a space in one is a character the user chose`() = runTest {
        signIn("https://host.example", "sample-user", Secret(" not-a-real-password "))

        assertEquals(" not-a-real-password ", repository.signInAttempts.single().credentials.password.reveal())
    }

    // ------------------------------------------------------------------ nothing reaches the wire

    @Test
    fun `an unparseable URL fails on the URL field and makes no request`() = runTest {
        val outcome = signIn("ftp://gujlogin.coraltele.com", "sample-user", Secret("not-a-real-password"))

        val violation = assertIs<ChatSignInViolation.MalformedUrl>(violations(outcome).single())
        assertIs<ChatUrlViolation.UnsupportedScheme>(violation.violation)
        assertEquals(ChatSignInField.SERVER_URL, violation.field)
        assertTrue(repository.signInAttempts.isEmpty())
    }

    @Test
    fun `an http URL signs in against the http origin, scheme and all`() = runTest {
        // http://192.168.250.201 is a real deployment. The scheme the user typed is the
        // one that reaches the repository, rather than being silently upgraded.
        signIn("http://192.168.250.201", "sample-user", Secret("not-a-real-password"))

        assertEquals("http://192.168.250.201", repository.signInAttempts.single().url.origin)
    }

    @Test
    fun `a bare IP signs in against its https form`() = runTest {
        signIn("192.168.250.201", "sample-user", Secret("not-a-real-password"))

        assertEquals("https://192.168.250.201", repository.signInAttempts.single().url.origin)
    }

    @Test
    fun `a blank username fails on the username field and makes no request`() = runTest {
        val outcome = signIn("https://host.example", "   ", Secret("not-a-real-password"))

        assertEquals(
            ChatSignInViolation.Required(ChatSignInField.USERNAME),
            violations(outcome).single(),
        )
        assertTrue(repository.signInAttempts.isEmpty())
    }

    @Test
    fun `a blank password fails on the password field and makes no request`() = runTest {
        val outcome = signIn("https://host.example", "sample-user", Secret.EMPTY)

        assertEquals(
            ChatSignInViolation.Required(ChatSignInField.PASSWORD),
            violations(outcome).single(),
        )
        assertTrue(repository.signInAttempts.isEmpty())
    }

    @Test
    fun `every fault is reported at once, not one per submission`() = runTest {
        val outcome = signIn("", "", Secret.EMPTY)

        assertEquals(
            listOf(
                ChatSignInField.SERVER_URL,
                ChatSignInField.USERNAME,
                ChatSignInField.PASSWORD,
            ),
            violations(outcome).map { it.field },
        )
    }

    // ------------------------------------------------------------------ the server's answer

    @Test
    fun `a rejected credential comes back typed, not as a message`() = runTest {
        repository.givenSignInFails(ChatAuthError.InvalidCredentials)

        val outcome = signIn("https://host.example", "sample-user", Secret("wrong"))

        assertEquals(
            ChatSignInError.Rejected(ChatAuthError.InvalidCredentials),
            outcome.errorOrNull(),
        )
    }

    @Test
    fun `a missing key is reported as CryptoUnavailable and never as a wrong password`() = runTest {
        repository.givenSignInFails(ChatAuthError.CryptoUnavailable)

        val error = assertIs<ChatSignInError.Rejected>(
            signIn("https://host.example", "sample-user", Secret("not-a-real-password")).errorOrNull(),
        )

        assertEquals(ChatAuthError.CryptoUnavailable, error.cause)
    }

    @Test
    fun `a failed sign-in stores neither the session nor the URL`() = runTest {
        repository.givenSignInFails(ChatAuthError.Network)

        signIn("https://typo.example", "sample-user", Secret("not-a-real-password"))

        assertEquals(null, repository.currentSession())
        assertEquals(CoralServerUrl.DEFAULT, repository.currentServerUrl())
    }

    @Test
    fun `a successful sign-in stores the session and the URL it was made against`() = runTest {
        val session = signIn("https://host.example", "sample-user", Secret("not-a-real-password")).getOrNull()

        assertEquals(session, repository.currentSession())
        assertEquals("https://host.example", repository.currentServerUrl().origin)
    }

    @Test
    fun `signing in registers the extension the LOGIN RESPONSE gave, not the username`() = runTest {
        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))

        val created = accounts.observeAccounts().first().single()
        // The bug this pins: `sample-user` signs in, `4021` is what the PBX answers to.
        // Registering the username produced an account no call could ever reach.
        assertEquals("4021", created.username, "the PBX extension registers, not the username")
        assertEquals("4021", created.extension)
        // The server's SIP domain and port, not the chat origin's host and not 5060.
        assertEquals("pbx.example", created.domain)
        assertEquals(5061, created.port)
        assertEquals(Transport.UDP, created.transport, "the response says enableSsl is false")
        // `extensionName` is null on real accounts, so the number is what gets displayed.
        assertEquals("4021", created.displayName)
        assertTrue(created.isDefault, "the first account must be the default or no call can be placed")
    }

    @Test
    fun `an extension that already exists is left alone, decisions and all`() = runTest {
        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))
        val first = accounts.observeAccounts().first().single()

        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))

        val after = accounts.observeAccounts().first()
        assertEquals(1, after.size, "a second sign-in created a duplicate account")
        assertEquals(first.id, after.single().id)
    }

    @Test
    fun `no extension in the login response means no account, rather than a guessed one`() = runTest {
        repository.extension = null

        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))

        assertTrue(
            accounts.observeAccounts().first().isEmpty(),
            "an account built from the username would register something the PBX does not know",
        )
    }

    @Test
    fun `the server's extension name wins over the number when it has one`() = runTest {
        repository.extension = repository.extension?.copy(name = "Reception")

        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))

        assertEquals("Reception", accounts.observeAccounts().first().single().displayName)
    }

    @Test
    fun `no SIP password means no account, rather than one registered with the web password`() =
        runTest {
            repository.extension = repository.extension?.copy(sipPassword = null)

            signIn("https://host.example", "sample-user", Secret("not-a-real-password"))

            assertTrue(accounts.observeAccounts().first().isEmpty())
        }

    @Test
    fun `a failed sign-in provisions nothing`() = runTest {
        repository.givenSignInFails(ChatAuthError.InvalidCredentials)

        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))

        assertTrue(accounts.observeAccounts().first().isEmpty())
    }

    @Test
    fun `signing out keeps the URL, so the second sign-in is two fields`() = runTest {
        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))

        repository.signOut()

        assertEquals(null, repository.currentSession())
        assertEquals("https://host.example", repository.currentServerUrl().origin)
    }
}
