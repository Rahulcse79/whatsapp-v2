package com.whatsappv2.feature.chat.signin

import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatUrlViolation
import com.whatsappv2.domain.usecase.ChatSignInField

/**
 * What the sign-in screen is showing.
 *
 * ## Errors are placed, not pooled
 *
 * Three separate error slots rather than one message, because the screen puts each fault
 * under the box that caused it: a bad URL under the URL field, a rejected credential under
 * the password, and an unreachable host in a banner with a retry. A single generic message
 * makes the user guess which of three things to change, and they will usually guess wrong.
 *
 * ## The URL is prefilled, and that is decision D2 working
 *
 * [serverUrl] starts from the stored origin — the shipped default on a fresh install, the
 * last one that worked after a sign-out. So the second sign-in is two fields of *typing*
 * even though three are on screen, which is what D2 asks for and what keeps the address
 * visible and correctable rather than hidden in a preference.
 */
data class ChatSignInUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: Secret = Secret.EMPTY,

    /** A reveal toggle, because a typed password nobody can check is a password typed twice. */
    val isPasswordVisible: Boolean = false,

    /** One request at a time. While true the fields lock and the button shows progress. */
    val isSubmitting: Boolean = false,

    val urlError: ChatUrlViolation? = null,
    val usernameError: Boolean = false,
    val passwordError: ChatAuthError? = null,

    /** A failure that belongs to no field — no network, a 500, a build with no key. */
    val banner: ChatAuthError? = null,
) {

    /**
     * Whether the button is enabled.
     *
     * Locally decided, and deliberately *only* on emptiness: the URL's real validation
     * lives in [com.whatsappv2.domain.usecase.ChatSignInUseCase] and runs on submit. A
     * screen that re-implemented `CoralServerUrl.parse` would be a second copy of the rules
     * that drifts from the first, and the one a user meets would be the copy.
     */
    val canSubmit: Boolean
        get() = !isSubmitting && serverUrl.isNotBlank() && username.isNotBlank() && !password.isEmpty

    /** Places a violation on its field. Used by the ViewModel, exposed so a test can read it. */
    fun errorFor(field: ChatSignInField): Boolean = when (field) {
        ChatSignInField.SERVER_URL -> urlError != null
        ChatSignInField.USERNAME -> usernameError
        ChatSignInField.PASSWORD -> passwordError != null
    }
}
