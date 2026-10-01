package com.whatsappv2.feature.chat.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.repository.ChatRepository
import com.whatsappv2.domain.usecase.SendChatMessageUseCase
import com.whatsappv2.domain.usecase.SyncConversationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One conversation.
 *
 * ## The conversation id comes from [SavedStateHandle], and that is allowed
 *
 * Architecture rule 10 forbids restoring **call state** from saved state — a call that
 * ended while the process was dead must not be rebuilt from a stale handle. A conversation
 * id is not call state: it is a navigation argument naming a durable server-side thing,
 * and re-reading it after process death is exactly right. The messages themselves are
 * re-fetched, never restored.
 *
 * ## The draft is here, not in the composable
 *
 * So it survives rotation and a trip to the contact picker. It is deliberately **not** in
 * `SavedStateHandle`: an unsent message is not worth writing to disk, and a half-typed
 * line reappearing days later after a process death is a surprise rather than a feature.
 */
@HiltViewModel
class ChatThreadViewModel @Inject constructor(
    private val chat: ChatRepository,
    private val sendMessage: SendChatMessageUseCase,
    private val sync: SyncConversationUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val conversationId = ConversationId(
        checkNotNull(savedStateHandle.get<String>(CONVERSATION_ID)) {
            "the thread route was opened with no conversation id"
        },
    )

    private val _state = MutableStateFlow(ChatThreadUiState(title = conversationId.value))
    val state: StateFlow<ChatThreadUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                chat.observeMessages(conversationId),
                chat.observeIdentity(),
                chat.observeConnection(),
                chat.observeConversations(),
            ) { messages, identity, connection, conversations ->
                val title = conversations.firstOrNull { it.id == conversationId }?.title
                _state.value.copy(
                    messages = messages,
                    identity = identity,
                    connection = connection,
                    title = title ?: _state.value.title,
                )
            }.collect { next -> _state.update { next.copy(draft = it.draft) } }
        }

        refresh()
    }

    fun setDraft(value: String) = _state.update { it.copy(draft = value) }

    /** Catches the thread up. Loops inside the use case; one call means synced. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            when (val outcome = sync(conversationId)) {
                is Outcome.Success -> _state.update { it.copy(isLoading = false) }
                is Outcome.Failure -> _state.update { it.copy(isLoading = false, error = outcome.error) }
            }
        }
    }

    fun send() {
        val text = _state.value.draft
        if (!_state.value.canSend) return

        // Cleared optimistically. The message is already drawn from the repository's own
        // stream - as Pending, or as Failed if there was no socket - so leaving the text
        // in the box as well would show it twice.
        _state.update { it.copy(draft = "") }

        viewModelScope.launch {
            when (val outcome = sendMessage(conversationId, text)) {
                is Outcome.Success -> Unit
                // Not an error banner: the bubble itself is already Failed and carries the
                // retry. A second report of the same fact is noise.
                is Outcome.Failure -> Unit.also { _ -> outcome.error }
            }
        }
    }

    /** Re-offers a message that could not be sent. */
    fun retry(clientId: String) {
        viewModelScope.launch { chat.retry(conversationId, clientId) }
    }

    companion object {
        /** The navigation argument's name. `:app`'s route must use the same string. */
        const val CONVERSATION_ID = "conversationId"
    }
}
