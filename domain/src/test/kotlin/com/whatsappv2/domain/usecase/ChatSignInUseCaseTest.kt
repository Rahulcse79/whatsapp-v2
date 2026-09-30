package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatSession
import com.whatsappv2.domain.chat.ChatUrlViolation
import com.whatsappv2.domain.chat.CoralServerUrl
import com.whatsappv2.domain.testing.FakeChatSessionRepository
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
    private val signIn = ChatSignInUseCase(repository)

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
        signIn("https://gujlogin.coraltele.com/services/app/v2/auth/login", "sample-user", Secret("not-a-real-password"))

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
    fun `signing out keeps the URL, so the second sign-in is two fields`() = runTest {
        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))

        repository.signOut()

        assertEquals(null, repository.currentSession())
        assertEquals("https://host.example", repository.currentServerUrl().origin)
    }
}
