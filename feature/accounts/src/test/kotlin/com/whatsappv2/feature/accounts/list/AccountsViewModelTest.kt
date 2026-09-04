package com.whatsappv2.feature.accounts.list

import app.cash.turbine.test
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
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
}
