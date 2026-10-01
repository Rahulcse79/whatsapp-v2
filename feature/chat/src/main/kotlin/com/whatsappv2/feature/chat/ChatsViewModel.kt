package com.whatsappv2.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.domain.chat.ChatConnectionState
import com.whatsappv2.domain.repository.ChatRepository
import com.whatsappv2.domain.repository.ChatSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
) : ViewModel() {

    private val _state = MutableStateFlow(ChatsUiState())
    val state: StateFlow<ChatsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                sessions.observeSession().map { it != null },
                chat.observeConnection(),
                chat.observeConversations(),
            ) { signedIn, connection, conversations ->
                Triple(signedIn, connection, conversations)
            }.collect { (signedIn, connection, conversations) ->
                _state.update {
                    it.copy(isSignedIn = signedIn, connection = connection, conversations = conversations)
                }
            }
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

    private suspend fun load() {
        _state.update { it.copy(isLoading = true, error = null) }

        when (val outcome = chat.refreshConversations()) {
            is Outcome.Success -> _state.update { it.copy(isLoading = false) }
            is Outcome.Failure -> _state.update { it.copy(isLoading = false, error = outcome.error) }
        }
    }
}
