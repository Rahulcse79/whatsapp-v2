package com.whatsappv2.domain.chat

/**
 * A conversation's id, as the chat server issues it.
 *
 * A value class rather than a bare `String`, matching `AccountId` and `CallId`: these ids
 * travel through navigation arguments, map keys and callbacks alongside **message** ids
 * and **user** ids, all of which are opaque strings of the same shape. The compiler is the
 * only thing that reliably keeps them apart, and it costs no allocation to let it.
 */
@JvmInline
value class ConversationId(val value: String) {
    override fun toString(): String = value
}

/**
 * A message's server id.
 *
 * Nullable wherever it appears, because a message this device has sent does not have one
 * until the server acknowledges it — see [ChatMessage.clientId], which is the handle that
 * exists for the whole of a send.
 */
@JvmInline
value class ChatMessageId(val value: String) {
    override fun toString(): String = value
}

/**
 * Who this device is, to the chat server.
 *
 * Resolved once when the socket comes up. It matters before that: a message sent while it
 * is still unknown carries a null sender and renders on the wrong side of the thread
 * (finding 1.3-5), which is why the composer waits for it.
 */
data class ChatIdentity(val userId: String, val deviceId: String)
