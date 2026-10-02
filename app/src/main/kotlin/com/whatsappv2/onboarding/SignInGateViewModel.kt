package com.whatsappv2.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.domain.repository.ChatSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Supplies [SignInGate] with the one fact it gates on.
 *
 * A ViewModel rather than a `collectAsState` on an injected repository, so the subscription
 * survives a configuration change and the disk read is not repeated on every rotation.
 *
 * [SharingStarted.Eagerly] rather than `WhileSubscribed`: this flow decides whether the
 * **whole app** is on screen, and one that dropped its value while the activity was stopped
 * would fall back to [SignInGateState.Unknown] — a blank screen on the way back from the
 * recents switcher.
 */
@HiltViewModel
internal class SignInGateViewModel @Inject constructor(
    sessions: ChatSessionRepository,
) : ViewModel() {

    val state: StateFlow<SignInGateState> = sessions.observeSession()
        .map { session ->
            if (session == null) SignInGateState.SignedOut else SignInGateState.SignedIn
        }
        // `observeSession` emits nothing until it has read the disk, so this initial value
        // is what "not known yet" actually means rather than a guess standing in for it.
        .stateIn(viewModelScope, SharingStarted.Eagerly, SignInGateState.Unknown)
}
