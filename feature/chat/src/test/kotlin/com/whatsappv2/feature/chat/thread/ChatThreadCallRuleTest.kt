package com.whatsappv2.feature.chat.thread

import com.whatsappv2.domain.chat.ChatGroup
import com.whatsappv2.domain.chat.ChatGroupMember
import com.whatsappv2.domain.chat.ChatMessage
import com.whatsappv2.domain.chat.ChatMessageType
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.chat.GroupRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the thread's top bar offers to call.
 *
 * A direct chat and a group answer this with different facts — an extension against a roster
 * size — and the bar asks one question rather than branching on the kind of conversation.
 */
class ChatThreadCallRuleTest {

    private fun group(members: Int) = ChatGroup(
        id = ConversationId("g1"),
        name = "Test group",
        createdBy = "owner",
        members = List(members) { ChatGroupMember("u$it", if (it == 0) GroupRole.OWNER else GroupRole.MEMBER) },
    )

    @Test
    fun `a direct chat offers a call once the directory gave it an extension`() {
        assertTrue(ChatThreadUiState(callableExtension = "8101").canOfferCall)
    }

    @Test
    fun `a direct chat with no dialable extension offers nothing`() {
        // The handle is a designation the PBX cannot route, so there is no button rather
        // than a button that fails.
        assertFalse(ChatThreadUiState(callableExtension = null).canOfferCall)
    }

    @Test
    fun `a group of four offers a call`() {
        assertTrue(ChatThreadUiState(isDirect = false, group = group(members = 4)).canOfferCall)
    }

    @Test
    fun `a group of five does not, which is the rule asked for`() {
        // Above the app's four-party ceiling the icons are absent rather than disabled.
        assertFalse(ChatThreadUiState(isDirect = false, group = group(members = 5)).canOfferCall)
    }

    @Test
    fun `a group offers a call on its roster, never on an extension it happens to carry`() {
        // chat-node fills `otherUserContactIdentifier` on a two-person group, so a group CAN
        // arrive carrying something extension-shaped. Size is what decides, not that.
        val tooBig = ChatThreadUiState(
            isDirect = false,
            group = group(members = 6),
            callableExtension = "8101",
        )

        assertFalse(tooBig.canOfferCall, "a six-person group was offered a call because of a stray extension")
    }

    @Test
    fun `a group whose roster has not loaded offers nothing yet`() {
        assertFalse(ChatThreadUiState(isDirect = false, group = group(members = 0)).canOfferCall)
        assertFalse(ChatThreadUiState(isDirect = false, group = group(members = 1)).canOfferCall)
    }

    @Test
    fun `placing a call disables the buttons without removing them`() {
        val placing = ChatThreadUiState(callableExtension = "8101", isPlacingCall = true)

        assertTrue(placing.canOfferCall, "the buttons should still be drawn while a call is being placed")
        assertFalse(placing.canCall, "a second tap would be a second INVITE")
    }

    @Test
    fun `a group sender is named, not printed as a ULID`() {
        // The defect this closes: a message carries only `senderId`, which is a 26-character
        // ULID. A group draws a sender on every incoming bubble, so without the join every
        // one of them read `01M3TB3R`.
        val state = ChatThreadUiState(
            isDirect = false,
            group = group(members = 3),
            senderNames = mapOf("u1" to "8102 (mcx8102)"),
        )

        assertEquals("8102 (mcx8102)", state.senderLabelOf(message(senderId = "u1")))
    }

    @Test
    fun `an unresolvable sender falls back to a stable short id, never to blank`() {
        // A member of a group somebody else made is not in this device's conversation list,
        // so there is nothing to resolve them against. Eight characters is a poor label but a
        // stable one, and it still tells two unknown people apart.
        val state = ChatThreadUiState(isDirect = false, group = group(members = 3))

        assertEquals("01M3TB3R", state.senderLabelOf(message(senderId = "01M3TB3RME0123456789ABCDEF")))
        assertEquals("", state.senderLabelOf(message(senderId = null)))
    }

    private fun message(senderId: String?) = ChatMessage(
        id = null,
        // Null, which is what an incoming message carries: a clientId exists only for a
        // message this device sent.
        clientId = null,
        conversationId = ConversationId("g1"),
        senderId = senderId,
        type = ChatMessageType.TEXT,
        body = "hello",
        sequenceNumber = 1,
        createdAtMs = 0,
        delivery = ChatMessage.Delivery.Sent,
    )
}
