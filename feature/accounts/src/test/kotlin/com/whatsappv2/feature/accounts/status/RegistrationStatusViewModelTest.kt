package com.whatsappv2.feature.accounts.status

import app.cash.turbine.test
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.RegistrationFailure
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import com.whatsappv2.feature.accounts.list.AccountStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class RegistrationStatusViewModelTest {

    private val repository = FakeSipAccountRepository()
    private val engine = FakeSipEngine()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = RegistrationStatusViewModel(repository, engine)

    private fun account(
        id: String,
        username: String,
        extension: String? = null,
        label: String = "Account $id",
        isDefault: Boolean = false,
    ) = SipAccount(
        id = AccountId(id),
        label = label,
        username = username,
        extension = extension,
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
        srtpPolicy = SrtpPolicy.DISABLED,
        codecs = CodecPreferences.DEFAULT,
        isDefault = isDefault,
    )

    @Test
    fun `no accounts is its own state, so the bar can offer to add one`() = runTest(dispatcher) {
        viewModel().uiState.test {
            assertEquals(RegistrationStatusUiState.Loading, awaitItem())
            assertEquals(RegistrationStatusUiState.NoAccounts, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the default account leads, with its extension and the engine's state`() = runTest(dispatcher) {
        val local = account("1", username = "1001", label = "Local", isDefault = true)
        val office = account("2", username = "user7001", extension = "7001", label = "Office")
        repository.given(local, office)
        engine.givenRegistered(local)
        engine.givenRegistrationFailed(office, RegistrationFailure.TIMEOUT)

        viewModel().uiState.test {
            skipItems(1)
            val content = assertIs<RegistrationStatusUiState.Content>(awaitItem())

            assertEquals("1001", content.default.extension)
            assertEquals(AccountStatus.REGISTERED, content.default.status)
            assertTrue(content.default.isDefault)

            // The office account has a dialable extension distinct from its username, and
            // the bar shows the extension: it is what a person knows themselves as.
            val second = content.accounts.single { it.id == office.id }
            assertEquals("7001", second.extension)
            // A timeout is retried by the app, so it is "reconnecting", not "fix this".
            assertEquals(AccountStatus.FAILED_RETRYING, second.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an account the engine has never seen is offline, not failed`() = runTest(dispatcher) {
        repository.given(account("1", username = "1001", isDefault = true))

        viewModel().uiState.test {
            skipItems(1)
            val content = assertIs<RegistrationStatusUiState.Content>(awaitItem())
            assertEquals(AccountStatus.OFFLINE, content.default.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `changing the default changes what the bar leads with`() = runTest(dispatcher) {
        val local = account("1", username = "1001", isDefault = true)
        val office = account("2", username = "7001")
        repository.given(local, office)
        val model = viewModel()

        model.uiState.test {
            skipItems(1)
            assertEquals("1001", assertIs<RegistrationStatusUiState.Content>(awaitItem()).default.extension)

            model.setDefault(office.id)
            advanceUntilIdle()

            val after = assertIs<RegistrationStatusUiState.Content>(expectMostRecentItem())
            assertEquals("7001", after.default.extension)
            // Exactly one default, which is the repository's invariant and this state's.
            assertEquals(1, after.accounts.count { it.isDefault })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a registration change reaches the bar without anyone reopening it`() = runTest(dispatcher) {
        // §6: the bar must not say Registered over a dead socket, and it must not keep
        // saying Offline after the REGISTER succeeded either.
        val local = account("1", username = "1001", isDefault = true)
        repository.given(local)

        viewModel().uiState.test {
            skipItems(1)
            assertEquals(AccountStatus.OFFLINE, assertIs<RegistrationStatusUiState.Content>(awaitItem()).default.status)

            engine.givenRegistered(local)
            assertEquals(
                AccountStatus.REGISTERED,
                assertIs<RegistrationStatusUiState.Content>(awaitItem()).default.status,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }
}
