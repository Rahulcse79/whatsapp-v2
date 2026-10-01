package com.whatsappv2.data.chat

import com.chatserver.sdk.ChatCallback
import com.chatserver.sdk.ChatError
import com.whatsappv2.core.common.dispatcher.DispatcherProvider
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
import com.whatsappv2.data.chat.sdk.ChatSdkHandle
import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.chat.ChatConversation
import com.whatsappv2.domain.chat.ChatFailure
import com.whatsappv2.domain.chat.ChatIdentity
import com.whatsappv2.domain.chat.ChatMessage
import com.whatsappv2.domain.chat.ChatMessageId
import com.whatsappv2.domain.chat.ChatMessageType
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import com.chatserver.sdk.model.Conversation as SdkConversation
import com.chatserver.sdk.model.Message as SdkMessage

/**
 * Conversations and messages, over the chat SDK.
 *
 * ## Three things it does that the SDK does not
 *
 * **It loops.** [syncMessages] calls `getMessages` until the snapshot stops growing. The
 * SDK advances its cursor by one 200-message page per call and returns its *whole*
 * snapshot each time, so "did the snapshot grow" is the termination condition — and a
 * conversation with a thousand messages genuinely needs five calls.
 *
 * **It reports a failed send.** [sendText] goes through [ChatSendOutbox], which asks
 * `isConnected` before offering anything. Below this line a send with no socket is
 * dropped in silence.
 *
 * **It answers before `init`.** Every call checks the handle and returns
 * [ChatFailure.NotConfigured] rather than letting `ChatSdk.get()` throw.
 *
 * ## The callback bridge
 *
 * `suspendCancellableCoroutine` per `ChatCallback`. The SDK delivers every callback on the
 * **main thread**, so each bridge is wrapped in `withContext(dispatchers.io)` — the
 * resumption hops back off the main thread before any mapping runs, which is what keeps
 * a two-hundred-message page from being mapped on the UI thread.
 */
@Singleton
internal class ChatRepositoryImpl @Inject constructor(
    private val sdk: ChatSdkHandle,
    private val engine: ChatEngineState,
    private val bus: ChatEventBus,
    private val outbox: ChatSendOutbox,
    private val dispatchers: DispatcherProvider,
) : ChatRepository {

    private val conversations = MutableStateFlow<List<ChatConversation>>(emptyList())

    /**
     * Bumped whenever this app does something that changes a thread — a sync, a send, a
     * retry. The SDK's own cache is the source of truth for messages, so there is nothing
     * to store here; what is needed is a reason to re-read it.
     */
    private val localRevision = MutableStateFlow(0)

    override fun observeConnection(): Flow<ChatConnectionState> = engine.observeConnection()

    override fun observeIdentity(): Flow<ChatIdentity?> = engine.observeIdentity()

    override fun observeConversations(): Flow<List<ChatConversation>> = conversations

    /**
     * One thread, kept current by three things: the snapshot a sync produced, the outbox's
     * own view of what is in flight or failed, and a live re-read whenever an event lands.
     *
     * The outbox is combined in rather than merged into the snapshot, because a **failed**
     * message does not exist in the SDK at all — it was never handed over. Without this
     * the bubble would simply vanish when the screen recomposed.
     */
    override fun observeMessages(conversationId: ConversationId): Flow<List<ChatMessage>> {
        // Three reasons to re-read, and the SERVER's is the one that is easy to forget:
        // without the bus, a message pushed while the thread is open would sit in the
        // SDK's cache and never reach the screen until something else happened to refresh.
        val triggers = merge(
            flowOf(Unit),
            localRevision.map { },
            bus.events().filter { it.touches(conversationId) }.map { },
        )

        return combine(
            triggers.map { sdk.cachedMessages(conversationId.value).map(ChatModelMapper::toDomain) },
            outbox.observe(),
        ) { fetched, pending ->
            // A FAILED message does not exist in the SDK at all - it was never handed
            // over - so it has to be added here or the bubble vanishes on recomposition.
            val failed = pending.values
                .filter { it.failed && it.conversationId == conversationId.value }
                .map { it.asFailedMessage(conversationId) }

            (fetched + failed).sortedWith(compareBy({ it.sequenceNumber }, { it.createdAtMs }))
        }
    }

    /** Whether an event changes what this conversation should be showing. */
    private fun ChatEvent.touches(conversationId: ConversationId): Boolean = when (this) {
        is ChatEvent.Received -> message.conversationId == conversationId
        is ChatEvent.Acknowledged -> message.conversationId == conversationId
        // A reconnection can mean missed messages, so the thread re-reads on one too.
        ChatEvent.Connected -> true
        is ChatEvent.Disconnected -> false
    }

    override suspend fun refreshConversations(): Outcome<Unit, ChatFailure> =
        withContext(dispatchers.io) {
            // A `when` rather than Outcome.map: kotlinx's Flow.map is imported for
            // observeMessages and shadows it, and disambiguating by import alias would be
            // less readable than branching once.
            when (val rows = bridge<List<SdkConversation>> { sdk.conversations(it) }) {
                is Outcome.Failure -> rows
                is Outcome.Success -> {
                    conversations.value = rows.value
                        .map(ChatModelMapper::toDomain)
                        .sortedByDescending { it.lastMessageAtMs }
                    success(Unit)
                }
            }
        }

    /**
     * Fetches until the server has nothing newer.
     *
     * Bounded by [MAX_PAGES] as well as by the snapshot's size, because "loop until it
     * stops growing" trusts the server to eventually stop — and an endpoint that returned
     * one duplicate row for ever would otherwise spin here with no way for a caller to
     * interrupt it.
     */
    override suspend fun syncMessages(conversationId: ConversationId): Outcome<Unit, ChatFailure> =
        withContext(dispatchers.io) {
            var seen = -1
            repeat(MAX_PAGES) {
                val page = bridge<List<SdkMessage>> { sdk.messages(conversationId.value, it) }
                when (page) {
                    is Outcome.Failure -> return@withContext page
                    is Outcome.Success -> {
                        bumpRevision()
                        // The SDK returns its WHOLE snapshot each time, so no growth means
                        // the cursor has caught up. That is the termination condition.
                        if (page.value.size == seen) return@withContext success(Unit)
                        seen = page.value.size
                    }
                }
            }
            success(Unit)
        }

    override suspend fun openDirectConversation(otherUserId: String): Outcome<ConversationId, ChatFailure> =
        withContext(dispatchers.io) {
            when (val opened = bridge<String> { sdk.openDirectConversation(otherUserId, it) }) {
                is Outcome.Failure -> opened
                is Outcome.Success -> success(ConversationId(opened.value))
            }
        }

    override suspend fun sendText(
        conversationId: ConversationId,
        text: String,
    ): Outcome<ChatMessageId?, ChatFailure> = withContext(dispatchers.io) {
        if (!sdk.isInitialised) return@withContext failure(ChatFailure.NotConfigured)

        // Null means the outbox refused it: there was no socket, and the SDK would have
        // dropped it without saying so. The entry is kept and will be re-offered.
        outbox.offer(conversationId.value, text)
            ?: return@withContext failure(ChatFailure.Network)

        bumpRevision()
        // Success with a NULL id: the message is in flight and the server id does not
        // exist until the ack arrives as onMessageSent. The port's KDoc says so, and the
        // bubble shows Pending until then.
        success(null)
    }

    override suspend fun retry(conversationId: ConversationId, clientId: String): Outcome<Unit, ChatFailure> =
        withContext(dispatchers.io) {
            val sent = outbox.retry(clientId)
            bumpRevision()
            if (sent) success(Unit) else failure(ChatFailure.Network)
        }

    override fun connect() = sdk.connect()

    /** Tells every open thread to re-read the SDK's cache. */
    private fun bumpRevision() {
        localRevision.value += 1
    }

    private fun OutboxEntry.asFailedMessage(conversationId: ConversationId) = ChatMessage(
        id = null,
        clientId = clientId,
        conversationId = conversationId,
        senderId = sdk.myUserId,
        type = ChatMessageType.TEXT,
        body = text,
        sequenceNumber = Long.MAX_VALUE,
        createdAtMs = System.currentTimeMillis(),
        delivery = ChatMessage.Delivery.Failed,
    )

    /**
     * One `ChatCallback` as one suspending call.
     *
     * `suspendCancellableCoroutine` rather than `suspendCoroutine`, so a screen that
     * closes mid-request cancels the coroutine instead of leaking it until the callback
     * fires. The SDK offers no way to cancel the request itself, so the resumption is
     * simply abandoned — which is the best available and is why the check exists.
     */
    private suspend fun <T> bridge(request: (ChatCallback<T>) -> Boolean): Outcome<T, ChatFailure> =
        suspendCancellableCoroutine { continuation ->
            val callback = object : ChatCallback<T> {
                override fun onSuccess(result: T) {
                    if (continuation.isActive) continuation.resume(success(result))
                }

                override fun onError(error: ChatError?) {
                    if (continuation.isActive) continuation.resume(failure(ChatModelMapper.toDomain(error)))
                }
            }
            if (!request(callback) && continuation.isActive) {
                continuation.resume(failure(ChatFailure.NotConfigured))
            }
        }

    private companion object {
        /**
         * The SDK's page is 200, so this covers 20 000 messages in one sync.
         *
         * A bound rather than a `while (true)`: the loop's real exit is "the snapshot
         * stopped growing", which trusts the server to stop. This is what makes a server
         * that never does a slow sync rather than a hang.
         */
        const val MAX_PAGES = 100
    }
}
