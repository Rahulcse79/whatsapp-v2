package com.whatsappv2.domain.repository

import com.whatsappv2.domain.chat.ChatReadMark
import com.whatsappv2.domain.chat.ConversationId
import kotlinx.coroutines.flow.Flow

/**
 * How far through each conversation this device has read.
 *
 * ## Why reading is tracked here instead of being told to the server
 *
 * Because the server will not listen. `ChatSdk` exposes no read API at all, and the obvious
 * frame is refused outright — `message.read` comes back from chat-node as *"Unrecognized or
 * not-yet-implemented frame type"*, probed on 1 Oct 2026. The conversation summary carries
 * an `unreadCount` that this client can therefore only ever watch go **up**.
 *
 * Without something on this side, that is exactly what it did: the badge on a chat you had
 * just read and left stayed on it for ever, and the only thing that cleared it was the other
 * person's own client marking it read, which has nothing to do with you.
 *
 * This is the same bargain `ChatPinRepository` makes for the same reason, and it carries the
 * same cost, stated rather than discovered: **read state does not follow you to another
 * phone.** Sign in on a second device and every chat looks unread again, because the only
 * record that you read them is on the first one.
 *
 * ## A watermark, not a set of message ids
 *
 * The server sends a count, never which messages are in it, so there is nothing to subtract
 * from. What this stores is a timestamp per conversation: *everything up to here has been
 * seen.* A conversation whose newest message is older than its mark shows no badge; one that
 * has moved past it shows the server's count, which is then the best estimate available.
 *
 * It is deliberately not exact. After reading a chat and leaving, two new messages arriving
 * shows "2" only if the server agrees; if the server says 5 because three were already
 * unread on another device, the row says 5. Being occasionally generous is the right failure
 * for a badge — a count that is too low teaches people to stop trusting it.
 */
interface ChatReadRepository {

    /**
     * The newest message timestamp each conversation has been read up to.
     *
     * A conversation absent from the map has never been opened on this device, which is not
     * the same as "no unread": it means there is nothing to suppress and the server's count
     * stands as given.
     */
    fun observeReadMarks(): Flow<Map<ConversationId, ChatReadMark>>

    /**
     * Records that [id] has been read as far as [uptoMs], with [serverUnreadCount] as the
     * server's running total then — see [ChatReadMark] for why both are needed.
     *
     * Never moves a mark backwards. Scrolling up through history must not un-read the
     * newest message, and a late-arriving older page must not either.
     */
    suspend fun markRead(id: ConversationId, uptoMs: Long, serverUnreadCount: Int)

    /**
     * Forgets [id]'s mark, so the server's count stands again.
     *
     * This is "mark as unread", and it works only because the server's count is stale-high
     * by design: it never learned the conversation was read, so dropping the local mark
     * restores the number it still believes. A conversation the server genuinely counts as
     * zero cannot be made to look unread — there is no number to restore and nowhere to put
     * one, which is why the UI does not offer the action there.
     */
    suspend fun clearRead(id: ConversationId)
}
