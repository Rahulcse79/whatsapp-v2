package com.whatsappv2.feature.accounts.editor

import app.cash.turbine.test
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.model.AccountId
import com.whatsappv2.domain.model.CodecPreferences
import com.whatsappv2.domain.model.NatPolicy
import com.whatsappv2.domain.model.SipAccount
import com.whatsappv2.domain.model.SrtpPolicy
import com.whatsappv2.domain.model.Transport
import com.whatsappv2.domain.repository.AccountRepositoryError
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import com.whatsappv2.domain.usecase.LoginUseCase
import com.whatsappv2.domain.usecase.RegistrationAttempt
import com.whatsappv2.domain.usecase.SaveAccountUseCase
import com.whatsappv2.domain.validation.AccountField
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AccountEditorViewModelTest {

    private val repository = FakeSipAccountRepository()
    private val engine = FakeSipEngine()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AccountEditorViewModel(
        repository = repository,
        saveAccount = SaveAccountUseCase(repository, engine, LoginUseCase(repository, engine)),
    )

    private fun account(id: String = "acct-1") = SipAccount(
        id = AccountId(id),
        label = "Work",
        username = "alice",
        extension = "1001",
        authUsername = "alice-auth",
        password = Secret("hunter22"),
        displayName = "Alice",
        domain = "sip.example.com",
        registrar = null,
        outboundProxy = null,
        port = null,
        transport = Transport.TLS,
        registrationExpirySeconds = 600,
        stunServer = null,
        turn = null,
        natPolicy = NatPolicy.DEFAULT,
        srtpPolicy = SrtpPolicy.MANDATORY,
        codecs = CodecPreferences.DEFAULT,
        isDefault = true,
    )

    @Test
    fun `a new account starts from a blank draft with a fresh id`() = runTest(dispatcher) {
        val model = viewModel()
        model.load(null)

        val state = model.uiState.value
        assertTrue(state.isNewAccount)
        assertEquals("", state.draft.label)
        assertTrue(state.draft.id.value.isNotBlank())
    }

    @Test
    fun `loading an account fills every editable field`() = runTest(dispatcher) {
        repository.given(account())

        val model = viewModel()
        model.load(AccountId("acct-1"))
        advanceUntilIdle()

        val draft = model.uiState.value.draft
        assertEquals("Work", draft.label)
        assertEquals("alice", draft.username)
        assertEquals("1001", draft.extension)
        assertEquals("alice-auth", draft.authUsername)
        assertEquals("Alice", draft.displayName)
        assertEquals(Transport.TLS, draft.transport)
        assertEquals("600", draft.registrationExpirySeconds)
        assertEquals(SrtpPolicy.MANDATORY, draft.srtpPolicy)
        assertTrue(!model.uiState.value.isNewAccount)
    }

    @Test
    fun `loading never brings the password back`() = runTest(dispatcher) {
        // A decrypted credential must not sit in a ViewModel for the life of a screen.
        // A blank field therefore means "unchanged", not "empty".
        repository.given(account())

        val model = viewModel()
        model.load(AccountId("acct-1"))
        advanceUntilIdle()

        assertEquals(0, model.uiState.value.draft.password.length)
    }

    @Test
    fun `saving an invalid draft marks the offending fields`() = runTest(dispatcher) {
        val model = viewModel()
        model.load(null)
        model.save()
        advanceUntilIdle()

        val state = model.uiState.value
        assertNotNull(state.errorFor(AccountField.LABEL))
        assertNotNull(state.errorFor(AccountField.USERNAME))
        assertNotNull(state.errorFor(AccountField.DOMAIN))
        assertNotNull(state.errorFor(AccountField.PASSWORD))
        assertTrue(!state.isSaving)
    }

    @Test
    fun `editing a field clears only that field's error`() = runTest(dispatcher) {
        // Leaving it red while the user fixes it says the correction did not register.
        val model = viewModel()
        model.load(null)
        model.save()
        advanceUntilIdle()
        assertNotNull(model.uiState.value.errorFor(AccountField.LABEL))

        model.update { it.copy(label = "Work") }

        assertNull(model.uiState.value.errorFor(AccountField.LABEL))
        assertNotNull(model.uiState.value.errorFor(AccountField.USERNAME), "other errors must remain")
    }

    @Test
    fun `a valid draft saves and reports the label`() = runTest(dispatcher) {
        val model = viewModel()
        model.load(null)
        model.update {
            it.copy(label = "Work", username = "alice", domain = "sip.example.com", password = Secret("pw"))
        }

        model.events.test {
            model.save()
            advanceUntilIdle()
            val saved = assertIs<AccountEditorEvent.Saved>(awaitItem())
            assertEquals("Work", saved.label)
            // Saving a new account logs it in: that is what "login" means here, and an
            // account that saved without registering would sit in the list looking
            // configured and take no calls (Task 29).
            assertEquals(RegistrationAttempt.Succeeded, saved.registration)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a duplicate identity is reported on the username field`() = runTest(dispatcher) {
        // A banner would not say which field to change.
        repository.given(account(id = "acct-1"))

        val model = viewModel()
        model.load(null)
        model.update {
            it.copy(label = "Second", username = "alice", domain = "sip.example.com", password = Secret("pw"))
        }
        model.save()
        advanceUntilIdle()

        assertNotNull(model.uiState.value.errorFor(AccountField.USERNAME))
    }

    @Test
    fun `an unreadable credential asks the user to re-enter it`() = runTest(dispatcher) {
        repository.nextFailure = AccountRepositoryError.CredentialsUnrecoverable

        val model = viewModel()
        model.load(null)
        model.update {
            it.copy(label = "Work", username = "alice", domain = "sip.example.com", password = Secret("pw"))
        }

        model.events.test {
            model.save()
            advanceUntilIdle()
            assertEquals(AccountEditorEvent.CredentialsMustBeReEntered, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a warning is surfaced without blocking the save`() = runTest(dispatcher) {
        val model = viewModel()
        model.load(null)
        model.update {
            it.copy(
                label = "Work",
                username = "alice",
                domain = "sip.example.com",
                password = Secret("pw"),
                transport = Transport.TLS,
                port = "5060",
            )
        }
        model.save()
        advanceUntilIdle()

        assertEquals(1, model.uiState.value.warnings.size)
    }

    @Test
    fun `saving twice in a row does not double-submit`() = runTest(dispatcher) {
        val model = viewModel()
        model.load(null)
        model.update {
            it.copy(label = "Work", username = "alice", domain = "sip.example.com", password = Secret("pw"))
        }

        model.save()
        model.save()
        advanceUntilIdle()

        assertEquals(1, repository.savedIds.size, "a second tap must not create a second account")
    }

    @Test
    fun `an engine failure does not stop the account being saved`() = runTest(dispatcher) {
        engine.failNext(FakeSipEngine.Operation.UNREGISTER, SipError.Timeout)
        repository.given(account())

        val model = viewModel()
        model.load(AccountId("acct-1"))
        advanceUntilIdle()
        model.update { it.copy(username = "alice2", password = Secret("pw")) }
        model.save()
        advanceUntilIdle()

        assertTrue(repository.savedIds.isNotEmpty())
    }
}
