package com.whatsappv2.domain.chat

/**
 * Whether the chat socket is up.
 *
 * ## [NotConfigured] is required, not defensive
 *
 * `ChatSdk.get()` **throws** before `init` has been called, and "nobody has signed in yet"
 * is an ordinary, reachable state — it is what every fresh install is in. Without a name
 * for it the repository would have to either throw from a Flow or lie about being
 * disconnected, and the tab could not tell "the server is down" from "you have no account".
 */
sealed interface ChatConnectionState {

    /** No session, so the engine has never been started. The tab offers sign-in. */
    data object NotConfigured : ChatConnectionState

    /** Configured, socket not up yet. Also the state during the SDK's own backoff. */
    data object Connecting : ChatConnectionState

    data object Connected : ChatConnectionState

    /**
     * The socket closed. [code] and [reason] are the WebSocket's own.
     *
     * Distinct from [Connecting] because the SDK reconnects on its own with backoff, so
     * this is informational rather than an instruction to the user to do anything.
     */
    data class Disconnected(val code: Int, val reason: String?) : ChatConnectionState

    /** True when messages can actually move. */
    val isUsable: Boolean get() = this is Connected
}
