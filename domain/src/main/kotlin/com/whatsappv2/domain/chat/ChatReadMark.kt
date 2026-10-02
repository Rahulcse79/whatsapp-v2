package com.whatsappv2.domain.chat

/**
 * How far this device has read one conversation, and what the server was counting at the time.
 *
 * ## Why the server's count has to be remembered, not just the time
 *
 * Because chat-node's `unreadCount` is **not** an unread count. It never goes down: there is
 * no `message.read` frame to tell the server anything was seen (it answers *"Unrecognized or
 * not-yet-implemented frame type"*), so the number it reports is every incoming message the
 * conversation has ever carried. Reported from a device on 2 Oct 2026: one new message, and
 * the badge said seven.
 *
 * Remembering what it said when the chat was last read turns a useless running total into the
 * thing a badge is for. The count is monotonic, so the **difference** between then and now is
 * exactly how many have arrived since — which is the number a person expects to see.
 *
 * ## Both fields, because they answer different questions
 *
 * [readUpToMs] answers *is there anything new at all*, and it is the honest one: it comes
 * from the messages themselves. [serverUnreadAtRead] answers *how many*, and only as a
 * difference. A conversation never opened on this device has no mark, and then the server's
 * running total stands as given — every message in it is genuinely unread **here**, which is
 * the best this client can say until the chat is opened once.
 */
data class ChatReadMark(
    /** The newest message timestamp seen on this device. */
    val readUpToMs: Long,

    /** What the server's running total was at that moment, so later counts can be differenced. */
    val serverUnreadAtRead: Int,
)
