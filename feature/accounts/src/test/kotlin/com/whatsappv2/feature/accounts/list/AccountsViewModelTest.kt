package com.whatsappv2.feature.accounts.list

import app.cash.turbine.test
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.RegistrationState
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SipUri
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import com.whatsappv2.domain.usecase.DeleteAccountUseCase
import com.whatsappv2.domain.usecase.LoginUseCase
import com.whatsappv2.domain.usecase.LogoutUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {

    private val repository = FakeSipAccountRepository()
    private val engine = FakeSipEngine()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AccountsViewModel(
        repository = repository,
        deleteAccount = DeleteAccountUseCase(repository, engine, engine),
        login = LoginUseCase(repository, engine),
        logout = LogoutUseCase(repository, engine, engine),
        registrar = engine,
    )

    private fun account(
        id: String = "acct-1",
        username: String = "alice",
        label: String = "Work",
        isDefault: Boolean = true,
    ) = SipAccount(
        id = AccountId(id),
        label = label,
        username = username,
        extension = null,
        authUsername = null,
        password = Secret("hunter22"),
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
        isDefault = isDefault,
    )

    private val bob = requireNotNull(SipUri.parse("sip:bob@example.com").getOrNull())

    @Test
    fun `an empty repository reports Empty, not an empty list`() = runTest(dispatcher) {
        viewModel().uiState.test {
            assertEquals(AccountsUiState.Loading, awaitItem())
            assertEquals(AccountsUiState.Empty, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `accounts are presented with their identity and default marker`() = runTest(dispatcher) {
        repository.given(account())

        viewModel().uiState.test {
            skipItems(1)
            val content = assertIs<AccountsUiState.Content>(awaitItem())
            val row = content.accounts.single()
            assertEquals("Work", row.label)
            assertEquals("alice@sip.example.com", row.identity)
            assertTrue(row.isDefault)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `status comes from the engine, never from storage`() = runTest(dispatcher) {
        // The database has no idea whether the transport is up, so a stored status column
        // would go stale the moment the network changed (§6).
        repository.given(account())
        engine.givenRegistered(account())

        viewModel().uiState.test {
            skipItems(1)
            val content = assertIs<AccountsUiState.Content>(awaitItem())
            assertEquals(AccountStatus.REGISTERED, content.accounts.single().status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an account the engine has never seen reads as offline, not failed`() = runTest(dispatcher) {
        // Otherwise a fresh install looks broken.
        repository.given(account())

        viewModel().uiState.test {
            skipItems(1)
            val content = assertIs<AccountsUiState.Content>(awaitItem())
            assertEquals(AccountStatus.OFFLINE, content.accounts.single().status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a wrong password needs attention while a timeout does not`() = runTest(dispatcher) {
        assertTrue(
            AccountStatus.from(
                RegistrationState.Failed(
                    RegistrationFailure.AUTHENTICATION_FAILED,
                    retryScheduled = false,
                ),
            ).needsAttention,
        )
        assertTrue(
            !AccountStatus.from(
                RegistrationState.Failed(
                    RegistrationFailure.TIMEOUT,
                    retryScheduled = true,
                ),
            ).needsAttention,
        )
    }

    @Test
    fun `deleting while a call is in progress emits a refusal, not a silent no-op`() = runTest(dispatcher) {
        repository.given(account())
        engine.givenRegistered(account())
        engine.simulateIncomingCall(AccountId("acct-1"), bob)

        val model = viewModel()
        model.events.test {
            model.deleteAccount(AccountId("acct-1"), "Work")
            assertEquals(AccountsEvent.DeleteRefusedCallInProgress(activeCalls = 1), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a successful delete reports which account went`() = runTest(dispatcher) {
        repository.given(account())

        val model = viewModel()
        model.events.test {
            model.deleteAccount(AccountId("acct-1"), "Work")
            assertEquals(AccountsEvent.Deleted("Work"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setting the default moves it`() = runTest(dispatcher) {
        repository.given(
            account(id = "acct-1", isDefault = true),
            account(id = "acct-2", username = "bob", isDefault = false),
        )

        val model = viewModel()
        model.setDefault(AccountId("acct-2"))

        model.uiState.test {
            skipItems(1)
            val content = assertIs<AccountsUiState.Content>(awaitItem())
            assertEquals(listOf("acct-2"), content.accounts.filter { it.isDefault }.map { it.id.value })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multiple accounts are all listed`() = runTest(dispatcher) {
        // Task 22: the app is multi-account, and the list must show every one.
        repository.given(
            account(id = "acct-1", username = "alice", label = "Work"),
            account(id = "acct-2", username = "bob", label = "Home", isDefault = false),
            account(id = "acct-3", username = "carol", label = "Lab", isDefault = false),
        )

        viewModel().uiState.test {
            skipItems(1)
            val content = assertIs<AccountsUiState.Content>(awaitItem())
            assertEquals(3, content.accounts.size)
            assertEquals(1, content.accounts.count { it.isDefault }, "exactly one default at all times")
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ------------------------------------------------------------------ login / logout

    @Test
    fun `logging out releases the registration and keeps the account`() = runTest(dispatcher) {
        // Task 29: logout is not delete. The row stays, so the account is still listed and
        // can be logged back in without the password being typed again.
        val stored = account()
        repository.given(stored)
        engine.givenRegistered(stored)
        val viewModel = viewModel()

        viewModel.events.test {
            viewModel.logOut(stored.id, "Work")
            assertEquals(AccountsEvent.LoggedOut("Work"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(repository.deletedIds.isEmpty(), "logging out must not delete the account")
        assertEquals(RegistrationState.Unregistered, engine.registrationState.value[stored.id])
    }

    @Test
    fun `logging out is refused while a call is in progress`() = runTest(dispatcher) {
        // Silently doing nothing would be worse than refusing: the user pressed a button
        // and is owed a reason.
        val stored = account()
        repository.given(stored)
        engine.givenRegistered(stored)
        engine.simulateIncomingCall(stored.id, bob)
        val viewModel = viewModel()

        viewModel.events.test {
            viewModel.logOut(stored.id, "Work")
            assertEquals(AccountsEvent.LogoutRefusedCallInProgress(activeCalls = 1), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `logging back in needs no password`() = runTest(dispatcher) {
        val stored = account()
        repository.given(stored)
        val viewModel = viewModel()

        viewModel.events.test {
            viewModel.logIn(stored.id, "Work")
            assertEquals(AccountsEvent.LoggedIn("Work"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(RegistrationState.Registered(3_600), engine.registrationState.value[stored.id])
    }

    @Test
    fun `a rejected login names the reason in the same words the row uses`() = runTest(dispatcher) {
        // A snackbar saying "error" beside a row saying "check your details" describes one
        // failure two ways. Both come from RegistrationFailure so they cannot diverge.
        val stored = account()
        repository.given(stored)
        engine.failNext(FakeSipEngine.Operation.REGISTER, SipError.AuthenticationFailed(401))
        val viewModel = viewModel()

        viewModel.events.test {
            viewModel.logIn(stored.id, "Work")
            assertEquals(
                AccountsEvent.LoginFailed("Work", RegistrationFailure.AUTHENTICATION_FAILED),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `acting on an account that has been deleted elsewhere says so`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.events.test {
            viewModel.logIn(AccountId("gone"), "Ghost")
            assertEquals(AccountsEvent.AccountGone("Ghost"), awaitItem())

            viewModel.logOut(AccountId("gone"), "Ghost")
            assertEquals(AccountsEvent.AccountGone("Ghost"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a row knows whether it is logged in`() = runTest(dispatcher) {
        // The one control does two opposite things, so the row has to be able to say which.
        val stored = account()
        repository.given(stored)
        engine.givenRegistered(stored)
        val viewModel = viewModel()

        viewModel.uiState.test {
            skipItems(1)
            val registered = assertIs<AccountsUiState.Content>(awaitItem())
            assertTrue(registered.accounts.single().isLoggedIn)

            viewModel.logOut(stored.id, "Work")
            val loggedOut = assertIs<AccountsUiState.Content>(awaitItem())
            assertTrue(!loggedOut.accounts.single().isLoggedIn)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
