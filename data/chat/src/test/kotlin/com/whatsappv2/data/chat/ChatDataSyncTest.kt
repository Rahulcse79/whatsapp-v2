package com.whatsappv2.data.chat

import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.core.common.logging.NoOpLogger
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.data.chat.sdk.FakeChatSdkHandle
import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.chat.ChatCredentials
import com.whatsappv2.domain.chat.ChatGroup
import com.whatsappv2.domain.chat.ChatGroupMember
import com.whatsappv2.domain.chat.ChatIdentity
import com.whatsappv2.domain.chat.ChatMessage
import com.whatsappv2.domain.chat.ChatMessageType
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.chat.CoralServerUrl
import com.whatsappv2.domain.chat.GroupRole
import com.whatsappv2.domain.testing.FakeChatContactRepository
import com.whatsappv2.domain.testing.FakeChatGroupRepository
import com.whatsappv2.domain.testing.FakeChatSessionRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import com.chatserver.sdk.model.Conversation as SdkConversation

/**
 * The Chats list keeping up with what the socket says, without anybody asking it to.
 *
 * Reported from a device on 2 Oct 2026: send a message to somebody, press Back, and the
 * list did not show it — the chat appeared only after the thread had been opened. The thread
 * was never wrong, which is what made the list look like it needed opening to work.
 *
 * The cause was that `observeConversations` had no live trigger at all, while
 * `observeMessages` has three. These tests are written against the list's observable state
 * rather than against the number of fetches, so they fail for the user's reason.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatDataSyncTest {

    // Unconfined throughout. The bus is a MutableSharedFlow with no replay, so a test where
    // the collector is still a queued task when an event is published measures nothing: it
    // fails identically whether the production code is right or wrong. Unconfined makes the
    // subscription and the fetch both eager, so each assertion is about behaviour.
    private val dispatcher = UnconfinedTestDispatcher()
    private val sdk = FakeChatSdkHandle().apply { markInitialised() }
    private val bus = ChatEventBus()
    private val outbox = ChatSendOutbox(sdk)
    private val cache = ChatMemoryCache()

    private val dispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = dispatcher
        override val io: CoroutineDispatcher get() = dispatcher
        override val default: CoroutineDispatcher get() = dispatcher
        override val unconfined: CoroutineDispatcher get() = dispatcher
    }

    private val engine = object : ChatEngineState {
        override fun observeConnection() = MutableStateFlow<ChatConnectionState>(ChatConnectionState.Connected)
        override fun observeIdentity() = MutableStateFlow<ChatIdentity?>(ChatIdentity("me", "d"))
    }

    private val repository = ChatRepositoryImpl(sdk, engine, bus, outbox, cache, dispatchers)
    private val contacts = FakeChatContactRepository()
    private val groupRepository = FakeChatGroupRepository()
    private val sessions = FakeChatSessionRepository()
    private val sync = ChatDataSync(repository, contacts, groupRepository, sessions, bus, NoOpLogger)

    /** Starts the loop on the test's background job, so it is cancelled with the test. */
    private fun TestScope.startSync() = sync.start(backgroundScope)

    private fun serverConversation(id: String, lastBody: String) = SdkConversation(
        id, "DIRECT", 0L, false, false, false,
        "guest-mcx8102@guest.local", "guest-mcx8102@guest.local",
        "m1", lastBody, "TEXT", "me", 1L, "SENT", false, 0,
    )

    private fun group(id: String) = ChatGroup(
        id = ConversationId(id),
        name = "Ops",
        createdBy = "owner-ulid",
        members = listOf(
            ChatGroupMember("owner-ulid", GroupRole.OWNER),
            ChatGroupMember("01ULIDFOR8102", GroupRole.MEMBER),
        ),
    )

    /** A GROUP row, which is how chat-node reports one: typed GROUP, with NO other party. */
    private fun serverGroup(id: String, lastBody: String) = SdkConversation(
        id, "GROUP", 0L, false, false, false,
        null, null,
        "m1", lastBody, "TEXT", "me", 1L, "SENT", false, 0,
    )

    private fun message(conversationId: String) = ChatMessage(
        id = null,
        clientId = "c1",
        conversationId = ConversationId(conversationId),
        senderId = "me",
        type = ChatMessageType.TEXT,
        body = "hello",
        sequenceNumber = 1,
        createdAtMs = 1,
        delivery = ChatMessage.Delivery.Sent,
    )

    @Test
    fun `a conversation the user has just started appears without the list being asked`() =
        runTest(dispatcher) {
            startSync()
            testScheduler.advanceUntilIdle()
            // Nothing yet: the list has never been fetched, which is the state after a
            // sign-in that has not finished its first refresh.
            assertEquals(emptyList(), cache.conversations.value)

            // The server now knows about the chat, and says so by acknowledging the message
            // that created it.
            sdk.conversationsResult = listOf(serverConversation("c1", "hello"))
            bus.publish(ChatEvent.Acknowledged(message("c1")))
            testScheduler.advanceUntilIdle()

            // This is the bug, in one assertion: before the fix nothing wrote the cache
            // until somebody called refreshConversations(), so pressing Back showed a list
            // that did not contain the chat just created.
            assertEquals(listOf(ConversationId("c1")), repository.observeConversations().first().map { it.id })
        }

    @Test
    fun `an incoming message updates the preview of a chat already in the list`() = runTest(dispatcher) {
        startSync()
        sdk.conversationsResult = listOf(serverConversation("c1", "first"))
        repository.refreshConversations()
        testScheduler.advanceUntilIdle()
        assertEquals("first", cache.conversations.value.single().lastMessageBody)

        sdk.conversationsResult = listOf(serverConversation("c1", "second"))
        bus.publish(ChatEvent.Received(message("c1")))
        testScheduler.advanceUntilIdle()

        assertEquals("second", cache.conversations.value.single().lastMessageBody)
    }

    @Test
    fun `a connection event is left to the ViewModel, so one connect is not two fetches`() =
        runTest(dispatcher) {
            startSync()
            sdk.conversationsResult = listOf(serverConversation("c1", "first"))

            bus.publish(ChatEvent.Connected)
            testScheduler.advanceUntilIdle()

            // ChatsViewModel refreshes on Connected, with its loading state attached. Doing
            // it here as well would be two fetches racing over which answer lands last.
            assertEquals(emptyList(), cache.conversations.value)
        }

    @Test
    fun `a disconnection fetches nothing, because there is nothing to ask`() = runTest(dispatcher) {
        startSync()
        sdk.conversationsResult = listOf(serverConversation("c1", "first"))

        bus.publish(ChatEvent.Disconnected(1006, "gone"))
        testScheduler.advanceUntilIdle()

        assertEquals(emptyList(), cache.conversations.value)
    }

    @Test
    fun `starting twice does not run two loops`() = runTest(dispatcher) {
        startSync()
        startSync()
        sdk.conversationsResult = listOf(serverConversation("c1", "first"))

        bus.publish(ChatEvent.Received(message("c1")))
        testScheduler.advanceUntilIdle()

        // One fetch, not two. A second loop would double every request the app makes for
        // the rest of the process's life.
        assertEquals(1, sdk.conversationsCalls)
        assertEquals("first", cache.conversations.value.single().lastMessageBody)
    }

    @Test
    fun `a message in a direct chat does not re-read every group roster`() = runTest(dispatcher) {
        startSync()
        sdk.conversationsResult = listOf(serverConversation("c1", "first"))

        bus.publish(ChatEvent.Received(message("c1")))
        testScheduler.advanceUntilIdle()

        // The group refresh is `GET /groups` plus one `GET /members` PER GROUP, and this event
        // fires on every message sent or received. Doing it unconditionally put six requests
        // on the wire per message for somebody in five groups.
        assertEquals(0, groupRepository.refreshCount, "a direct message triggered a group refresh")
    }

    @Test
    fun `a message in a group this device cannot name does re-read the groups`() = runTest(dispatcher) {
        startSync()
        // A GROUP row arrives that nothing has a name for: `GET /conversations` returns its
        // name as null, so without this the row would sit in the list untitled.
        sdk.conversationsResult = listOf(serverGroup("g1", "first"))
        groupRepository.serverGroups = listOf(group("g1"))

        bus.publish(ChatEvent.Received(message("g1")))
        testScheduler.advanceUntilIdle()

        assertEquals(1, groupRepository.refreshCount, "an unknown group was left unnamed")
    }

    @Test
    fun `a second message in a group already on file costs nothing`() = runTest(dispatcher) {
        startSync()
        sdk.conversationsResult = listOf(serverGroup("g1", "first"))
        groupRepository.serverGroups = listOf(group("g1"))
        bus.publish(ChatEvent.Received(message("g1")))
        testScheduler.advanceUntilIdle()
        assertEquals(1, groupRepository.refreshCount)

        // The group is known now, so the condition is false and the hot path is free. This is
        // the case that happens on every message of an ordinary conversation.
        bus.publish(ChatEvent.Received(message("g1")))
        testScheduler.advanceUntilIdle()

        assertEquals(1, groupRepository.refreshCount, "a known group was refreshed again")
    }

    @Test
    fun `signing in reads the groups unconditionally, because nothing is on file yet`() = runTest(dispatcher) {
        startSync()

        sessions.signIn(CoralServerUrl.DEFAULT, ChatCredentials("mcx8101", Secret("pw")))
        testScheduler.advanceUntilIdle()

        assertEquals(1, groupRepository.refreshCount)
    }
}
