package com.whatsappv2.feature.chat.thread

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.call.userMessage
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.model.CallId
import com.whatsappv2.domain.model.MediaProfile
import com.whatsappv2.domain.repository.ChatRepository
import com.whatsappv2.domain.usecase.PlaceCallError
import com.whatsappv2.domain.usecase.PlaceCallUseCase
import com.whatsappv2.domain.usecase.SendChatMessageUseCase
import com.whatsappv2.domain.usecase.SyncConversationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
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
/** One-shot outcomes of the thread. */
sealed interface ChatThreadEvent {
    /** A call is up. The route opens the call screen; the thread stays where it is. */
    data class CallPlaced(val callId: CallId) : ChatThreadEvent

    /** A call could not be placed, with the reason in the user's words. */
    data class CallFailed(val reason: String) : ChatThreadEvent
}

@HiltViewModel
class ChatThreadViewModel @Inject constructor(
    private val chat: ChatRepository,
    private val sendMessage: SendChatMessageUseCase,
    private val sync: SyncConversationUseCase,
    private val placeCall: PlaceCallUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _events = Channel<ChatThreadEvent>(Channel.BUFFERED)

    /** Navigation and failures, as events: a call id left in state would re-open the call screen. */
    val events: Flow<ChatThreadEvent> = _events.receiveAsFlow()

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
                val conversation = conversations.firstOrNull { it.id == conversationId }
                _state.value.copy(
                    messages = messages,
                    identity = identity,
                    connection = connection,
                    title = conversation?.title ?: _state.value.title,
                    callableExtension = conversation?.callableExtension,
                    isDirect = conversation?.isDirect ?: _state.value.isDirect,
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

    /**
     * Calls the other party, over SIP.
     *
     * ## The chat server is not involved
     *
     * This is the app's own calling, pointed at the extension the conversation names.
     * The chat SDK has no call API and chat-node knows nothing about it; what connects
     * the two is that on this deployment a person's chat handle and their extension are
     * the same string.
     *
     * [PlaceCallUseCase] owns everything else — which account, whether to re-register
     * first, and the downgrade from video to audio when the camera cannot be used. A
     * screen that decided any of that would be a second copy of those rules.
     */
    fun call(media: MediaProfile) {
        val extension = _state.value.callableExtension ?: return
        if (_state.value.isPlacingCall) return

        _state.update { it.copy(isPlacingCall = true) }
        viewModelScope.launch {
            val outcome = placeCall(input = extension, media = media)
            _state.update { it.copy(isPlacingCall = false) }

            when (outcome) {
                is Outcome.Success -> _events.send(ChatThreadEvent.CallPlaced(outcome.value))
                is Outcome.Failure -> _events.send(ChatThreadEvent.CallFailed(outcome.error.wording()))
            }
        }
    }

    /**
     * Why a call could not be placed, in the user's words.
     *
     * Deliberately not shared with the dialler's copy. That screen can offer "add an
     * account" because it is next to the account list; this one is inside a conversation,
     * where the useful thing to say is what went wrong and that the message still sent.
     */
    private fun PlaceCallError.wording(): String = when (this) {
        is PlaceCallError.NoAccountAvailable -> "No calling account is set up yet."
        is PlaceCallError.UnknownAccount -> "That calling account no longer exists."
        is PlaceCallError.InvalidTarget -> "There is no extension to call for this contact."
        // The account was not registered, the app tried, and the server did not answer in
        // time. Worded as what happened rather than as "not registered", which by now is
        // only half the story.
        is PlaceCallError.NotRegistered -> "Could not reach the server for that account."
        is PlaceCallError.Rejected -> cause.userMessage()
    }

    companion object {
        /** The navigation argument's name. `:app`'s route must use the same string. */
        const val CONVERSATION_ID = "conversationId"
    }
}
