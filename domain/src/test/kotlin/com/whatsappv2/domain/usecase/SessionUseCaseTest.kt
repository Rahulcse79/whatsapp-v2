package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.RegistrationState
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Login and logout semantics (Task 29, §5.1).
 *
 * The distinction under test throughout is that **logout is not delete**: the row
 * survives, the registration and every decrypted copy of the credentials do not.
 */
private fun account(
    id: String = "acct-1",
    username: String = "alice",
    password: String = "hunter22",
) = SipAccount(
    id = AccountId(id),
    label = "Work",
    username = username,
    extension = null,
    authUsername = null,
    password = Secret(password),
    displayName = null,
    domain = "sip.example.com",
    registrar = null,
    outboundProxy = null,
    port = null,
    transport = Transport.UDP,
    registrationExpirySeconds = 3_600,
    stunServer = null,
    turn = null,
    natPolicy = NatPolicy.DEFAULT,
    srtpPolicy = SrtpPolicy.OPTIONAL,
    codecs = CodecPreferences.DEFAULT,
    isDefault = true,
)

class LoginUseCaseTest {

    private val repository = FakeSipAccountRepository()
    private val engine = FakeSipEngine()
    private val login = LoginUseCase(repository, engine)

    @Test
    fun `a stored account is registered`() = runTest {
        repository.given(account())

        assertIs<Outcome.Success<Unit>>(login(AccountId("acct-1")))

        assertEquals(
            RegistrationState.Registered(3_600),
            engine.registrationState.value[AccountId("acct-1")],
        )
    }

    @Test
    fun `the account handed to the engine carries no password`() = runTest {
        // The engine fetches and decrypts the credentials itself, immediately before
        // building the REGISTER. Taking an id rather than an account is what makes it
        // impossible for a decrypted one to travel through this call (Task 18).
        repository.given(account(password = "hunter22"))

        login(AccountId("acct-1"))

        assertEquals(0, engine.registeredAccounts.single().password.length)
    }

    @Test
    fun `logging in to an account that is gone reports it`() = runTest {
        assertEquals(LoginError.NotFound, login(AccountId("missing")).errorOrNull())
        assertTrue(engine.invocations.isEmpty(), "a missing account must not reach the engine")
    }

    @Test
    fun `a rejection carries the reason, not a message`() = runTest {
        // The caller needs to tell "your password is wrong" from "the server is down":
        // one of those is worth waking the user up for.
        repository.given(account())
        engine.failNext(FakeSipEngine.Operation.REGISTER, SipError.AuthenticationFailed(401))

        val error = login(AccountId("acct-1")).errorOrNull()

        assertEquals(LoginError.Rejected(SipError.AuthenticationFailed(401)), error)
    }
}

class LogoutUseCaseTest {

    private val repository = FakeSipAccountRepository()
    private val engine = FakeSipEngine()
    private val logout = LogoutUseCase(repository, engine, engine)

    private val bob = requireNotNull(SipUri.parse("sip:bob@example.com").getOrNull())

    @Test
    fun `logout leaves the account row present and the registration gone`() = runTest {
        // Task 29's first done-when. This is the whole difference from delete: the user
        // stops taking calls without losing the account or having to retype a password.
        val stored = account()
        repository.given(stored)
        engine.givenRegistered(stored)

        assertIs<Outcome.Success<Unit>>(logout(stored.id))

        assertNotNull(repository.findById(stored.id), "logout must not delete the account")
        assertTrue(repository.deletedIds.isEmpty())
        assertEquals(RegistrationState.Unregistered, engine.registrationState.value[stored.id])
    }

    @Test
    fun `after logout nothing holds the account's credentials`() = runTest {
        // Task 29's third done-when, at this layer: the engine is the only thing above
        // storage that is ever given credentials, and unregistering drops the account and
        // with it everything the stack kept for it. The stack half of the same rule is
        // asserted in LinphoneSipEngineTest.
        val stored = account()
        repository.given(stored)
        engine.givenRegistered(stored)
        assertEquals(1, engine.registeredAccounts.size, "arrange: the engine holds the account")

        logout(stored.id)

        assertTrue(
            engine.registeredAccounts.isEmpty(),
            "the engine still holds an account it was logged out of",
        )
    }

    @Test
    fun `a logged-out account can be logged back in without a password`() = runTest {
        // The point of keeping the row: the encrypted password is still at rest, so
        // logging back in is one tap rather than a re-entry.
        val stored = account()
        repository.given(stored)
        engine.givenRegistered(stored)
        logout(stored.id)

        assertIs<Outcome.Success<Unit>>(LoginUseCase(repository, engine)(stored.id))
        assertEquals(
            RegistrationState.Registered(3_600),
            engine.registrationState.value[stored.id],
        )
    }

    @Test
    fun `logging out sends the unregister and nothing else`() = runTest {
        val stored = account()
        repository.given(stored)
        engine.givenRegistered(stored)
        engine.clearInvocations()

        logout(stored.id)

        assertEquals(
            listOf(FakeSipEngine.Operation.UNREGISTER),
            engine.invocations.map { it.operation },
        )
    }

    @Test
    fun `logging out is refused while a call is in progress`() = runTest {
        // The registration carries the dialog's in-progress requests, so tearing it down
        // would drop the call mid-sentence. Same rule as delete, and the same reason.
        val stored = account()
        repository.given(stored)
        engine.givenRegistered(stored)
        engine.simulateIncomingCall(stored.id, bob)
        engine.clearInvocations()

        val error = logout(stored.id).errorOrNull()

        assertEquals(LogoutError.CallInProgress(activeCalls = 1), error)
        assertTrue(engine.invocations.isEmpty(), "the registration must survive the refusal")
    }

    @Test
    fun `a call on another account does not block the logout`() = runTest {
        val stored = account()
        val other = account(id = "acct-2", username = "bob")
        repository.given(stored, other)
        engine.givenRegistered(other)
        engine.simulateIncomingCall(other.id, bob)

        assertIs<Outcome.Success<Unit>>(logout(stored.id))
    }

    @Test
    fun `logging out of an account that is gone reports it`() = runTest {
        assertEquals(LogoutError.NotFound, logout(AccountId("missing")).errorOrNull())
    }

    @Test
    fun `a registrar that will not answer does not fail the logout`() = runTest {
        // The user asked to be logged out. The binding and the credentials are dropped
        // locally whatever the server says, so reporting failure would leave the UI
        // claiming an account is still logged in when it is not.
        val stored = account()
        repository.given(stored)
        engine.givenRegistered(stored)
        engine.failNext(FakeSipEngine.Operation.UNREGISTER, SipError.Timeout)

        assertIs<Outcome.Success<Unit>>(logout(stored.id))
        assertNotNull(repository.findById(stored.id))
    }
}
