package com.whatsappv2.data.chat

import com.whatsappv2.domain.chat.ChatConversation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The conversation list this process is holding, and the one place it is emptied.
 *
 * ## Why it is not just a field on the repository
 *
 * Because of who has to clear it. The list belongs to **an identity**, not to the app:
 * it was fetched with one person's token and it must not survive them signing out. The
 * repository that fetches it has no notion of sign-out, and the repository that handles
 * sign-out has no business owning a conversation list — so the state lives here and both
 * hold the same instance.
 *
 * Found on a device: sign out as one user, sign in as another, and the first user's
 * conversations were still on the screen. Nothing on disk was at fault — there is no
 * message cache yet — and the SDK was innocent too, since `ChatSdk.init` builds a new
 * instance and its own maps start empty. It was this list, a `@Singleton`'s field, living
 * as long as the process.
 *
 * ## Emptied, not invalidated
 *
 * Clearing shows an empty list for the moment between signing in and the first refresh,
 * which is honest — we genuinely do not know this person's conversations yet. Keeping the
 * previous ones until replacements arrive would mean showing somebody else's chats, which
 * is the bug this exists to prevent.
 */
@Singleton
internal class ChatMemoryCache @Inject constructor() {

    private val _conversations = MutableStateFlow<List<ChatConversation>>(emptyList())

    val conversations: StateFlow<List<ChatConversation>> = _conversations.asStateFlow()

    fun putConversations(value: List<ChatConversation>) {
        _conversations.value = value
    }

    /** Forgets everything this identity put here. Called on sign-out and before a sign-in. */
    fun clear() {
        _conversations.value = emptyList()
    }
}
