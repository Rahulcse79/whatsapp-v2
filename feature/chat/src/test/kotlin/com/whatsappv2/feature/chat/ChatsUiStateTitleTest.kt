package com.whatsappv2.feature.chat

import com.whatsappv2.domain.chat.ChatContact
import com.whatsappv2.domain.chat.ChatConversation
import com.whatsappv2.domain.chat.ChatReadMark
import com.whatsappv2.domain.chat.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The join between a conversation and the directory, which is what puts a number on a row.
 *
 * The chat server knows the other party only as `mcx8102`; the directory is the only thing
 * that knows they are on `8102`. Everything here is about what happens when those two
 * disagree, or when one of them has not arrived yet — the list has to stay readable in both
 * cases, because the directory loads after the conversations do.
 */
class ChatsUiStateTitleTest {

    private fun conversation(handle: String, unread: Int = 0, lastAtMs: Long = 0) = ChatConversation(
        id = ConversationId("conv-$handle"),
        type = "DIRECT",
        createdAtMs = 0,
        muted = false,
        archived = false,
        pinned = false,
        // The shape chat-node actually sends back in guest mode.
        otherUserId = "guest-$handle@guest.local",
        otherUserContactIdentifier = null,
        lastMessageBody = "about the invoice",
        lastMessageType = null,
        lastMessageSenderId = null,
        lastMessageAtMs = lastAtMs,
        unreadCount = unread,
    )

    private fun contact(username: String, extension: String?, displayName: String = "8102") = ChatContact(
        id = username,
        username = username,
        displayName = displayName,
        extension = extension,
        department = "coral-test",
        avatarUrl = null,
    )

    private fun state(vararg directory: ChatContact) = ChatsUiState(
        isSignedIn = true,
        conversations = listOf(conversation("mcx8102")),
        directory = directory.associateBy { it.id },
    )

    @Test
    fun `a row reads the extension and the designation once the directory is in`() {
        val state = state(contact(username = "mcx8102", extension = "8102"))

        assertEquals("8102 (mcx8102)", state.titleOf(state.conversations.single()))
    }

    @Test
    fun `the bare handle until the directory loads, rather than a blank row`() {
        val state = state()

        // The conversations arrive before the directory does. A row that waited would be
        // empty for as long as the phonebook request takes.
        assertEquals("mcx8102", state.titleOf(state.conversations.single()))
    }

    @Test
    fun `somebody outside the directory keeps their handle`() {
        val state = state(contact(username = "mcx9999", extension = "9999"))

        assertEquals("mcx8102", state.titleOf(state.conversations.single()))
    }

    @Test
    fun `the avatar takes the name, never the label`() {
        val state = state(contact(username = "mcx8102", extension = "8102"))

        // Initials of "8102 (mcx8102)" are "8(", which reads as a rendering fault.
        assertEquals("8102", state.avatarNameOf(state.conversations.single()))
    }

    @Test
    fun `search matches what the row actually shows`() {
        val state = state(contact(username = "mcx8102", extension = "8102"))

        // Typing the extension has to find a row the server calls mcx8102 — the whole point
        // of the join is that the extension is the half a person knows.
        assertEquals(1, state.copy(query = "8102").otherConversations.size)
        assertEquals(1, state.copy(query = "mcx").otherConversations.size)
        // And the last message, because "what was that about the invoice" is the other half
        // of why anybody searches a chat list.
        assertEquals(1, state.copy(query = "invoice").otherConversations.size)
        assertEquals(0, state.copy(query = "9999").otherConversations.size)
    }

    @Test
    fun `an unread chat the user has not opened keeps the server's count`() {
        val chat = conversation("mcx8102", unread = 3, lastAtMs = 500)
        val state = ChatsUiState(conversations = listOf(chat))

        assertEquals(3, state.unreadOf(chat))
    }

    @Test
    fun `reading a chat clears its badge, which nothing else ever does`() {
        val chat = conversation("mcx8102", unread = 3, lastAtMs = 500)
        val state = ChatsUiState(
            conversations = listOf(chat),
            readMarks = mapOf(chat.id to ChatReadMark(500L, 0)),
        )

        // The server's count only goes up — chat-node refuses `message.read` — so without
        // the local mark this badge stays on a chat the user has read, for ever.
        assertEquals(0, state.unreadOf(chat))
    }

    @Test
    fun `a message arriving after the chat was read brings the badge back`() {
        val chat = conversation("mcx8102", unread = 1, lastAtMs = 900)
        val state = ChatsUiState(
            conversations = listOf(chat),
            // Read up to 500 when the server was counting nothing; the newest message is
            // newer than that.
            readMarks = mapOf(chat.id to ChatReadMark(500L, 0)),
        )

        assertEquals(1, state.unreadOf(chat))
    }

    @Test
    fun `a chat the server calls read shows nothing, mark or no mark`() {
        val chat = conversation("mcx8102", unread = 0, lastAtMs = 900)

        assertEquals(0, ChatsUiState(conversations = listOf(chat)).unreadOf(chat))
        val read = ChatsUiState(
            conversations = listOf(chat),
            readMarks = mapOf(chat.id to ChatReadMark(1L, 0)),
        )
        assertEquals(0, read.unreadOf(chat))
    }

    @Test
    fun `another chat's read mark does not clear this one`() {
        val chat = conversation("mcx8102", unread = 2, lastAtMs = 500)
        val state = ChatsUiState(
            conversations = listOf(chat),
            readMarks = mapOf(ConversationId("conv-someone-else") to ChatReadMark(Long.MAX_VALUE, 0)),
        )

        assertEquals(2, state.unreadOf(chat))
    }

    @Test
    fun `a chat addressed to the user themselves is not in the list`() {
        // The live row, read off chat-node on 2 Oct 2026: signed in as mcx8102, counterparty
        // guest-8102@guest.local. It looks like a colleague and its call button rings this
        // very handset.
        val phantom = conversation("8102")
        val real = conversation("mcx8101")
        val state = ChatsUiState(
            isSignedIn = true,
            conversations = listOf(phantom, real),
            myUsername = "mcx8102",
            myExtension = "8102",
        )

        assertEquals(listOf(real.id), state.visibleConversations.map { it.id })
        assertEquals(listOf(real.id), state.otherConversations.map { it.id })
        assertTrue(state.isSelf(phantom))
        assertFalse(state.isSelf(real))
    }

    @Test
    fun `a phantom keyed by the designation is hidden too`() {
        val phantom = conversation("mcx8102")
        val state = ChatsUiState(
            isSignedIn = true,
            conversations = listOf(phantom),
            myUsername = "mcx8102",
            myExtension = "8102",
        )

        assertEquals(emptyList(), state.visibleConversations)
        // A list made entirely of phantoms is empty, not "you have chats you cannot see".
        assertTrue(state.isEmpty)
    }

    @Test
    fun `search cannot surface a hidden self chat`() {
        val state = ChatsUiState(
            isSignedIn = true,
            conversations = listOf(conversation("8102")),
            myUsername = "mcx8102",
            myExtension = "8102",
            query = "8102",
        )

        // Searching is the obvious way back to a row that was filtered out of the list, so
        // it filters from the same place rather than from `conversations`.
        assertEquals(emptyList(), state.otherConversations)
        assertTrue(state.hasNoMatches)
    }

    @Test
    fun `nothing is hidden before the session is known`() {
        val state = ChatsUiState(isSignedIn = true, conversations = listOf(conversation("8102")))

        // The session is read asynchronously. Hiding on a null identity would blank the
        // whole list for as long as that takes.
        assertEquals(1, state.visibleConversations.size)
    }

    @Test
    fun `the badge counts what arrived SINCE the chat was read, not the server's total`() {
        // chat-node's count is a lifetime total: it never goes down, because nothing can tell
        // it a message was seen. Read at 6, now 7 — one new message, which is what the user
        // sent and what the badge must say. Reported from a device showing 7 for one message.
        val chat = conversation("mcx8102", unread = 7, lastAtMs = 900)
        val state = ChatsUiState(
            conversations = listOf(chat),
            readMarks = mapOf(chat.id to ChatReadMark(readUpToMs = 500, serverUnreadAtRead = 6)),
        )

        assertEquals(1, state.unreadOf(chat))
    }

    @Test
    fun `a chat never opened on this device shows the whole total`() {
        val chat = conversation("mcx8102", unread = 7, lastAtMs = 900)

        // No mark: there is no baseline to difference against, and every one of them is
        // genuinely unread HERE. The total is the honest answer until it is opened once.
        assertEquals(7, ChatsUiState(conversations = listOf(chat)).unreadOf(chat))
    }

    @Test
    fun `a badge never contradicts the timestamp by reading zero`() {
        // The timestamp says something arrived after the mark, but the server's total has not
        // moved — it can lag, or the baseline can have been written from a later snapshot.
        // Saying "nothing new" while showing a newer message would be the screen arguing with
        // itself, so the floor is one.
        val chat = conversation("mcx8102", unread = 6, lastAtMs = 900)
        val state = ChatsUiState(
            conversations = listOf(chat),
            readMarks = mapOf(chat.id to ChatReadMark(readUpToMs = 500, serverUnreadAtRead = 6)),
        )

        assertEquals(1, state.unreadOf(chat))
    }

    @Test
    fun `a chat with a badge is offered Mark as read`() {
        val chat = conversation("mcx8102", unread = 3, lastAtMs = 500)

        assertEquals(
            ConversationReadAction.MarkRead,
            ChatsUiState(conversations = listOf(chat)).readActionFor(chat),
        )
    }

    @Test
    fun `a chat this device has read is offered Mark as unread`() {
        val chat = conversation("mcx8102", unread = 3, lastAtMs = 500)
        val state = ChatsUiState(conversations = listOf(chat), readMarks = mapOf(chat.id to ChatReadMark(500L, 0)))

        // The server still counts three, because it never learned they were read. That stale
        // count is exactly what makes the badge restorable.
        assertEquals(ConversationReadAction.MarkUnread, state.readActionFor(chat))
    }

    @Test
    fun `a chat nobody counts as unread is offered neither`() {
        val chat = conversation("mcx8102", unread = 0, lastAtMs = 500)

        // No badge to clear, and no number to put back — an action here would do nothing
        // when tapped, which is worse than an action that is not there.
        assertEquals(null, ChatsUiState(conversations = listOf(chat)).readActionFor(chat))
    }

    @Test
    fun `search still works before the directory arrives`() {
        val state = state()

        // Matched against the handle, which is all there is to match against yet. On this
        // deployment the designation happens to carry the digits of the extension, so
        // typing either still finds the row — but that is the directory's naming being
        // kind, not the join working. A deployment whose designations were names would
        // find nothing here, and the fallback would still be the best answer available.
        assertEquals(1, state.copy(query = "mcx8102").otherConversations.size)
        assertEquals(1, state.copy(query = "8102").otherConversations.size)
        assertEquals(0, state.copy(query = "Rahul").otherConversations.size)
    }
}
