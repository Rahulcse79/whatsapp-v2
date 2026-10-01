package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.errorOrNull
import com.whatsappv2.core.common.result.getOrNull
import com.whatsappv2.domain.chat.ChatFailure
import com.whatsappv2.domain.chat.ChatMessage
import com.whatsappv2.domain.chat.ChatMessageId
import com.whatsappv2.domain.chat.ChatMessageType
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.testing.FakeChatRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The three chat use cases, and the orderings they exist to enforce.
 */
class ChatConversationUseCasesTest {

    private val repository = FakeChatRepository()
    private val open = OpenConversationUseCase(repository)
    private val send = SendChatMessageUseCase(repository)
    private val sync = SyncConversationUseCase(repository)

    private fun message(sequence: Long) = ChatMessage(
        id = ChatMessageId("m$sequence"),
        clientId = null,
        conversationId = ConversationId("conv-8102"),
        senderId = "8102",
        type = ChatMessageType.TEXT,
        body = "body $sequence",
        sequenceNumber = sequence,
        createdAtMs = sequence,
        delivery = ChatMessage.Delivery.Sent,
    )

    // ------------------------------------------------------------------ opening

    @Test
    fun `opening a conversation also syncs it, so the thread is not blank on arrival`() = runTest {
        val id = ConversationId("conv-8102")
        repository.givenHistory(id, message(1), message(2), message(3), message(4))

        val opened = open("8102")

        assertEquals(id, opened.getOrNull())
        assertTrue(repository.syncCalls > 0, "the thread was opened without being synced")
        assertTrue(repository.observeMessages(id).first().isNotEmpty())
    }

    @Test
    fun `opening also refreshes the list, so the thread has a title rather than an id`() = runTest {
        // Without this a brand-new conversation is absent from the list, and the thread's
        // top bar falls back to the server's ULID.
        open("8102")

        assertTrue(
            repository.observeConversations().first().any { it.id == ConversationId("conv-8102") },
            "the new conversation never reached the list",
        )
    }

    @Test
    fun `a failure to open is reported and nothing else is attempted`() = runTest {
        repository.givenFailing(ChatFailure.Unauthorized)

        assertEquals(ChatFailure.Unauthorized, open("8102").errorOrNull())
        assertEquals(0, repository.syncCalls)
    }

    @Test
    fun `an empty user id is refused before it reaches the server`() = runTest {
        assertIs<Outcome.Failure<ChatFailure>>(open("  "))
        assertTrue(repository.opened.isEmpty())
    }

    // ------------------------------------------------------------------ sending

    @Test
    fun `a blank message is refused rather than handed to the server`() = runTest {
        repository.givenConnected()

        assertIs<Outcome.Failure<ChatFailure>>(send(ConversationId("c1"), "   "))
        assertTrue(repository.sent.isEmpty())
    }

    @Test
    fun `surrounding whitespace is trimmed and inner spacing is left alone`() = runTest {
        repository.givenConnected()

        send(ConversationId("c1"), "  two  words\nand a line  ")

        assertEquals("two  words\nand a line", repository.sent.single().second)
    }

    @Test
    fun `a send with no socket is a failure, not a message that never arrives`() = runTest {
        // The fake reproduces the SDK's own behaviour deliberately: below the port, this
        // is dropped in silence and left "sending" for ever.
        val outcome = send(ConversationId("c1"), "hello")

        assertEquals(ChatFailure.Network, outcome.errorOrNull())
    }

    // ------------------------------------------------------------------ syncing

    @Test
    fun `syncing hands back one page per call, which is why callers go through the port`() = runTest {
        val id = ConversationId("conv-8102")
        repository.givenHistory(id, message(1), message(2), message(3), message(4), message(5))
        repository.pageSize = 2

        sync(id)

        // The fake pages like the SDK, so one call is one page. The REAL repository loops
        // internally; this asserts the fake is honest about what it is standing in for.
        assertEquals(2, repository.observeMessages(id).first().size)
    }
}
