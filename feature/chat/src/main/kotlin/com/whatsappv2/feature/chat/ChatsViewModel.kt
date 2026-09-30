package com.whatsappv2.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.domain.repository.ChatSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Whether the Chats tab is signed in, and the one action that changes it.
 *
 * Deliberately thin. The tab's job for now is to know which of two states it is in; the
 * conversation list that will fill it is phase 3, and putting its state here early would
 * mean designing it against a transport that does not exist yet.
 *
 * ## Sign-out is a repository call, with no use case in front of it
 *
 * §4.2 forbids pass-through use cases and this is exactly one: there is no second
 * collaborator to order and no rule the repository cannot see. Disconnecting the chat
 * socket belongs to `:data:chat` and happens on the far side of the port, triggered by the
 * session it already watches. [ChatSessionRepository]'s KDoc records the same reasoning.
 */
@HiltViewModel
class ChatsViewModel @Inject constructor(
    private val sessions: ChatSessionRepository,
) : ViewModel() {

    /**
     * Starts false and becomes true when the store answers.
     *
     * The wrong way round on purpose: a tab that starts "signed in" and corrects itself
     * flashes a conversation list at somebody who has no account. Starting signed out and
     * correcting upward shows a prompt for a moment instead, which is the honest wait.
     */
    val isSignedIn: StateFlow<Boolean> = sessions.observeSession()
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS), false)

    fun signOut() {
        viewModelScope.launch { sessions.signOut() }
    }

    private companion object {
        /** Long enough to survive a rotation, short enough not to hold the store open. */
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
    }
}
