package com.whatsappv2.domain.chat

/**
 * Why a chat operation failed.
 *
 * Typed rather than a message, for the reason `AccountRepositoryError` gives: the UI owns
 * the wording, and these drive different screens. [Unauthorized] sends somebody to sign in;
 * [Network] asks them to try again. A string could not be branched on without parsing it.
 *
 * Deliberately **not** [ChatAuthError]. That one belongs to the Coral UC platform at
 * `/services/`; this one to chat-node at `/chat/`. Two services, two error models
 * (`docs/chat-auth-and-contacts-plan.md` §1.2) — and a failure from one does not look like
 * a failure from the other.
 */
sealed interface ChatFailure {

    /** No response at all. `ChatError.httpStatus == 0` means the request never landed. */
    data object Network : ChatFailure

    /** The chat server refused the identity. Drives a return to sign-in. */
    data object Unauthorized : ChatFailure

    /** The server answered, and it was not a success. */
    data class Server(val httpStatus: Int, val message: String?) : ChatFailure

    /** Nobody is signed in, so the engine has never been started. */
    data object NotConfigured : ChatFailure

    /** Something the SDK reported that maps to none of the above. */
    data class Unknown(val message: String?) : ChatFailure

    /** True when trying the same thing again could work. */
    val isRetryable: Boolean
        get() = this is Network || (this is Server && httpStatus >= SERVER_ERROR)
}

/** Below this a 4xx is the caller's fault and a retry changes nothing. */
private const val SERVER_ERROR = 500
