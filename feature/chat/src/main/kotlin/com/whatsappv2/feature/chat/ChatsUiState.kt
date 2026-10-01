package com.whatsappv2.feature.chat

import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.chat.ChatConversation
import com.whatsappv2.domain.chat.ChatFailure

/**
 * What the Chats tab is showing.
 *
 * [isSignedIn] and [connection] are two different questions and the tab needs both: a
 * signed-in user whose socket is down still sees their conversation list, with a banner
 * over it — whereas a signed-out one has no list to show at all.
 */
data class ChatsUiState(
    val isSignedIn: Boolean = false,
    val connection: ChatConnectionState = ChatConnectionState.NotConfigured,
    val conversations: List<ChatConversation> = emptyList(),
    val isLoading: Boolean = false,
    val error: ChatFailure? = null,
) {
    /** Signed in, nothing loading, nothing wrong, and nobody to talk to yet. */
    val isEmpty: Boolean get() = isSignedIn && conversations.isEmpty() && !isLoading && error == null
}
