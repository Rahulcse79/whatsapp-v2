package com.whatsappv2.domain.repository

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.chat.ChatConversation
import com.whatsappv2.domain.chat.ChatFailure
import com.whatsappv2.domain.chat.ChatIdentity
import com.whatsappv2.domain.chat.ChatMessage
import com.whatsappv2.domain.chat.ChatMessageId
import com.whatsappv2.domain.chat.ConversationId
import kotlinx.coroutines.flow.Flow

/**
 * Conversations and messages, over chat-node at `{origin}/chat/`.
 *
 * ## Separate from [ChatSessionRepository], because they are separate servers
 *
 * That one is the Coral UC platform at `/services/` — who you are, and the directory.
 * This one is chat-node — what you say. One host, two services, two auth models, two
 * error types. Folding them into one port would mean one interface whose halves fail in
 * unrelated ways.
 *
 * ## What the callers are protected from
 *
 * Three SDK behaviours that would otherwise be every caller's problem:
 *
 *  - [syncMessages] **loops**. The SDK advances its cursor by one 200-message page per
 *    call, oldest first, so a thousand-message conversation needs five calls and the first
 *    returns the *oldest* two hundred. A caller must not have to know that.
 *  - [sendText] reports a **failure**. The SDK drops a send with no socket and leaves it
 *    `pending` for ever; there is no outbox and no failure state below this line.
 *  - [observeConnection] has a [ChatConnectionState.NotConfigured] state, because
 *    `ChatSdk.get()` throws before `init` and "not signed in" is where every install starts.
 */
interface ChatRepository {

    fun observeConnection(): Flow<ChatConnectionState>

    /** The conversation list, newest activity first. Survives rotation; empty until refreshed. */
    fun observeConversations(): Flow<List<ChatConversation>>

    /** One thread, oldest first. Emits again on every push, ack and send. */
    fun observeMessages(conversationId: ConversationId): Flow<List<ChatMessage>>

    /**
     * Who this device is, resolved once the socket is up.
     *
     * Null before that. A composer enabled while it is null produces messages with no
     * sender, which render on the wrong side of their own thread (finding 1.3-5).
     */
    fun observeIdentity(): Flow<ChatIdentity?>

    suspend fun refreshConversations(): Outcome<Unit, ChatFailure>

    /**
     * Fetches until the server has nothing newer — **not one page**.
     *
     * The SDK returns its whole snapshot after advancing one page, so this loops while the
     * snapshot grows and stops when it does not. That is the termination condition, and it
     * is why a caller can treat one call as "synced".
     */
    suspend fun syncMessages(conversationId: ConversationId): Outcome<Unit, ChatFailure>

    /** Opens, creating if necessary, the direct conversation with [otherUserId]. */
    suspend fun openDirectConversation(otherUserId: String): Outcome<ConversationId, ChatFailure>

    /**
     * Sends a message, or says why it could not go.
     *
     * Returns the server id only once acknowledged; a message still in flight reports
     * success with a null id and shows as [ChatMessage.Delivery.Pending].
     */
    suspend fun sendText(conversationId: ConversationId, text: String): Outcome<ChatMessageId?, ChatFailure>

    /** Re-offers a message that failed, under its original client id. */
    suspend fun retry(conversationId: ConversationId, clientId: String): Outcome<Unit, ChatFailure>

    /** Opens the socket, or repairs a stale one. Idempotent. */
    fun connect()
}
