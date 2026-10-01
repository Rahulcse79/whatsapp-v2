package com.whatsappv2.domain.usecase

import com.whatsappv2.core.common.result.Outcome
import com.whatsappv2.core.common.result.failure
import com.whatsappv2.core.common.secret.Secret
import com.whatsappv2.domain.chat.ChatAuthError
import com.whatsappv2.domain.chat.ChatCredentials
import com.whatsappv2.domain.chat.ChatSession
import com.whatsappv2.domain.chat.ChatUrlViolation
import com.whatsappv2.domain.chat.CoralServerUrl
import com.whatsappv2.domain.repository.ChatSessionRepository
import javax.inject.Inject

/** One of the three boxes on the sign-in screen. Errors are placed, not pooled in a toast. */
enum class ChatSignInField {
    SERVER_URL,
    USERNAME,
    PASSWORD,
}

/**
 * Something the user typed that cannot be sent.
 *
 * Every case names its [field], because the screen puts the message under the box that
 * caused it. An error the user has to guess the owner of is one they fix by changing the
 * wrong thing.
 */
sealed interface ChatSignInViolation {

    val field: ChatSignInField

    /** The box is empty. */
    data class Required(override val field: ChatSignInField) : ChatSignInViolation

    /** The URL is there but wrong. Carries [CoralServerUrl.parse]'s own verdict, suggestion and all. */
    data class MalformedUrl(val violation: ChatUrlViolation) : ChatSignInViolation {
        override val field: ChatSignInField get() = ChatSignInField.SERVER_URL
    }
}

/** Why signing in did not produce a session. */
sealed interface ChatSignInError {

    /**
     * The form was not sendable. Carries **every** problem, not the first.
     *
     * A form that reveals one fault per submission is how somebody submits five times.
     * The screen disables its button until all three parse, so this is the backstop
     * rather than the usual path — but a backstop that only half-reports is not one.
     */
    data class InvalidInput(val violations: List<ChatSignInViolation>) : ChatSignInError

    /** The request was made and the platform said no. Typed, so the screen can tell apart what it must. */
    data class Rejected(val cause: ChatAuthError) : ChatSignInError
}

/**
 * Signs in to the Coral platform: parse the URL, check the fields, make the call.
 *
 * ## Why this one earns its class and `ChatSignOutUseCase` does not
 *
 * §4.2 forbids pass-through use cases, and signing out is exactly one repository call
 * with no second collaborator to order — [ChatSessionRepository]'s own KDoc says so, and
 * [UseCaseRationale] says why that is the layering working rather than a shortcut.
 *
 * Signing in is not that shape. Three things have to happen in one order and no single
 * collaborator can see all three: the raw URL becomes a [CoralServerUrl] or the request
 * is never made; the username and password are checked for being there at all; and only
 * then does the repository get called, with a parsed origin rather than a string. Put
 * that in the ViewModel and the next caller — a deep link, a test, a second screen —
 * re-implements it, differently.
 *
 * ## It also provisions the extension the call button needs
 *
 * On success, [EnsureChatExtensionUseCase] makes sure a SIP account exists for this
 * identity. That is a second collaborator in a fixed order — the username is only known
 * once the credentials are accepted — which is precisely what a use case is for, and it
 * is why this one keeps earning its class.
 *
 * ## Nothing is persisted on a failure
 *
 * The URL reaches storage only through a successful [ChatSessionRepository.signIn]. A
 * typo that cannot sign in must not become the address every later attempt uses.
 */
class ChatSignInUseCase @Inject constructor(
    private val repository: ChatSessionRepository,
    private val ensureExtension: EnsureChatExtensionUseCase,
) {

    suspend operator fun invoke(
        rawUrl: String,
        username: String,
        password: Secret,
    ): Outcome<ChatSession, ChatSignInError> {
        val violations = mutableListOf<ChatSignInViolation>()

        val url = when (val parsed = CoralServerUrl.parse(rawUrl)) {
            is Outcome.Success -> parsed.value
            is Outcome.Failure -> {
                violations += ChatSignInViolation.MalformedUrl(parsed.error)
                null
            }
        }

        // Trimmed, because a username arrives pasted more often than typed and a trailing
        // space would be encrypted along with it and rejected as a wrong credential. The
        // password is NOT trimmed: a space inside one is a character the user chose.
        val trimmedUsername = username.trim()
        if (trimmedUsername.isEmpty()) violations += ChatSignInViolation.Required(ChatSignInField.USERNAME)
        if (password.isEmpty) violations += ChatSignInViolation.Required(ChatSignInField.PASSWORD)

        if (url == null || violations.isNotEmpty()) {
            return failure(ChatSignInError.InvalidInput(violations))
        }

        val credentials = ChatCredentials(username = trimmedUsername, password = password)
        return when (val result = repository.signIn(url, credentials)) {
            is Outcome.Success -> {
                // The chat thread offers a call button, and a button that cannot work is
                // worse than no button. Deliberately NOT propagated: chat is signed in
                // either way, and only the call button depends on this half.
                ensureExtension(url, trimmedUsername, password)
                result
            }
            is Outcome.Failure -> failure(ChatSignInError.Rejected(result.error))
        }
    }
}
