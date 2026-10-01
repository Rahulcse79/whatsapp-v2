package com.whatsappv2.feature.chat.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.chat.ChatContact
import com.whatsappv2.domain.chat.ConversationId
import com.whatsappv2.domain.repository.ChatContactRepository
import com.whatsappv2.domain.usecase.OpenConversationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The company directory.
 *
 * ## The query goes to the server, and it is debounced
 *
 * Filtering here would narrow a page the server had already truncated and present the
 * result as the whole directory — which is why [ChatContactRepository.refresh] takes the
 * query. Debounced, because the alternative is one HTTP request per keystroke.
 *
 * ## A failed refresh keeps the rows
 *
 * [ChatContactsUiState] carries contacts and an error at once so that a directory already
 * on screen stays there when a refresh fails. Blanking a useful list to show an error is
 * worse than showing both.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ChatContactsViewModel @Inject constructor(
    private val contacts: ChatContactRepository,
    private val openConversation: OpenConversationUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatContactsUiState())
    val state: StateFlow<ChatContactsUiState> = _state.asStateFlow()

    /**
     * Where to navigate once a conversation exists.
     *
     * A channel, not state: navigating is a one-shot event, and a conversation id left in
     * state would re-fire the navigation on every recomposition after a rotation.
     */
    private val _opened = Channel<ConversationId>(Channel.BUFFERED)
    val opened: Flow<ConversationId> = _opened.receiveAsFlow()

    init {
        viewModelScope.launch {
            contacts.observeContacts().collect { rows -> _state.update { it.copy(contacts = rows) } }
        }
        viewModelScope.launch {
            _state
                .map { it.query }
                .distinctUntilChanged()
                // The first emission is the empty query the screen starts with, and
                // refresh() below already covers it. Without the drop, opening the screen
                // fires two identical requests.
                .drop(1)
                .debounce(SEARCH_DEBOUNCE_MILLIS)
                .collect { query -> load(query) }
        }
        refresh()
    }

    fun setQuery(value: String) = _state.update { it.copy(query = value) }

    fun refresh() {
        viewModelScope.launch { load(_state.value.query) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private suspend fun load(query: String) {
        _state.update { it.copy(isLoading = true, error = null) }

        when (val outcome = contacts.refresh(query.takeIf { it.isNotBlank() })) {
            is Outcome.Success -> _state.update { it.copy(isLoading = false) }
            is Outcome.Failure -> _state.update { it.copy(isLoading = false, error = outcome.error) }
        }
    }

    /**
     * Opens the conversation with [contact], then announces where to go.
     *
     * Opening before navigating, not after: the thread's id belongs to the chat server and
     * does not exist until it says so — for a first conversation it is created by this
     * call. Navigating first would land on a screen with no id to load.
     *
     * Which field identifies the other party is decided here and nowhere else.
     * [ChatContact.id] is the extension, because chat-node's guest mode keys an identity
     * by the PPDR username; the directory's numeric `id` is a UC database row and means
     * nothing to it.
     */
    fun openConversationWith(contact: ChatContact) {
        viewModelScope.launch {
            _state.update { it.copy(isOpening = true, error = null) }

            when (val outcome = openConversation(contact.id)) {
                is Outcome.Success -> {
                    _state.update { it.copy(isOpening = false) }
                    _opened.send(outcome.value)
                }
                is Outcome.Failure -> _state.update {
                    // Reported on this screen rather than by pushing a thread that cannot
                    // load: the user is still here, and here is where the retry is.
                    it.copy(isOpening = false, openFailure = outcome.error)
                }
            }
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 300L
    }
}
