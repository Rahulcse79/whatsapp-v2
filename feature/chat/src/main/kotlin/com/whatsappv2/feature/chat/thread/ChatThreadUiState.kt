package com.whatsappv2.feature.chat.thread

import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.chat.ChatFailure
import com.whatsappv2.domain.chat.ChatIdentity
import com.whatsappv2.domain.chat.ChatMessage

/**
 * What one conversation is showing.
 *
 * ## Why [identity] is here rather than folded into a boolean
 *
 * Because the thread needs it for two different things: deciding which side each bubble
 * sits on, and deciding whether the composer may be used at all. A message sent before
 * `me()` resolves carries a null sender and renders on the **wrong side** of its own
 * thread (finding 1.3-5), so the composer waits for this rather than for the socket.
 */
data class ChatThreadUiState(
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val identity: ChatIdentity? = null,
    val connection: ChatConnectionState = ChatConnectionState.NotConfigured,
    val isLoading: Boolean = false,
    val error: ChatFailure? = null,
) {

    /**
     * Whether the composer accepts input.
     *
     * Three conditions, and the identity one is the subtle one: a socket can be up before
     * the SDK has resolved who we are, and a message sent in that window is drawn as
     * somebody else's. Waiting costs a moment; not waiting costs a thread that looks wrong
     * until it is reloaded.
     */
    val canSend: Boolean
        get() = identity != null && connection.isUsable && draft.isNotBlank()

    /** Why the composer is disabled, when it is, so the user is not left guessing. */
    val composerHint: String?
        get() = when {
            identity == null && connection.isUsable -> "Signing in…"
            !connection.isUsable -> "Waiting for the connection…"
            else -> null
        }

    val isEmpty: Boolean get() = messages.isEmpty() && !isLoading && error == null
}
