package com.whatsappv2.feature.accounts.detail

import app.cash.turbine.test
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.registration.RegistrationRetrySchedule
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import com.whatsappv2.domain.usecase.LoginUseCase
import com.whatsappv2.feature.accounts.list.AccountStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The registration detail screen's state (Task 31).
 *
 * The three done-whens are here as three tests: the state follows the engine with nothing
 * polling it, a wrong password is named rather than generalised, and no network is a
 * different state from a broken account.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AccountDetailViewModelTest {

    private val repository = FakeSipAccountRepository()
    private val engine = FakeSipEngine()
    private val retries = FakeRetrySchedule()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AccountDetailViewModel(
        repository = repository,
        registrar = engine,
        login = LoginUseCase(repository, engine),
        retrySchedule = retries,
    )

    private val account = SipAccount(
        id = AccountId("acct-1"),
        label = "Work",
        username = "alice",
        extension = null,
        authUsername = null,
        password = Secret("hunter22"),
        displayName = null,
        domain = "sip.example.com",
        registrar = null,
        outboundProxy = null,
        port = null,
        transport = Transport.TLS,
        registrationExpirySeconds = 3_600,
        stunServer = null,
        turn = null,
        natPolicy = NatPolicy.DEFAULT,
        srtpPolicy = SrtpPolicy.OPTIONAL,
        codecs = CodecPreferences.DEFAULT,
        isDefault = true,
    )

    // ---------------------------------------------------------------- done-when 1

    @Test
    fun `status follows the engine with nothing asking it to`() = runTest {
        // "Live, with no polling" is not a thing that can be asserted directly - you
        // cannot prove the absence of a timer. What CAN be asserted is the property that
        // makes polling unnecessary: a change made only to the engine arrives here,
        // through a ViewModel that was never told to look again.
        repository.save(account)
        engine.givenRegistered(account)
        val viewModel = viewModel().apply { load(account.id) }

        viewModel.uiState.test {
            awaitItem() // Loading, before the flows have produced anything.
            val registered = assertIs<AccountDetailUiState.Content>(awaitItem())
            assertEquals(AccountStatus.REGISTERED, registered.status)

            engine.simulateRegistrationExpiry(account.id)

            assertEquals(
                AccountStatus.OFFLINE,
                assertIs<AccountDetailUiState.Content>(awaitItem()).status,
                "the engine changed and the screen followed; nothing here re-read it",
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the granted expiry is reported, because the server chooses it and not us`() = runTest {
        repository.save(account)
        engine.givenRegistered(account)
        val viewModel = viewModel().apply { load(account.id) }

        viewModel.uiState.test {
            awaitItem()
            val content = assertIs<AccountDetailUiState.Content>(awaitItem())
            assertEquals(3_600, content.grantedExpirySeconds)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------- done-when 2

    @Test
    fun `a wrong password is named, not generalised`() = runTest {
        // The task names this one explicitly. "Authentication failed" tells someone to go
        // and fix their password; "registration error" sends them to look at their Wi-Fi.
        repository.save(account)
        engine.alwaysFail(
            FakeSipEngine.Operation.REGISTER,
            SipError.AuthenticationFailed(UNAUTHORIZED),
        )
        val viewModel = viewModel().apply { load(account.id) }

        viewModel.registerNow()

        viewModel.events.test {
            val event = assertIs<AccountDetailEvent.RegisterFailed>(awaitItem())
            assertEquals(RegistrationFailure.AUTHENTICATION_FAILED, event.reason)
            assertEquals("Authentication failed", event.reason.detailLabel())
        }
    }

    @Test
    fun `a failure that needs the user is separated from one that does not`() = runTest {
        // Both are Failed. Only one of them is worth interrupting someone about, which is
        // the distinction the screen colours and the list's status enum encodes.
        assertTrue(RegistrationFailure.AUTHENTICATION_FAILED.requiresUserAction)
        assertTrue(!RegistrationFailure.TIMEOUT.requiresUserAction)
        assertEquals("Retrying automatically.", RegistrationFailure.TIMEOUT.remedy())
    }

    // ---------------------------------------------------------------- done-when 3

    @Test
    fun `no network reads as offline, not as a broken account`() = runTest {
        // §6, and the reason this screen exists: airplane mode must not send someone to
        // re-type a password that was never wrong.
        repository.save(account)
        engine.givenRegistered(account)
        val viewModel = viewModel().apply { load(account.id) }

        viewModel.uiState.test {
            awaitItem()
            val registered = assertIs<AccountDetailUiState.Content>(awaitItem())
            assertEquals(AccountStatus.REGISTERED, registered.status)

            engine.simulateNetworkLoss()

            val offline = assertIs<AccountDetailUiState.Content>(awaitItem())
            assertEquals(
                AccountStatus.FAILED_RETRYING,
                offline.status,
                "a lost network is retried, so it must not be reported as needing attention",
            )
            assertEquals(RegistrationFailure.NETWORK_UNAVAILABLE, offline.failure)
            assertTrue(!offline.status.needsAttention)
            assertEquals("No network", offline.failure!!.detailLabel())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------- retry schedule

    @Test
    fun `a pending retry is carried through to the state`() = runTest {
        repository.save(account)
        engine.givenRegistered(account)
        val viewModel = viewModel().apply { load(account.id) }

        viewModel.uiState.test {
            awaitItem()
            assertNull(assertIs<AccountDetailUiState.Content>(awaitItem()).nextRetryAtEpochMillis)

            retries.schedule(account.id, DUE_AT)

            val scheduled = assertIs<AccountDetailUiState.Content>(awaitItem())
            assertEquals(DUE_AT, scheduled.nextRetryAtEpochMillis)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    fun `an account deleted underneath the screen reads as gone`() = runTest {
        repository.save(account)
        val viewModel = viewModel().apply { load(account.id) }

        viewModel.uiState.test {
            awaitItem()
            assertIs<AccountDetailUiState.Content>(awaitItem())

            repository.delete(account.id)

            assertIs<AccountDetailUiState.Gone>(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val DUE_AT = 1_700_000_060_000L

        /** The response code a registrar sends for a bad password. */
        const val UNAUTHORIZED = 401
    }
}

/**
 * The retry schedule, as a thing a test can write to.
 *
 * The real one is the recovery coordinator inside `:data:sip`, which a `:feature:*` module
 * may not depend on (architecture rule 3) — and should not need to, since what this screen
 * consumes is a domain interface with one flow on it.
 */
private class FakeRetrySchedule : RegistrationRetrySchedule {
    private val times = MutableStateFlow<Map<AccountId, Long>>(emptyMap())
    override val nextRetryAt: StateFlow<Map<AccountId, Long>> = times

    fun schedule(id: AccountId, epochMillis: Long) {
        times.value = times.value + (id to epochMillis)
    }
}
