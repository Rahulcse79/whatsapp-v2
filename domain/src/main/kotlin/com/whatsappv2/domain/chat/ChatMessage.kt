package com.whatsappv2.domain.chat

/**
 * One message in a thread.
 *
 * Timestamps are epoch milliseconds rather than an `Instant`: architecture rule 1 keeps
 * Android off `:domain`, `java.time` here would be a desugared type, and the SDK hands
 * them over as `long` anyway. Converting twice to arrive back where we started would be
 * the only effect.
 */
data class ChatMessage(
    /** The server's id, or null until it acknowledges a message this device sent. */
    val id: ChatMessageId?,

    /**
     * The id this device minted when it sent the message.
     *
     * **The only handle that exists for the whole of a send**, which is why the ack is
     * matched on it rather than on the server id — the server id does not exist yet when
     * the send begins. Null on a message that arrived from somebody else.
     */
    val clientId: String?,

    val conversationId: ConversationId,

    /**
     * Who sent it, or null.
     *
     * Null is reachable and is not a bug in the server: a message sent before `me()` has
     * resolved carries no sender (finding 1.3-5). It renders on the wrong side until the
     * ack arrives, which is why the composer is disabled until the identity is known.
     */
    val senderId: String?,

    val type: ChatMessageType,
    val body: String?,

    /** The server's ordering. 0 on a message that has not been acknowledged yet. */
    val sequenceNumber: Long,
    val createdAtMs: Long,
    val delivery: Delivery,
) {

    /** True when this device sent it. False for a null sender, which is the safe side. */
    fun isMine(identity: ChatIdentity?): Boolean =
        identity != null && senderId != null && senderId == identity.userId

    /**
     * How far a message has got.
     *
     * [Failed] is **ours**. The SDK has no failure state for a send at all: a message
     * offered with no socket is dropped silently and stays `pending` for ever (finding
     * 1.3-3), so a bubble would spin until the process died. Naming the state is what lets
     * the thread offer a retry instead.
     */
    enum class Delivery {
        /** Sent, not yet acknowledged. */
        Pending,

        /** Acknowledged by the server. */
        Sent,

        /** Could not be sent, and will not be without another attempt. */
        Failed,
    }
}
