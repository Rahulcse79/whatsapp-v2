package com.whatsappv2.onboarding

import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.chat.ChatCredentials
import com.whatsappv2.domain.chat.ChatSession
import com.whatsappv2.domain.testing.FakeChatSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Whether the application is on screen at all.
 *
 * The state worth testing is [SignInGateState.Unknown]. Collapsing it into "signed out"
 * is free and invisible in a preview, and shows a login form for a frame or two on every
 * cold start to somebody who is already signed in — which looks exactly like having been
 * signed out, on the one screen where that is most alarming.
 */
class SignInGateViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun session(userId: String = "mcx8101") = ChatSession(
        userId = userId,
        displayName = null,
        token = Secret("not-a-real-token"),
        expiresAtMs = null,
        deviceId = "DEVICE",
    )

    @Test
    fun `the gate starts Unknown, before the stored session has been read`() = runTest(dispatcher) {
        val model = SignInGateViewModel(FakeChatSessionRepository())

        // Read without advancing: this is the very first frame, and the repository's own
        // flow emits nothing until it has been to disk.
        assertEquals(SignInGateState.Unknown, model.state.value)
    }

    @Test
    fun `no stored session shows the login`() = runTest(dispatcher) {
        val model = SignInGateViewModel(FakeChatSessionRepository())

        testScheduler.advanceUntilIdle()

        assertEquals(SignInGateState.SignedOut, model.state.value)
    }

    @Test
    fun `a stored session lets the app through`() = runTest(dispatcher) {
        val model = SignInGateViewModel(FakeChatSessionRepository(initialSession = session()))

        testScheduler.advanceUntilIdle()

        assertEquals(SignInGateState.SignedIn, model.state.value)
    }

    @Test
    fun `signing out closes the gate again, without anything else happening`() = runTest(dispatcher) {
        val repository = FakeChatSessionRepository(initialSession = session())
        val model = SignInGateViewModel(repository)
        testScheduler.advanceUntilIdle()
        assertEquals(SignInGateState.SignedIn, model.state.value)

        repository.signOut()
        testScheduler.advanceUntilIdle()

        // Reactive, which is the whole reason this is not a step in FirstRunGate: that one
        // re-reads its stores only when some other step completes, so a sign-out would have
        // left the app on screen until something unrelated happened.
        assertEquals(SignInGateState.SignedOut, model.state.value)
    }

    @Test
    fun `signing back in reopens it`() = runTest(dispatcher) {
        val repository = FakeChatSessionRepository()
        val model = SignInGateViewModel(repository)
        testScheduler.advanceUntilIdle()
        assertEquals(SignInGateState.SignedOut, model.state.value)

        repository.signIn(repository.currentServerUrl(), credentials())
        testScheduler.advanceUntilIdle()

        assertEquals(SignInGateState.SignedIn, model.state.value)
    }

    private fun credentials() = ChatCredentials(
        username = "mcx8101",
        password = Secret("not-a-real-password"),
    )
}
