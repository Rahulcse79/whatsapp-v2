package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.domain.chat.ChatFailure
import com.whatsappv2.domain.chat.ChatMessageId
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.repository.ChatRepository
import javax.inject.Inject

/**
 * Opens the conversation with somebody from the directory, and makes sure it has content.
 *
 * ## Why this is not the pass-through §4.2 forbids
 *
 * Because two things have to happen in one order and the second is easy to forget:
 * `openDirectConversation` creates the thread and returns its id, and the thread is then
 * **empty** until it is synced. A caller that only opened it would land on a blank screen
 * for an existing conversation with a year of history in it, and would blame the thread
 * screen. Ordering the two here is the whole point.
 *
 * A failure in either follow-up is **not** a failure to open: the conversation exists, and
 * a thread with no history or a title that falls back to an id is recoverable by
 * refreshing. So the id comes back either way.
 */
class OpenConversationUseCase @Inject constructor(
    private val repository: ChatRepository,
) {

    suspend operator fun invoke(otherUserId: String): Outcome<ConversationId, ChatFailure> {
        if (otherUserId.isBlank()) return failure(ChatFailure.Unknown("no user to open a conversation with"))

        return when (val opened = repository.openDirectConversation(otherUserId)) {
            is Outcome.Failure -> opened
            is Outcome.Success -> {
                // Neither is propagated. See the class comment: the conversation exists,
                // and both of these only make the destination nicer to arrive at.
                repository.syncMessages(opened.value)
                // Without this a BRAND NEW conversation is not in the list yet, so the
                // thread has no row to take a title from and shows the server's ULID in
                // its top bar. Refreshing resolves it to the other party's name.
                repository.refreshConversations()
                opened
            }
        }
    }
}

/**
 * Sends a message, having refused the ones that are not messages.
 *
 * Blank is rejected here rather than by disabling the button, because the button is one
 * caller: a retry, a share target or a test can all reach the repository, and "the server
 * decides what an empty message means" is not an answer anybody wants to discover.
 *
 * The text is trimmed of surrounding whitespace only. Inner spacing and newlines are the
 * sender's, and a chat client that reflowed what somebody typed would be wrong.
 */
class SendChatMessageUseCase @Inject constructor(
    private val repository: ChatRepository,
) {

    suspend operator fun invoke(
        conversationId: ConversationId,
        text: String,
    ): Outcome<ChatMessageId?, ChatFailure> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return failure(ChatFailure.Unknown("an empty message is not a message"))

        return repository.sendText(conversationId, trimmed)
    }
}

/**
 * Brings one conversation up to date.
 *
 * A one-line body that earns its class for the reason [LoginUseCase] does: it names a rule
 * that would otherwise live in each caller's head. `syncMessages` loops internally — the
 * SDK's cursor advances one page per call — and this is the place the thread screen, the
 * list and [OpenConversationUseCase] all go through, so none of them has to know that.
 */
class SyncConversationUseCase @Inject constructor(
    private val repository: ChatRepository,
) {

    suspend operator fun invoke(conversationId: ConversationId): Outcome<Unit, ChatFailure> =
        repository.syncMessages(conversationId)
}
