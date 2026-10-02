package com.whatsappv2.domain.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the call button in a thread is allowed to dial.
 *
 * A conversation is addressed by designation — `mcx8101` — and the PBX answers to `8101`.
 * The two are different strings on this deployment, and this property used to return the
 * first one, which produced a call button that dialled a chat username. Reported from a
 * device on 2 Oct 2026 as "call this user's extension, not mcx8101".
 *
 * The real answer is the directory join, which the thread does. This is the fallback, and
 * its job is to say **nothing** rather than something unroutable.
 */
class ChatConversationCallableTest {

    private fun conversation(identifier: String?) = ChatConversation(
        id = ConversationId("c1"),
        type = "DIRECT",
        createdAtMs = 0,
        muted = false,
        archived = false,
        pinned = false,
        otherUserId = null,
        otherUserContactIdentifier = identifier,
        lastMessageBody = null,
        lastMessageType = null,
        lastMessageSenderId = null,
        lastMessageAtMs = 0,
        unreadCount = 0,
    )

    @Test
    fun `a designation is not dialable, so there is no call button`() {
        // The bug. `mcx8101` reaches the switch and goes nowhere.
        assertNull(conversation("guest-mcx8101@guest.local").callableExtension)
        // ...while the title still shows it, because that IS who you are talking to.
        assertEquals("mcx8101", conversation("guest-mcx8101@guest.local").title)
    }

    @Test
    fun `a bare extension still dials, which is the fallback worth keeping`() {
        assertEquals("8101", conversation("guest-8101@guest.local").callableExtension)
    }

    @Test
    fun `a dial string's own characters are allowed through`() {
        assertEquals("*123", conversation("*123").callableExtension)
        assertEquals("+919876543210", conversation("+919876543210").callableExtension)
    }

    @Test
    fun `a name is never mistaken for a number`() {
        assertNull(conversation("Rahul Singh").callableExtension)
        assertNull(conversation("8101a").callableExtension)
        assertNull(conversation("a8101").callableExtension)
    }

    @Test
    fun `a group has a title and nobody to call`() {
        assertNull(conversation(null).callableExtension)
    }
}
