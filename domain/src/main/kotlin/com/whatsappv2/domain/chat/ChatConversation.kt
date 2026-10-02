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
        get() = handleOf(otherUserContactIdentifier)
            ?: handleOf(otherUserId)
            ?: id.value

    /**
     * The extension to dial for the other party, or null when there is nothing dialable.
     *
     * ## It is NOT simply the title any more
     *
     * A conversation is addressed by **designation** — `mcx8101` — and that is what the
     * handle carries. No PBX can route it: the switch answers to `8101`. Returning the
     * handle regardless produced a call button that dialled `mcx8101` and failed, reported
     * from a device on 2 Oct 2026.
     *
     * The real answer is the company directory, which is the only thing that knows
     * `mcx8101` is on `8101`, and the thread joins against it. This property is the
     * **fallback** for a party the directory does not list, and it now gives an answer only
     * when the handle is itself dialable — digits, possibly with the separators a dial
     * string may carry. A handle like `mcx8101` yields null, so the call button is absent
     * rather than present and broken, which is the rule the rest of this screen follows.
     */
    val callableExtension: String?
        get() = (handleOf(otherUserContactIdentifier) ?: handleOf(otherUserId))
            ?.takeIf { it.matches(DIALABLE) }

    /**
     * Whether there is exactly one other party.
     *
     * [type] is the server's own word for it (`DIRECT`, `GROUP` or `BROADCAST`), and the
     * other-party fields are the fallback for a summary that arrives without one: the
     * server leaves both null for a group, so their presence says the same thing.
     *
     * It matters to the thread because a sender's name is worth drawing only when the
     * title does not already answer "who said this".
     */
    val isDirect: Boolean
        get() = type?.equals(DIRECT_TYPE, ignoreCase = true)
            ?: (otherUserContactIdentifier != null || otherUserId != null)
}

/** The server's name for a one-to-one conversation. */
private const val DIRECT_TYPE = "DIRECT"

/**
 * What a PBX could actually be asked to ring.
 *
 * Digits, with the characters a dial string legitimately carries. Deliberately narrow: the
 * question is not "is this a valid number" — `DialledTarget` decides that — but "is this a
 * number at all, rather than a chat username that would fail at the switch".
 */
private val DIALABLE = Regex("""[+*#0-9][*#0-9]*""")

/**
 * Unwraps chat-node's guest identity into the handle a person recognises.
 *
 * In guest mode the server provisions `guest-8102@guest.local` for the identifier
 * `8102`, and shows that back in every conversation summary. Rendering it raw puts a
 * synthetic email in the title bar where a name belongs — and, worse, gives the call
 * button something no PBX can dial. The reference client strips it the same way.
 *
 * Anything that is not of that shape is returned as it came: a real display name must
 * survive untouched.
 */
private fun handleOf(identifier: String?): String? {
    val value = identifier?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!value.startsWith(GUEST_PREFIX) || '@' !in value) return value

    return value.substring(GUEST_PREFIX.length, value.indexOf('@')).takeIf { it.isNotEmpty() } ?: value
}

private const val GUEST_PREFIX = "guest-"
