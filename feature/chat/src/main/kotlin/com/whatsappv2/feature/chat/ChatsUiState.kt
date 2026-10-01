package com.whatsappv2.feature.chat

import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.chat.ChatConversation
import com.whatsappv2.domain.chat.ChatFailure
import com.whatsappv2.domain.chat.ConversationId

/**
 * What the Chats tab is showing.
 *
 * [isSignedIn] and [connection] are two different questions and the tab needs both: a
 * signed-in user whose socket is down still sees their conversation list, with a banner
 * over it — whereas a signed-out one has no list to show at all.
 *
 * ## Search and pinning are both derived here, in that order
 *
 * The screen renders [pinnedConversations] and [otherConversations] and decides nothing.
 * Doing it here is what keeps one answer to "which section is this row in" — computed
 * once from [conversations], [pinned] and [query] — rather than two filters in two
 * composables that can disagree about a row that matches the search but is also pinned.
 */
data class ChatsUiState(
    val isSignedIn: Boolean = false,
    val connection: ChatConnectionState = ChatConnectionState.NotConfigured,
    val conversations: List<ChatConversation> = emptyList(),
    val isLoading: Boolean = false,
    val error: ChatFailure? = null,

    /** What was typed into the search field. Blank means "not searching". */
    val query: String = "",

    /** The pinned ids, newest pin first — the order they are drawn in. */
    val pinned: List<ConversationId> = emptyList(),
) {

    /** Signed in, nothing loading, nothing wrong, and nobody to talk to yet. */
    val isEmpty: Boolean get() = isSignedIn && conversations.isEmpty() && !isLoading && error == null

    /** Whether the search field has anything in it, which is what empty states branch on. */
    val isSearching: Boolean get() = query.isNotBlank()

    /**
     * The conversations a search leaves, in the order the server gave them.
     *
     * Matched on the title — the handle, after `guest-8102@guest.local` has been unwrapped
     * — and on the last message, because "what was that about the invoice" is the other
     * half of why anybody searches a chat list. Case-insensitive, trimmed: a trailing
     * space from a soft keyboard's autocomplete should not empty the screen.
     */
    private val matching: List<ChatConversation>
        get() = if (!isSearching) {
            conversations
        } else {
            val needle = query.trim()
            conversations.filter { conversation ->
                conversation.title.contains(needle, ignoreCase = true) ||
                    conversation.lastMessageBody?.contains(needle, ignoreCase = true) == true
            }
        }

    /**
     * The pinned rows, in pin order rather than in the list's own order.
     *
     * A pin that matches nothing in [conversations] is skipped rather than drawn empty —
     * it survives in storage (the list may simply not have loaded yet) but there is
     * nothing to render for it.
     */
    val pinnedConversations: List<ChatConversation>
        get() = pinned.mapNotNull { id -> matching.firstOrNull { it.id == id } }

    /** Everything else, still in the server's recency order. */
    val otherConversations: List<ChatConversation>
        get() = matching.filterNot { it.id in pinned }

    /** Whether a search returned nothing, which is a different empty from having no chats. */
    val hasNoMatches: Boolean
        get() = isSearching && pinnedConversations.isEmpty() && otherConversations.isEmpty()

    /** Whether [id] is pinned — what the row's icon and its long-press both read. */
    fun isPinned(id: ConversationId): Boolean = id in pinned
}
