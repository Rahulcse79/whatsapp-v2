package com.whatsappv2.feature.chat.signin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.repository.ChatSessionRepository
import com.whatsappv2.domain.usecase.ChatSignInError
import com.whatsappv2.domain.usecase.ChatSignInField
import com.whatsappv2.domain.usecase.ChatSignInUseCase
import com.whatsappv2.domain.usecase.ChatSignInViolation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-shot outcomes of the sign-in screen. */
sealed interface ChatSignInEvent {
    /** Signed in. The route leaves; the screen does not have to draw a success state. */
    data object SignedIn : ChatSignInEvent
}

/**
 * The three-field sign-in form.
 *
 * ## It validates nothing itself
 *
 * Every rule lives in [ChatSignInUseCase] — the URL parse, the required fields, the order
 * they are checked in. This class turns violations into which box goes red. A ViewModel
 * that validated too would be a second copy of the rules, and the copy a user meets is
 * always the one that drifted.
 *
 * ## One request in flight
 *
 * [submit] returns immediately if [ChatSignInUiState.isSubmitting] is already set. Not a
 * nicety: a double tap on a slow network would send two logins, and the second's answer
 * would overwrite the first's — including overwriting a success with a failure.
 *
 * ## The password never leaves this object except as a [Secret]
 *
 * It is held as one in the state and handed to the use case as one. The single `reveal()`
 * is in the text field, where a person has asked to look at it.
 */
@HiltViewModel
class ChatSignInViewModel @Inject constructor(
    private val signIn: ChatSignInUseCase,
    private val sessions: ChatSessionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatSignInUiState())
    val state: StateFlow<ChatSignInUiState> = _state.asStateFlow()

    private val _events = Channel<ChatSignInEvent>(Channel.BUFFERED)
    val events: Flow<ChatSignInEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            // Read once, not observed. The field is the user's to edit from here on, and a
            // store emission arriving mid-typing would overwrite what they were writing.
            val stored = sessions.observeServerUrl().first()
            _state.update { if (it.serverUrl.isBlank()) it.copy(serverUrl = stored.origin) else it }
        }
    }

    fun setServerUrl(value: String) = _state.update {
        // Clearing the error on edit, not on submit: a message that stays while the user
        // fixes the thing it complains about reads as though the fix did not take.
        it.copy(serverUrl = value, urlError = null, banner = null)
    }

    fun setUsername(value: String) = _state.update {
        it.copy(username = value, usernameError = false, banner = null)
    }

    fun setPassword(value: Secret) = _state.update {
        it.copy(password = value, passwordError = null, banner = null)
    }

    fun togglePasswordVisible() = _state.update { it.copy(isPasswordVisible = !it.isPasswordVisible) }

    fun dismissBanner() = _state.update { it.copy(banner = null) }

    fun submit() {
        val current = _state.value
        if (current.isSubmitting) return

        _state.update {
            it.copy(
                isSubmitting = true,
                urlError = null,
                usernameError = false,
                passwordError = null,
                banner = null,
            )
        }

        viewModelScope.launch {
            when (val outcome = signIn(current.serverUrl, current.username, current.password)) {
                is Outcome.Success -> {
                    // The password is dropped here and nowhere later. A Secret that outlives
                    // the request it was needed for is the thing Task 18's rule is about.
                    _state.update { ChatSignInUiState(serverUrl = it.serverUrl) }
                    _events.send(ChatSignInEvent.SignedIn)
                }
                is Outcome.Failure -> _state.update { it.withFailure(outcome.error) }
            }
        }
    }

    private fun ChatSignInUiState.withFailure(error: ChatSignInError): ChatSignInUiState =
        when (error) {
            is ChatSignInError.InvalidInput -> copy(isSubmitting = false).withViolations(error.violations)
            is ChatSignInError.Rejected -> copy(isSubmitting = false).withAuthError(error.cause)
        }

    private fun ChatSignInUiState.withViolations(violations: List<ChatSignInViolation>) =
        copy(
            urlError = violations.filterIsInstance<ChatSignInViolation.MalformedUrl>()
                .firstOrNull()?.violation,
            usernameError = violations.any { it.field == ChatSignInField.USERNAME },
            passwordError = if (violations.any { it.field == ChatSignInField.PASSWORD }) {
                // Reuses the password slot for "you did not type one". The slot is "what is
                // wrong with this box", and an empty box is one of the things that can be.
                ChatAuthError.InvalidCredentials
            } else {
                null
            },
        )

    /**
     * Decides whether a platform failure belongs on the password field or in the banner.
     *
     * Only [ChatAuthError.InvalidCredentials] is the password's fault. Everything else —
     * a dead network, a 500, a build with no cipher key — is about the app or the server,
     * and putting it under the password box would tell somebody their correct credential
     * was wrong.
     */
    private fun ChatSignInUiState.withAuthError(error: ChatAuthError) = when (error) {
        ChatAuthError.InvalidCredentials -> copy(passwordError = error)
        else -> copy(banner = error)
    }
}
