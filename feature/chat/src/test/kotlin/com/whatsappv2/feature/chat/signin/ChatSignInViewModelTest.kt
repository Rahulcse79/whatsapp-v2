package com.whatsappv2.feature.chat.signin

import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatUrlViolation
import com.whatsappv2.domain.chat.CoralServerUrl
import com.whatsappv2.domain.testing.FakeChatSessionRepository
import com.whatsappv2.domain.testing.FakeSipAccountRepository
import com.whatsappv2.domain.testing.FakeSipEngine
import com.whatsappv2.domain.usecase.ChatSignInUseCase
import com.whatsappv2.domain.usecase.EnsureChatExtensionUseCase
import com.whatsappv2.domain.usecase.LoginUseCase
import com.whatsappv2.domain.usecase.LogoutUseCase
import com.whatsappv2.domain.usecase.SaveAccountUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where each failure lands on the sign-in form.
 *
 * The assertions are all about **placement**. A bad URL under the URL box, a rejected
 * credential under the password, and everything else in a banner — because the one thing
 * a three-field form must never do is tell somebody their password is wrong when the
 * server was unreachable, or the build had no key.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSignInViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeChatSessionRepository()

    private val accounts = FakeSipAccountRepository()
    private val engine = FakeSipEngine()

    private fun viewModel() = ChatSignInViewModel(
        ChatSignInUseCase(
            repository,
            EnsureChatExtensionUseCase(
                accounts = accounts,
                saveAccount = SaveAccountUseCase(accounts, engine, LoginUseCase(accounts, engine)),
                logout = LogoutUseCase(accounts, engine, engine),
                logger = NoOpLogger,
            ),
        ),
        repository,
    )

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the URL field is prefilled from the stored origin`() = runTest(dispatcher) {
        val model = viewModel()
        testScheduler.advanceUntilIdle()

        assertEquals(CoralServerUrl.DEFAULT.origin, model.state.value.serverUrl)
    }

    @Test
    fun `submit is disabled until all three fields have something in them`() = runTest(dispatcher) {
        val model = viewModel()
        testScheduler.advanceUntilIdle()

        assertFalse(model.state.value.canSubmit, "enabled with no username or password")

        model.setUsername("sample-user")
        assertFalse(model.state.value.canSubmit, "enabled with no password")

        model.setPassword(Secret("not-a-real-password"))
        assertTrue(model.state.value.canSubmit)
    }

    @Test
    fun `a blank URL disables submit even though the field started prefilled`() = runTest(dispatcher) {
        val model = viewModel()
        testScheduler.advanceUntilIdle()
        model.setUsername("sample-user")
        model.setPassword(Secret("not-a-real-password"))

        model.setServerUrl("   ")

        assertFalse(model.state.value.canSubmit)
    }

    @Test
    fun `a successful sign-in announces itself once and clears the password`() = runTest(dispatcher) {
        val model = viewModel()
        testScheduler.advanceUntilIdle()
        model.setUsername("sample-user")
        model.setPassword(Secret("not-a-real-password"))

        model.submit()
        testScheduler.advanceUntilIdle()

        assertEquals(ChatSignInEvent.SignedIn, model.events.first())
        assertTrue(model.state.value.password.isEmpty, "the password outlived the request that needed it")
        assertEquals(CoralServerUrl.DEFAULT.origin, model.state.value.serverUrl, "the URL was forgotten")
    }

    @Test
    fun `an unusable scheme goes on the URL field and nothing reaches the network`() =
        runTest(dispatcher) {
            val model = viewModel()
            testScheduler.advanceUntilIdle()
            model.setServerUrl("ftp://gujlogin.coraltele.com")
            model.setUsername("sample-user")
            model.setPassword(Secret("not-a-real-password"))

            model.submit()
            testScheduler.advanceUntilIdle()

            assertIs<ChatUrlViolation.UnsupportedScheme>(model.state.value.urlError)
            assertNull(model.state.value.banner, "a field error was also put in the banner")
            assertTrue(repository.signInAttempts.isEmpty(), "an unparseable URL still reached the network")
        }

    @Test
    fun `an http URL is accepted and used as typed`() = runTest(dispatcher) {
        val model = viewModel()
        testScheduler.advanceUntilIdle()
        model.setServerUrl("http://192.168.250.201")
        model.setUsername("sample-user")
        model.setPassword(Secret("not-a-real-password"))

        model.submit()
        testScheduler.advanceUntilIdle()

        assertNull(model.state.value.urlError, "a real deployment's address was refused")
        assertEquals("http://192.168.250.201", repository.signInAttempts.single().url.origin)
    }

    @Test
    fun `a rejected credential goes on the password field, not the banner`() = runTest(dispatcher) {
        val model = signedInAttemptFailing(ChatAuthError.InvalidCredentials)

        assertEquals(ChatAuthError.InvalidCredentials, model.state.value.passwordError)
        assertNull(model.state.value.banner)
    }

    @Test
    fun `an unreachable server goes in the banner, not on the password field`() = runTest(dispatcher) {
        val model = signedInAttemptFailing(ChatAuthError.Network)

        assertEquals(ChatAuthError.Network, model.state.value.banner)
        assertNull(model.state.value.passwordError, "a dead network was blamed on the password")
    }

    @Test
    fun `a build with no cipher key goes in the banner and never on the password`() = runTest(dispatcher) {
        // The case ChatAuthError.CryptoUnavailable exists for. Reported on the password
        // field it would send somebody to change a credential that was correct.
        val model = signedInAttemptFailing(ChatAuthError.CryptoUnavailable)

        assertEquals(ChatAuthError.CryptoUnavailable, model.state.value.banner)
        assertNull(model.state.value.passwordError)
    }

    @Test
    fun `a server error goes in the banner and carries the platform's own words`() = runTest(dispatcher) {
        val model = signedInAttemptFailing(ChatAuthError.Server(503, "Service unavailable"))

        assertEquals("Service unavailable", assertIs<ChatAuthError.Server>(model.state.value.banner).message)
    }

    @Test
    fun `one request at a time - a second submit while one is in flight is dropped`() = runTest(dispatcher) {
        val model = viewModel()
        testScheduler.advanceUntilIdle()
        model.setUsername("sample-user")
        model.setPassword(Secret("not-a-real-password"))

        model.submit()
        // Not advanced: the first request is still in flight, which is exactly the window
        // a double tap lands in. Two logins would race, and the loser's answer would
        // overwrite the winner's - including overwriting a success with a failure.
        model.submit()
        testScheduler.advanceUntilIdle()

        assertEquals(1, repository.signInAttempts.size)
    }

    @Test
    fun `editing a field clears its error, so a fix does not look like it failed`() = runTest(dispatcher) {
        val model = signedInAttemptFailing(ChatAuthError.InvalidCredentials)

        model.setPassword(Secret("the-right-one"))

        assertNull(model.state.value.passwordError)
    }

    @Test
    fun `the reveal toggle flips and starts hidden`() = runTest(dispatcher) {
        val model = viewModel()
        testScheduler.advanceUntilIdle()

        assertFalse(model.state.value.isPasswordVisible, "the password started visible")
        model.togglePasswordVisible()
        assertTrue(model.state.value.isPasswordVisible)
    }

    /** Runs one failing attempt and hands back the settled ViewModel. */
    private fun TestScope.signedInAttemptFailing(error: ChatAuthError): ChatSignInViewModel {
        repository.givenSignInFails(error)
        val model = viewModel()
        testScheduler.advanceUntilIdle()
        model.setUsername("sample-user")
        model.setPassword(Secret("not-a-real-password"))
        model.submit()
        testScheduler.advanceUntilIdle()
        return model
    }
}
