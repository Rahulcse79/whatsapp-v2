package com.whatsappv2.data.chat

import com.chatserver.sdk.ChatCallback
import com.chatserver.sdk.model.Message
import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.data.chat.sdk.FakeChatSdkHandle
import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.chat.ChatFailure
import com.whatsappv2.domain.chat.ChatIdentity
import com.whatsappv2.domain.chat.ChatMessage
import com.whatsappv2.domain.chat.ConversationId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * **The regression test for the one-page sync** (finding 1.3-1).
 *
 * `getMessages` sends `afterSequenceNumber = <high-water mark>, limit = 200` and advances
 * the cursor by exactly one page per call, oldest first — then returns its *whole*
 * snapshot. So a conversation with a thousand messages needs five calls, the first call
 * returns the **oldest** two hundred, and a caller that made one call and stopped would
 * show a year-old screenful and call it the conversation.
 *
 * The port's contract is that one call to `syncMessages` means synced. These assert it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatRepositorySyncTest {

    private val dispatcher = StandardTestDispatcher()
    private val sdk = FakeChatSdkHandle().apply { markInitialised() }
    private val bus = ChatEventBus()
    private val outbox = ChatSendOutbox(sdk)

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = dispatcher
        override val io: CoroutineDispatcher get() = dispatcher
        override val default: CoroutineDispatcher get() = dispatcher
        override val unconfined: CoroutineDispatcher get() = dispatcher
    }

    /** Only the two answers the repository asks for — no Context, no socket, no SDK. */
    private val engine = object : ChatEngineState {
        override fun observeConnection() = MutableStateFlow<ChatConnectionState>(ChatConnectionState.Connected)
        override fun observeIdentity() = MutableStateFlow<ChatIdentity?>(ChatIdentity("me", "d"))
    }

    private val repository = ChatRepositoryImpl(sdk, engine, bus, outbox, dispatchers)

    private val conversation = ConversationId("c1")

    private fun message(sequence: Long) = Message(
        "m$sequence", null, conversation.value, "8102", "TEXT", "body $sequence", sequence, sequence, false,
    )

    @Test
    fun `a conversation spanning five pages is fetched in full by ONE call`() = runTest(dispatcher) {
        sdk.pages = (0 until 5).map { page -> (0 until 3).map { message(page * 3L + it) } }

        val outcome = repository.syncMessages(conversation)

        assertIs<Outcome.Success<Unit>>(outcome)
        assertEquals(15, repository.observeMessages(conversation).first().size)
    }

    @Test
    fun `the loop stops when a page adds nothing, rather than spinning`() = runTest(dispatcher) {
        // The SDK returns its whole snapshot each time, so "no growth" is the termination
        // condition. Without it this would run to MAX_PAGES on every single sync.
        sdk.pages = listOf(listOf(message(1)), listOf(message(2)))

        repository.syncMessages(conversation)

        assertEquals(2, repository.observeMessages(conversation).first().size)
    }

    @Test
    fun `an empty conversation syncs without error`() = runTest(dispatcher) {
        sdk.pages = emptyList()

        assertIs<Outcome.Success<Unit>>(repository.syncMessages(conversation))
        assertTrue(repository.observeMessages(conversation).first().isEmpty())
    }

    @Test
    fun `a request the SDK cannot dispatch is NotConfigured, not a hang`() = runTest(dispatcher) {
        sdk.refuseRequests = true

        val outcome = repository.syncMessages(conversation)

        assertEquals(ChatFailure.NotConfigured, (outcome as Outcome.Failure).error)
    }

    @Test
    fun `a failed send shows in the thread as Failed, with the text intact`() = runTest(dispatcher) {
        sdk.isConnected = false

        val outcome = repository.sendText(conversation, "hello")

        assertEquals(ChatFailure.Network, (outcome as Outcome.Failure).error)
        val drawn = repository.observeMessages(conversation).first().single()
        assertEquals(ChatMessage.Delivery.Failed, drawn.delivery)
        assertEquals("hello", drawn.body, "a failed message lost the text the user typed")
    }

    @Test
    fun `a pushed message reaches an open thread`() = runTest(dispatcher) {
        // The bug detekt found as "bus is unused": without the event bus wired into
        // observeMessages, an incoming push sits in the SDK's cache and the open thread
        // never redraws. It would look like messages only arrived when you left and came
        // back, which is the kind of fault that gets blamed on the server.
        sdk.pages = listOf(listOf(message(1)))
        repository.syncMessages(conversation)
        assertEquals(1, repository.observeMessages(conversation).first().size)

        // A second page lands on the "server", then the push tells us about it.
        sdk.pages = listOf(listOf(message(1)), listOf(message(2)))
        sdk.messages(
            conversation.value,
            object : ChatCallback<List<Message>> {
                override fun onSuccess(result: List<Message>) = Unit
                override fun onError(error: com.chatserver.sdk.ChatError?) = Unit
            },
        )
        bus.publish(ChatEvent.Received(ChatModelMapper.toDomain(message(2))))
        testScheduler.advanceUntilIdle()

        assertEquals(2, repository.observeMessages(conversation).first().size)
    }

    @Test
    fun `a push for a DIFFERENT conversation does not disturb this one`() = runTest(dispatcher) {
        sdk.pages = listOf(listOf(message(1)))
        repository.syncMessages(conversation)

        bus.publish(
            ChatEvent.Received(
                ChatModelMapper.toDomain(
                    Message("x", null, "other", "8103", "TEXT", "elsewhere", 9, 9, false),
                ),
            ),
        )
        testScheduler.advanceUntilIdle()

        assertEquals(1, repository.observeMessages(conversation).first().size)
    }

    @Test
    fun `a send with a socket reports success with no id yet`() = runTest(dispatcher) {
        // Success with a NULL id is the contract: the server id arrives with the ack.
        sdk.isConnected = true

        val outcome = repository.sendText(conversation, "hello")

        assertIs<Outcome.Success<*>>(outcome)
        assertEquals(null, outcome.value)
    }
}
