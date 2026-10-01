package com.whatsappv2.data.chat

import com.chatserver.sdk.ChatError
import com.chatserver.sdk.model.Conversation
import com.chatserver.sdk.model.Message
import com.chatserver.sdk.model.User
import com.whatsappv2.domain.chat.ChatConversation
import com.whatsappv2.domain.chat.ChatFailure
import com.whatsappv2.domain.chat.ChatIdentity
import com.whatsappv2.domain.chat.ChatMessage
import com.whatsappv2.domain.chat.ChatMessageId
import com.whatsappv2.domain.chat.ChatMessageType
import com.whatsappv2.domain.chat.ConversationId

/**
 * `com.chatserver.sdk.model.*` → the domain's types, and back as little as possible.
 *
 * One file, so the SDK's vocabulary stops here. Everything above `:data:chat` speaks
 * domain types, which is what makes architecture rule 13 more than a naming convention:
 * swapping the SDK is this file, [com.whatsappv2.data.chat.sdk.ChatSdkHandle] and the
 * listener, rather than every ViewModel that ever touched a message.
 */
internal object ChatModelMapper {

    /**
     * A message, with its delivery state decided here rather than read from the SDK.
     *
     * The SDK has two states — `pending` true or false — and no third. [ChatMessage.Delivery.Failed]
     * is ours (finding 1.3-3), so it can only be applied by the outbox, which is the only
     * thing that knows a send was offered with no socket. From the SDK's own data a
     * message is Pending or Sent, and nothing else is honest.
     */
    fun toDomain(message: Message): ChatMessage = ChatMessage(
        id = message.messageId?.let(::ChatMessageId),
        clientId = message.clientMessageId,
        conversationId = ConversationId(message.conversationId.orEmpty()),
        senderId = message.senderId,
        type = ChatMessageType.ofWire(message.type),
        body = message.body,
        sequenceNumber = message.sequenceNumber,
        createdAtMs = message.createdAtMs,
        delivery = if (message.pending) ChatMessage.Delivery.Pending else ChatMessage.Delivery.Sent,
    )

    fun toDomain(conversation: Conversation): ChatConversation = ChatConversation(
        id = ConversationId(conversation.conversationId.orEmpty()),
        type = conversation.type,
        createdAtMs = conversation.createdAtMs,
        muted = conversation.muted,
        archived = conversation.archived,
        pinned = conversation.pinned,
        otherUserId = conversation.otherUserId,
        otherUserContactIdentifier = conversation.otherUserContactIdentifier,
        lastMessageBody = conversation.lastMessageBody,
        // Null rather than UNKNOWN when there is no last message at all: "this thread is
        // empty" and "this thread ends with something we cannot render" are different,
        // and a row that said "unsupported message" for an empty conversation would lie.
        lastMessageType = conversation.lastMessageType?.let(ChatMessageType::ofWire),
        lastMessageSenderId = conversation.lastMessageSenderId,
        lastMessageAtMs = conversation.lastMessageAtMs,
        unreadCount = conversation.unreadCount,
    )

    fun toDomain(user: User): ChatIdentity =
        ChatIdentity(userId = user.userId.orEmpty(), deviceId = user.deviceId.orEmpty())

    /**
     * A failure the UI can branch on.
     *
     * `httpStatus == 0` is the SDK's own marker for "no response at all" — it is what the
     * Retrofit adapter passes on a transport failure — so it maps to [ChatFailure.Network]
     * rather than to a server error with a nonsense code.
     */
    fun toDomain(error: ChatError?): ChatFailure = when {
        error == null -> ChatFailure.Unknown(null)
        error.httpStatus == NO_RESPONSE -> ChatFailure.Network
        error.httpStatus == UNAUTHORIZED || error.httpStatus == FORBIDDEN -> ChatFailure.Unauthorized
        else -> ChatFailure.Server(error.httpStatus, error.message)
    }

    private const val NO_RESPONSE = 0
    private const val UNAUTHORIZED = 401
    private const val FORBIDDEN = 403
}
