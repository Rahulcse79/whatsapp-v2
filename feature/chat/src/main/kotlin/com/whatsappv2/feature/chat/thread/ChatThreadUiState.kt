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

    /**
     * The other party's extension, or null when there is nobody to dial.
     *
     * Null until the conversation is known, so the call buttons are absent rather than
     * present-and-broken on the moment a brand-new thread opens.
     */
    val callableExtension: String? = null,

    /** A call is being placed. The buttons lock, because a double tap is two INVITEs. */
    val isPlacingCall: Boolean = false,

    /**
     * Whether this thread has exactly one other party.
     *
     * It decides whether an incoming bubble is labelled with who sent it. In a one-to-one
     * conversation the title already says who that is, and the only label the SDK can
     * offer is a shortened user id — there is no display name on a message and no roster
     * to look one up in — so naming the sender there prints a ULID fragment above every
     * run for no information at all.
     *
     * Defaults to true because that is what the app can open, and because a thread whose
     * conversation summary has not arrived yet should not show the id and then drop it.
     */
    val isDirect: Boolean = true,
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

    /** Whether the top bar offers to call. Shown only when there is a real extension behind it. */
    val canCall: Boolean get() = callableExtension != null && !isPlacingCall

    /**
     * The line under the name — what the connection is doing, or nothing.
     *
     * Null when connected, which is the point: a bar that permanently reads "online"
     * spends a line saying what the absence of a warning already says. It is also where
     * a messaging app puts "typing…", which this SDK cannot report (no typing API), so
     * the slot exists and stays honest about what it knows.
     */
    val presenceLabel: String?
        get() = when (connection) {
            ChatConnectionState.Connected -> null
            ChatConnectionState.Connecting -> "connecting…"
            is ChatConnectionState.Disconnected -> "reconnecting…"
            ChatConnectionState.NotConfigured -> "not signed in"
        }
}
