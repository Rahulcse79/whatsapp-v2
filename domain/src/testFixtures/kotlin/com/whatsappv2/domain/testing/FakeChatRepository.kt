package com.whatsappv2.domain.testing

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.result.success
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
import kotlinx.coroutines.flow.map

/**
 * A chat server that lives in memory and answers whatever a test taught it.
 *
 * Beside the other fakes, and for the reason `FakeSipEngine` exists: it lets the whole app
 * be driven with **no chat server at all** (DoD 4). The thread screen, the conversation
 * list and their ViewModels are all built against this one, so none of them needed a
 * socket to exist before they could be written.
 *
 * Starts [ChatConnectionState.NotConfigured] and signed out, which is what a fresh install
 * is — so the ordinary case in a test is the case the app has to keep working for.
 *
 * ## What it reproduces rather than fakes
 *
 * The three SDK behaviours the port exists to hide, because a fake that smoothed them over
 * would let a broken caller pass:
 *
 *  - [sendText] with no connection **fails** and leaves the message
 *    [ChatMessage.Delivery.Failed], rather than silently succeeding.
 *  - a message sent before [identity] is known carries a **null sender**, so a thread that
 *    renders it on the wrong side fails here instead of on a device.
 *  - [syncMessages] hands back a page at a time from [history], so a caller that assumed
 *    one call meant "synced" sees a short thread.
 */
class FakeChatRepository : ChatRepository {

    private val connection = MutableStateFlow<ChatConnectionState>(ChatConnectionState.NotConfigured)
    private val conversations = MutableStateFlow<List<ChatConversation>>(emptyList())
    private val messages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    private val identity = MutableStateFlow<ChatIdentity?>(null)

    /** Server-side history, handed out [pageSize] at a time by [syncMessages]. */
    private val history = mutableMapOf<String, MutableList<ChatMessage>>()

    /** How much of [history] each conversation has been given. */
    private val delivered = mutableMapOf<String, Int>()

    /** The SDK's page is 200; a small one here makes the loop observable in a test. */
    var pageSize: Int = 2

    /** Every text handed to [sendText], in order. */
    val sent: MutableList<Pair<ConversationId, String>> = mutableListOf()

    /** Conversations opened, in order. */
    val opened: MutableList<String> = mutableListOf()

    /** How many times [syncMessages] hit the "server". */
    var syncCalls: Int = 0
        private set

    var connectCalls: Int = 0
        private set

    /** What the next call answers, when a test wants a failure. */
    var nextFailure: ChatFailure? = null

    private var clientIds = 0

    override fun observeConnection(): Flow<ChatConnectionState> = connection

    override fun observeConversations(): Flow<List<ChatConversation>> = conversations

    override fun observeMessages(conversationId: ConversationId): Flow<List<ChatMessage>> =
        messages.map { it[conversationId.value].orEmpty() }

    override fun observeIdentity(): Flow<ChatIdentity?> = identity

    override suspend fun refreshConversations(): Outcome<Unit, ChatFailure> =
        nextFailure?.let { failure(it) } ?: success(Unit)

    override suspend fun syncMessages(conversationId: ConversationId): Outcome<Unit, ChatFailure> {
        nextFailure?.let { return failure(it) }
        syncCalls++

        val all = history[conversationId.value].orEmpty()
        val already = delivered[conversationId.value] ?: 0
        val next = minOf(already + pageSize, all.size)
        delivered[conversationId.value] = next

        // A page at a time, exactly as the SDK does: the caller must loop or fall short.
        messages.value = messages.value + (conversationId.value to all.take(next))
        return success(Unit)
    }

    override suspend fun openDirectConversation(otherUserId: String): Outcome<ConversationId, ChatFailure> {
        nextFailure?.let { return failure(it) }
        opened += otherUserId

        val id = ConversationId("conv-$otherUserId")
        if (conversations.value.none { it.id == id }) {
            conversations.value = conversations.value + conversation(id, otherUserId)
        }
        return success(id)
    }

    override suspend fun sendText(
        conversationId: ConversationId,
        text: String,
    ): Outcome<ChatMessageId?, ChatFailure> {
        sent += conversationId to text
        nextFailure?.let { return failure(it) }

        val clientId = "client-${clientIds++}"
        val failed = !connection.value.isUsable

        append(
            conversationId,
            ChatMessage(
                id = if (failed) null else ChatMessageId("server-$clientId"),
                clientId = clientId,
                conversationId = conversationId,
                // Null while the identity is unknown - the real thing does this, and a
                // thread that renders it on the wrong side should fail here.
                senderId = identity.value?.userId,
                type = ChatMessageType.TEXT,
                body = text,
                sequenceNumber = 0,
                createdAtMs = 0,
                delivery = if (failed) ChatMessage.Delivery.Failed else ChatMessage.Delivery.Sent,
            ),
        )

        // A send with no socket is a failure, not a message that never arrives.
        return if (failed) failure(ChatFailure.Network) else success(ChatMessageId("server-$clientId"))
    }

    override suspend fun retry(conversationId: ConversationId, clientId: String): Outcome<Unit, ChatFailure> {
        nextFailure?.let { return failure(it) }

        val thread = messages.value[conversationId.value].orEmpty().map {
            if (it.clientId == clientId) it.copy(delivery = ChatMessage.Delivery.Sent) else it
        }
        messages.value = messages.value + (conversationId.value to thread)
        return success(Unit)
    }

    override fun connect() {
        connectCalls++
        if (connection.value == ChatConnectionState.NotConfigured) return
        connection.value = ChatConnectionState.Connected
    }

    // ------------------------------------------------------------------ teaching it things

    /** Brings the socket up and, with it, the identity a composer waits for. */
    fun givenConnected(userId: String = "me"): FakeChatRepository = apply {
        identity.value = ChatIdentity(userId = userId, deviceId = "device-$userId")
        connection.value = ChatConnectionState.Connected
    }

    /** Connected but with nobody resolved yet — the window finding 1.3-5 describes. */
    fun givenConnectedWithoutIdentity(): FakeChatRepository = apply {
        identity.value = null
        connection.value = ChatConnectionState.Connected
    }

    fun givenDisconnected(code: Int = 1006, reason: String? = null): FakeChatRepository = apply {
        connection.value = ChatConnectionState.Disconnected(code, reason)
    }

    fun givenConversation(conversation: ChatConversation): FakeChatRepository = apply {
        conversations.value = conversations.value + conversation
    }

    /** Puts messages on the "server", to be handed over a page at a time by [syncMessages]. */
    fun givenHistory(conversationId: ConversationId, vararg message: ChatMessage): FakeChatRepository = apply {
        history.getOrPut(conversationId.value) { mutableListOf() } += message
    }

    /** Delivers a message as though it had just been pushed. */
    fun givenIncoming(message: ChatMessage): FakeChatRepository = apply {
        append(message.conversationId, message)
    }

    fun givenFailing(failure: ChatFailure): FakeChatRepository = apply { nextFailure = failure }

    private fun append(conversationId: ConversationId, message: ChatMessage) {
        val thread = messages.value[conversationId.value].orEmpty() + message
        messages.value = messages.value + (conversationId.value to thread)
    }

    private fun conversation(id: ConversationId, otherUserId: String) = ChatConversation(
        id = id,
        type = "DIRECT",
        createdAtMs = 0,
        muted = false,
        archived = false,
        pinned = false,
        otherUserId = otherUserId,
        otherUserContactIdentifier = otherUserId,
        lastMessageBody = null,
        lastMessageType = null,
        lastMessageSenderId = null,
        lastMessageAtMs = 0,
        unreadCount = 0,
    )
}
