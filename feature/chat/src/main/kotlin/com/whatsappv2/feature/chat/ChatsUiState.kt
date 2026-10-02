package com.whatsappv2.feature.chat

import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.chat.ChatContact
import com.whatsappv2.domain.chat.ChatConversation
import com.whatsappv2.domain.chat.ChatFailure
import com.whatsappv2.domain.chat.ChatReadMark
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.chat.isSelfConversation

/**
 * The read action a conversation can usefully be offered, or none.
 *
 * Decided beside the state rather than in the sheet so it is testable without a screen, and
 * so there is one answer to "which of these two makes sense" — a menu that offered both
 * would let somebody mark a chat read and unread in the same breath.
 */
enum class ConversationReadAction {
    /** It has a badge this device can clear. */
    MarkRead,

    /** It has none, but the server still counts it unread, so the badge can be restored. */
    MarkUnread,
}

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

    /**
     * The company directory, keyed by the handle a conversation is addressed to.
     *
     * Here so a row can read `8102 (mcx8102)`. The chat server knows this person only as
     * `mcx8102` — the extension is the directory's, and nothing else in the app can join
     * the two. Empty until the directory loads, which is why every lookup falls back to
     * what the conversation itself says.
     */
    val directory: Map<String, ChatContact> = emptyMap(),

    /**
     * The signed-in user's own `8101 (mcx8101)`, drawn under the title.
     *
     * Null while signed out, and null for an account the login gave no extension — in both
     * cases the header is the bare title rather than a label with nothing in it.
     */
    val myExtensionLabel: String? = null,

    /**
     * How far each conversation has been read on this device — see `ChatReadRepository`.
     *
     * Local, because chat-node refuses `message.read`. Without it the server's `unreadCount`
     * only ever goes up and a chat you have just read keeps its badge for ever.
     */
    val readMarks: Map<ConversationId, ChatReadMark> = emptyMap(),

    /** The signed-in user's designation (`mcx8102`), for spotting a chat with themselves. */
    val myUsername: String? = null,

    /** The signed-in user's extension (`8102`), for the same reason — see [isSelfConversation]. */
    val myExtension: String? = null,
) {

    /**
     * The conversations worth showing: everything that is not this user talking to themselves.
     *
     * An earlier build addressed conversations by extension, so signing in as `mcx8102`
     * created `guest-8102@guest.local` — a second identity for the same person, which the
     * server reports back as an ordinary counterparty. The row looks like somebody called
     * `8102`, and calling it dials the user's own extension. See [isSelfConversation].
     *
     * Hidden rather than deleted because there is no delete: `ChatSdk` has none and
     * chat-node exposes no frame for one. These rows are on the server for good.
     */
    val visibleConversations: List<ChatConversation>
        get() = conversations.filterNot { isSelf(it) }

    /** Whether [conversation] is addressed to the signed-in user themselves. */
    fun isSelf(conversation: ChatConversation): Boolean =
        isSelfConversation(conversation.title, myUsername, myExtension)

    /** Signed in, nothing loading, nothing wrong, and nobody to talk to yet. */
    val isEmpty: Boolean
        get() = isSignedIn && visibleConversations.isEmpty() && !isLoading && error == null

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
            visibleConversations
        } else {
            val needle = query.trim()
            visibleConversations.filter { conversation ->
                // The rendered title, so searching "8102" finds a row the server calls
                // mcx8102 — what is on screen is what a search should match.
                titleOf(conversation).contains(needle, ignoreCase = true) ||
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

    /**
     * What to write on a row: the directory's `8102 (mcx8102)`, or the bare handle.
     *
     * The fallback is not a failure case — a conversation with somebody outside the
     * directory has no extension to show, and the handle is the honest answer.
     */
    fun titleOf(conversation: ChatConversation): String =
        directory[conversation.title]?.label ?: conversation.title

    /**
     * What the avatar should take its initials from — a name, never the label.
     *
     * The initials of `8102 (mcx8102)` are `8(`, which reads as a rendering fault rather
     * than as a person. So the avatar gets the directory's display name, and the handle
     * when there is no directory row to ask.
     */
    fun avatarNameOf(conversation: ChatConversation): String =
        directory[conversation.title]?.displayName ?: conversation.title

    /**
     * Which read action to offer for [conversation], or null when neither would do anything.
     *
     * Null is the honest answer for a chat the server counts as zero and this device has
     * read: there is no badge to clear, and nothing to restore if it were marked unread —
     * the server never learned it was read, so there is no number to put back. Offering an
     * action there would be a menu item that does nothing when tapped.
     */
    fun readActionFor(conversation: ChatConversation): ConversationReadAction? = when {
        unreadOf(conversation) > 0 -> ConversationReadAction.MarkRead
        conversation.unreadCount > 0 -> ConversationReadAction.MarkUnread
        else -> null
    }

    /**
     * How many messages have arrived since this device last read the chat.
     *
     * ## Not the server's number, which is a lifetime total
     *
     * chat-node's `unreadCount` never goes down — there is no `message.read` frame to tell it
     * anything was seen — so it counts every incoming message the conversation has ever had.
     * Showing it raw put **7** on a chat with one new message, reported from a device on
     * 2 Oct 2026.
     *
     * Because that total only ever rises, the **difference** against what it said when the
     * chat was last read is exactly how many have come since. That is the number here.
     *
     * Three cases, in order:
     * - nothing newer than the mark: no badge, which is the only thing that ever clears one;
     * - a mark exists and the chat has moved past it: the difference, and at least one — the
     *   timestamp has already proved something arrived, so a badge of zero would contradict
     *   it;
     * - no mark at all: the server's total stands. This chat has never been opened **here**,
     *   so every message in it is genuinely unread on this device.
     */
    fun unreadOf(conversation: ChatConversation): Int {
        if (conversation.unreadCount <= 0) return 0
        val mark = readMarks[conversation.id] ?: return conversation.unreadCount
        if (mark.readUpToMs >= conversation.lastMessageAtMs) return 0
        return (conversation.unreadCount - mark.serverUnreadAtRead).coerceAtLeast(1)
    }
}
