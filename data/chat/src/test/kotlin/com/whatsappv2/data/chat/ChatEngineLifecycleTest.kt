package com.whatsappv2.data.chat

import androidx.test.core.app.ApplicationProvider
import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.data.chat.sdk.FakeChatSdkHandle
import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.chat.ChatSession
import com.whatsappv2.domain.chat.CoralServerUrl
import com.whatsappv2.domain.testing.FakeChatSessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **The regression test for the thread leak.**
 *
 * `ChatSdk.init()` disconnects the old instance but never shuts down `WsClient`'s
 * single-thread scheduled executor, so **every call leaks one thread for the life of the
 * process** (finding 1.3-6). There is no way to observe that from here, and there is no
 * need to: the leak is exactly proportional to the number of `init` calls, so counting
 * them is counting leaked threads.
 *
 * Every assertion below is therefore on [FakeChatSdkHandle.initCount].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class ChatEngineLifecycleTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val sessions = FakeChatSessionRepository()
    private val sdk = FakeChatSdkHandle()
    private val bus = ChatEventBus()
    private val outbox = ChatSendOutbox(sdk)

    /**
     * An eagerly-dispatching scope, cancelled when the test ends.
     *
     * `backgroundScope` plus `advanceUntilIdle()` does **not** resume a long-lived
     * collector — the engine's flow subscription never started and every assertion read
     * the initial state instead. An unconfined dispatcher starts the collection at the
     * point of `launch`, which is what these tests are actually about.
     */
    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @After
    fun tearDown() = scope.cancel()

    private fun engine() = ChatEngineLifecycle(
        context = context,
        sessions = sessions,
        sdk = sdk,
        bus = bus,
        outbox = outbox,
        logger = NoOpLogger,
    )

    private fun session(userId: String = "mcx8101") = ChatSession(
        userId = userId,
        displayName = "Sample User",
        token = Secret("t"),
        expiresAtMs = null,
        deviceId = "D",
    )

    @Test
    fun `no session means no init, so a fresh install opens no socket`() = runTest {
        engine().start(scope)
        runCurrent()

        assertEquals(0, sdk.initCount)
        assertEquals(ChatConnectionState.NotConfigured, engine().observeConnection().first())
    }

    @Test
    fun `signing in initialises exactly once`() = runTest {
        sessions.givenSignedIn(session())

        engine().start(scope)
        runCurrent()

        assertEquals(1, sdk.initCount)
        assertEquals(1, sdk.listeners.size, "exactly one listener exists in the process")
    }

    @Test
    fun `N emissions of the same session produce ONE init`() = runTest {
        // The naive wiring the doc warns about: a value re-emitted per keystroke, or a
        // flow that simply repeats, leaking one thread each time.
        sessions.givenSignedIn(session())
        engine().start(scope)
        runCurrent()

        repeat(20) {
            sessions.givenSignedIn(session())
            runCurrent()
        }

        assertEquals(1, sdk.initCount, "re-emitting the same session re-initialised the SDK")
    }

    @Test
    fun `going back to a binding that is already running does not re-init`() = runTest {
        // distinctUntilChanged alone does NOT cover this: A then B then A is three
        // distinct consecutive values, and the third would re-init a live binding.
        val engine = engine()
        sessions.givenSignedIn(session("a"))
        engine.start(scope)
        runCurrent()
        assertEquals(1, sdk.initCount)

        sessions.givenSignedIn(session("b"))
        runCurrent()
        assertEquals(2, sdk.initCount)

        sessions.givenSignedIn(session("a"))
        runCurrent()

        // Three distinct values, three inits - "a" is no longer the running binding when
        // it comes back, so this one IS correct. What must never happen is a FOURTH.
        assertEquals(3, sdk.initCount)

        sessions.givenSignedIn(session("a"))
        runCurrent()
        assertEquals(3, sdk.initCount, "a repeat of the live binding re-initialised it")
    }

    @Test
    fun `a different user re-inits, because it is a different identity on the socket`() = runTest {
        sessions.givenSignedIn(session("a"))
        engine().start(scope)
        runCurrent()

        sessions.givenSignedIn(session("b"))
        runCurrent()

        assertEquals(2, sdk.initCount)
        assertTrue(sdk.bindings.last().contains("|b|"), "the new identity did not reach the SDK")
    }

    @Test
    fun `signing out disconnects and reports NotConfigured`() = runTest {
        val engine = engine()
        sessions.givenSignedIn(session())
        engine.start(scope)
        runCurrent()

        sessions.signOut()
        runCurrent()

        assertEquals(1, sdk.disconnectCount)
        assertEquals(ChatConnectionState.NotConfigured, engine.observeConnection().first())
        assertNull(engine.observeIdentity().first())
    }

    @Test
    fun `the socket URL follows the origin's scheme`() = runTest {
        val http = CoralServerUrl.parse("http://192.168.250.201").getOrNull()!!
        val repository = FakeChatSessionRepository(initialUrl = http, initialSession = session())

        ChatEngineLifecycle(context, repository, sdk, bus, outbox, NoOpLogger).start(scope)
        runCurrent()

        assertTrue(
            sdk.bindings.single().startsWith("http://192.168.250.201/chat/|ws://192.168.250.201/chat/ws"),
            "derived the wrong URLs: ${sdk.bindings.single()}",
        )
    }

    @Test
    fun `start is idempotent, so a second call does not double every decision`() = runTest {
        val engine = engine()
        sessions.givenSignedIn(session())

        engine.start(scope)
        engine.start(scope)
        runCurrent()

        assertEquals(1, sdk.initCount)
    }
}
