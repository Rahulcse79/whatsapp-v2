package com.whatsappv2.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.repository.ChatContactRepository
import com.whatsappv2.domain.repository.ChatGroupRepository
import com.whatsappv2.domain.repository.ChatPinRepository
import com.whatsappv2.domain.repository.ChatReadRepository
import com.whatsappv2.domain.repository.ChatRepository
import com.whatsappv2.domain.repository.ChatSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The Chats tab: who is signed in, whether the socket is up, and the conversation list.
 *
 * ## It never touches the SDK
 *
 * No `addListener`, no `ChatSdk`. A ViewModel that registered a listener directly would be
 * retained for the life of the process (finding 1.3-7); architecture rule 13 makes that
 * unbuildable by refusing the import, and this class reads two ports instead.
 *
 * ## Sign-out is a repository call with no use case in front of it
 *
 * §4.2 forbids pass-through use cases and this is exactly one — no second collaborator,
 * no rule the repository cannot see. Disconnecting the socket happens on the far side of
 * the port, triggered by the session the engine already watches.
 */
@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val sessions: ChatSessionRepository,
    private val chat: ChatRepository,
    private val pins: ChatPinRepository,
    private val reads: ChatReadRepository,
    private val contacts: ChatContactRepository,
    private val groupRepository: ChatGroupRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatsUiState())
    val state: StateFlow<ChatsUiState> = _state.asStateFlow()

    private val _events = Channel<ChatsEvent>(Channel.BUFFERED)
    val events: Flow<ChatsEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(
                // The whole session rather than a boolean: the header shows which extension
                // this is, and `ChatSession` is the only thing that knows.
                sessions.observeSession(),
                chat.observeConnection(),
                chat.observeConversations(),
                // Paired rather than given a slot each: `combine` is typed only to five
                // arguments, and the vararg overload would cost every one of these its type.
                // Both are this device's own lists, so they belong together anyway.
                combine(pins.observePinned(), reads.observeReadMarks(), ::Pair),
                // Only to put an extension beside the handle: the chat server knows this
                // person as `mcx8102` and the directory is the only thing that knows they
                // are on 8102.
                // Paired for the same reason the two local lists are: five typed slots, and
                // both of these describe who a row is.
                combine(contacts.observeContacts(), groupRepository.observeGroups(), ::Pair),
            ) { session, connection, conversations, local, directoryAndGroups ->
                val (pinned, readMarks) = local
                val (directory, groups) = directoryAndGroups
                // Only the four server-and-storage facts. The query is typed into this
                // ViewModel and must survive a conversation arriving mid-search, so it is
                // never part of what this flow rebuilds.
                { state: ChatsUiState ->
                    state.copy(
                        isSignedIn = session != null,
                        myExtensionLabel = session?.extensionLabel,
                        // Both names, because the phantom self-chats use both: this build
                        // addresses by designation, the one before it used the extension.
                        myUsername = session?.userId,
                        myExtension = session?.extension?.number,
                        connection = connection,
                        conversations = conversations,
                        pinned = pinned,
                        readMarks = readMarks,
                        directory = directory.associateBy { it.id },
                        groups = groups,
                    )
                }
            }.collect { apply -> _state.update(apply) }
        }

        // Refresh when the socket comes up, not on a timer and not on every recomposition.
        // collectLatest so a connection that flaps cancels the in-flight refresh rather
        // than queueing one per transition.
        viewModelScope.launch {
            chat.observeConnection()
                .map { it == ChatConnectionState.Connected }
                .distinctUntilChanged()
                .collectLatest { connected -> if (connected) load() }
        }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    fun signOut() {
        viewModelScope.launch { sessions.signOut() }
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    /** Clears the search. Separate from [setQuery] so the screen's X has one obvious call. */
    fun clearQuery() = _state.update { it.copy(query = "") }

    /**
     * Pins or unpins [id], and reports a full list rather than ignoring the tap.
     *
     * The event is a one-shot rather than state: "you can pin five" is a reply to a
     * gesture, and a flag in state would re-announce it on the next recomposition.
     */
    fun togglePin(id: ConversationId) {
        viewModelScope.launch {
            if (_state.value.isPinned(id)) {
                pins.unpin(id)
            } else if (!pins.pin(id)) {
                _events.send(ChatsEvent.PinLimitReached(ChatPinRepository.MAX_PINNED))
            }
        }
    }

    /**
     * Clears a chat's badge without opening it.
     *
     * Marked up to the conversation's own newest message, which is the only timestamp this
     * device can be sure the server agrees with — using "now" would mark a message that
     * arrives a second later as already read.
     */
    fun markRead(id: ConversationId) {
        val conversation = _state.value.conversations.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            reads.markRead(id, conversation.lastMessageAtMs, conversation.unreadCount)
        }
    }

    /** Puts a chat's badge back, by forgetting this device's mark — see [ChatReadRepository]. */
    fun markUnread(id: ConversationId) {
        viewModelScope.launch { reads.clearRead(id) }
    }

    private suspend fun load() {
        _state.update { it.copy(isLoading = true, error = null) }

        when (val outcome = chat.refreshConversations()) {
            is Outcome.Success -> _state.update { it.copy(isLoading = false) }
            is Outcome.Failure -> _state.update { it.copy(isLoading = false, error = outcome.error) }
        }
    }
}
