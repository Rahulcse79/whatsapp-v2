package com.whatsappv2.domain.chat

/**
 * A row in the conversation list.
 *
 * ## Everything here is read-only, and that is the server's doing
 *
 * [muted], [archived], [pinned] and [unreadCount] all arrive from the server and the SDK
 * exposes **no setter for any of them** (finding 1.3-10) — no read receipts, no typing
 * indicators, no delete, no group creation either. They are modelled because they are
 * shown; a UI that offered to change them would be offering something that cannot happen.
 */
data class ChatConversation(
    val id: ConversationId,
    val type: String?,
    val createdAtMs: Long,
    val muted: Boolean,
    val archived: Boolean,
    val pinned: Boolean,

    /** The other party, in a direct conversation. Null for anything that is not one. */
    val otherUserId: String?,

    /**
     * What the directory calls the other party.
     *
     * The chat server's own name for them, which is not necessarily the phonebook's — so
     * a screen that has both prefers this one, because it is what the message came with.
     */
    val otherUserContactIdentifier: String?,

    val lastMessageBody: String?,
    val lastMessageType: ChatMessageType?,
    val lastMessageSenderId: String?,
    val lastMessageAtMs: Long,
    val unreadCount: Int,
) {
    /** What to show as the row's title, falling back until something is printable. */
    val title: String
        get() = otherUserContactIdentifier?.takeIf { it.isNotBlank() }
            ?: otherUserId?.takeIf { it.isNotBlank() }
            ?: id.value
}
