package com.whatsappv2.domain.chat

/**
 * Why a call to the Coral platform failed.
 *
 * Typed, and each case exists because the app must **do** something different about it —
 * a sealed set of error codes nobody branches on would be a message in disguise.
 */
sealed interface ChatAuthError {

    /** The server rejected the username or password. Goes on the password field. */
    data object InvalidCredentials : ChatAuthError

    /**
     * The token is gone or stale — a 401 on a call that is not sign-in.
     *
     * Distinct from [InvalidCredentials] because the response is different: refresh if the
     * platform offers it, otherwise return to sign-in. Telling a signed-in user their
     * password is wrong when their session merely aged out is how people change a password
     * that was never broken.
     */
    data object SessionExpired : ChatAuthError

    /** No response at all — no network, DNS failure, timeout. Retryable, and worth saying so. */
    data object Network : ChatAuthError

    /** The server answered, and it was not a success. [message] is the platform's own `{"error":…}` text. */
    data class Server(val httpStatus: Int, val message: String?) : ChatAuthError

    /**
     * The credential cipher could not run — the key is absent or malformed in this build.
     *
     * Its own case, and the reason is the one failure this list exists to prevent: a build
     * with no key would otherwise fail at sign-in and be reported to the user as a wrong
     * password. That sends them to change a password that was correct, and sends the bug
     * report to the wrong team. This one says "this build cannot sign in", which is true.
     */
    data object CryptoUnavailable : ChatAuthError

    /** No server URL has been saved yet. Reachable because the URL is asked at sign-in. */
    data object NotConfigured : ChatAuthError

    /** True when trying the same thing again could work. */
    val isRetryable: Boolean get() = this is Network || (this is Server && httpStatus >= SERVER_ERROR)
}

/** Below this a 4xx is the caller's fault and a retry changes nothing. */
private const val SERVER_ERROR = 500
