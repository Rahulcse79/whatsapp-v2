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
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeChatSessionRepository
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** An account that is already on the device before a sign-in happens. */
private fun stubAccount(
    id: String,
    username: String,
    extension: String?,
    domain: String,
) = SipAccount(
    id = AccountId(id),
    label = "Existing",
    username = username,
    extension = extension,
    authUsername = null,
    password = Secret("not-a-real-password"),
    displayName = null,
    domain = domain,
    registrar = null,
    outboundProxy = null,
    // 1234, the port the first version of this guessed — part of what makes these fixtures
    // the shape a device actually carries after an earlier build.
    port = 1_234,
    transport = Transport.UDP,
    registrationExpirySeconds = SipAccount.DEFAULT_EXPIRY_SECONDS,
    stunServer = null,
    turn = null,
    natPolicy = NatPolicy.DEFAULT,
    srtpPolicy = SrtpPolicy.DISABLED,
    codecs = CodecPreferences.DEFAULT,
    isDefault = false,
)

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
            logout = LogoutUseCase(accounts, engine, engine),
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
    fun `a changed server is remembered even when the sign-in is rejected`() = runTest {
        // The complaint this closes: correct the address, get the password wrong, and the
        // form came back prefilled with the OLD address — so the new one had to be retyped
        // on every attempt. The address the user tried is now what is offered next time.
        repository.givenSignInFails(ChatAuthError.InvalidCredentials)

        signIn("http://192.168.3.151", "sample-user", Secret("wrong-password"))

        assertEquals("http://192.168.3.151", repository.rememberedUrls.single().origin)
        assertEquals("http://192.168.3.151", repository.currentServerUrl().origin)
    }

    @Test
    fun `a malformed server is not remembered, because there is nothing to remember`() = runTest {
        // It never parses into a CoralServerUrl, so it cannot reach the store. The field
        // keeps what the user typed on screen; what is PERSISTED stays the last good one.
        val before = repository.currentServerUrl().origin

        signIn("ftp://nope", "sample-user", Secret("not-a-real-password"))

        assertTrue(repository.rememberedUrls.isEmpty())
        assertEquals(before, repository.currentServerUrl().origin)
    }

    @Test
    fun `a bare private IP signs in against its http form`() = runTest {
        // The guess follows the kind of host: a lab literal cannot be on a certificate that
        // names the address it answers on, so https there is the guess that fails. See
        // CoralServerUrl's KDoc - this asserts the use case carries that through unchanged.
        signIn("192.168.250.201", "sample-user", Secret("not-a-real-password"))

        assertEquals("http://192.168.250.201", repository.signInAttempts.single().url.origin)
    }

    @Test
    fun `a bare hostname still signs in against its https form`() = runTest {
        signIn("host.example", "sample-user", Secret("not-a-real-password"))

        assertEquals("https://host.example", repository.signInAttempts.single().url.origin)
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
    fun `a failed sign-in stores no session, but DOES keep the URL that was tried`() = runTest {
        // Reversed deliberately. This used to assert the URL was discarded too, on the
        // grounds that a typo must not become the stored address. The cost landed on the
        // wrong side: somebody who fixed the server address and then mistyped the password
        // got the OLD address back in the form and had to retype the new one every attempt.
        // A wrong address is visible in the field and one edit away; the retyping was not.
        // See ChatSessionRepository.rememberServerUrl.
        repository.givenSignInFails(ChatAuthError.Network)

        signIn("https://typo.example", "sample-user", Secret("not-a-real-password"))

        assertEquals(null, repository.currentSession())
        assertEquals("https://typo.example", repository.currentServerUrl().origin)
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
        // The server's SIP domain, not the chat origin's host.
        assertEquals("pbx.example", created.domain)
        assertEquals(Transport.UDP, created.transport, "the response says enableSsl is false")
        // `extensionName` is null on real accounts, so the number is what gets displayed.
        assertEquals("4021", created.displayName)
        assertTrue(created.isDefault, "the signed-in extension is what calls go out from")
    }

    @Test
    fun `the port and the password are this deployment's, not the login response's`() = runTest {
        // The fixture says what the live response says: serverPort 5061, and a sipPassword
        // of its own. Neither registers against the PBX.
        assertEquals(5061, repository.extension?.port, "the fixture stopped modelling the response")

        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))

        val created = accounts.observeAccounts().first().single()
        assertEquals(
            EnsureChatExtensionUseCase.SIP_PORT,
            created.effectivePort,
            "registering on the platform's 5061 does not authenticate; the switch answers on 5060",
        )
        val credentials = accounts.credentialsFor(created.id).getOrNull() ?: fail("no credentials were stored")
        assertEquals(
            EnsureChatExtensionUseCase.SIP_PASSWORD,
            credentials.password.reveal(),
            "the response's sipPassword is a platform credential, not the one a REGISTER uses",
        )
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
    fun `a response with no SIP password still provisions, because the password is ours`() = runTest {
        repository.extension = repository.extension?.copy(sipPassword = null)

        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))

        // It used to be a second gate. It cannot be one any more: the credential no longer
        // comes from the response, so the extension is the only thing that can be missing.
        val created = accounts.observeAccounts().first().single()
        assertEquals("4021", created.username)
    }

    @Test
    fun `an account an older build left under the USERNAME is matched, not duplicated`() = runTest {
        // What the first version of this wrote: the username in the username column, on the
        // chat host, on port 1234. Matching `username == extension && domain == domain` saw
        // nothing and signed the user in to a second account beside it.
        accounts.given(
            stubAccount(id = "stale", username = "4021", extension = null, domain = "chat.example"),
        )

        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))

        val after = accounts.observeAccounts().first()
        assertEquals(1, after.size, "a second account was created for an extension that already had one")
        assertEquals("chat.example", after.single().domain, "the user's own account was overwritten")
    }

    @Test
    fun `the signed-in extension becomes the default, displacing whatever was`() = runTest {
        accounts.given(
            stubAccount(id = "other", username = "9999", extension = "9999", domain = "elsewhere.example"),
        )

        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))

        val after = accounts.observeAccounts().first()
        val default = after.filter { it.isDefault }
        assertEquals(1, default.size, "exactly one account may be the default")
        assertEquals("4021", default.single().username, "calls would still go out from the old account")
    }

    @Test
    fun `an existing account for this extension is made the default without being rewritten`() = runTest {
        // The account is right; the DEFAULT is a leftover. That is the state a device picks
        // up after an earlier build, and it is the one that sends calls out as somebody else.
        accounts.given(
            stubAccount(id = "mine", username = "4021", extension = "4021", domain = "pbx.example"),
            stubAccount(id = "leftover", username = "9999", extension = "9999", domain = "old.example"),
        )
        accounts.setDefault(AccountId("leftover"))

        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))

        val after = accounts.observeAccounts().first()
        assertEquals(2, after.size, "nothing should have been created")
        assertEquals(AccountId("mine"), after.single { it.isDefault }.id)
    }

    @Test
    fun `the previous user's chat extension is logged out, so one handset is one person`() = runTest {
        // The M14's real state on 2 Oct 2026: signed in as mcx8101 first, then as mcx8102,
        // and BOTH were registered. The handset answered for both people, and calling 8101
        // from it arrived back at itself.
        accounts.given(
            stubAccount(id = "previous", username = "9999", extension = "9999", domain = "pbx.example")
                .copy(label = "Chat (mcx9999)", registrationWanted = true),
        )

        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))

        val previous = accounts.observeAccounts().first().single { it.id == AccountId("previous") }
        assertFalse(previous.registrationWanted, "the previous user's extension was left registered")
        // Logged out, not deleted: signing back in as that person must not mean retyping.
        assertEquals(2, accounts.observeAccounts().first().size)
    }

    @Test
    fun `an account the user configured by hand is never logged out`() = runTest {
        // No "Chat (" label, so it is none of this use case's business. Logging somebody out
        // of their own account because they signed in to chat would be overstepping.
        accounts.given(
            stubAccount(id = "mine", username = "7000", extension = "7000", domain = "other.example")
                .copy(label = "Desk phone", registrationWanted = true),
        )

        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))

        assertTrue(accounts.observeAccounts().first().single { it.id == AccountId("mine") }.registrationWanted)
    }

    @Test
    fun `signing in again as the same person leaves their own extension alone`() = runTest {
        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))
        val provisioned = accounts.observeAccounts().first().single()

        signIn("https://host.example", "sample-user", Secret("not-a-real-password"))

        val after = accounts.observeAccounts().first().single()
        assertEquals(provisioned.id, after.id)
        assertTrue(after.registrationWanted, "the user was logged out of the account they just signed in to")
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
