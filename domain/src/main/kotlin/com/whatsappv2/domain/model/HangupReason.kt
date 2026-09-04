package com.whatsappv2.domain.model

/**
 * Why a call ended (§5.2).
 *
 * Every terminal transition carries one, so the call log can distinguish "the user
 * hung up" from "the network dropped" without inspecting SIP response codes at the
 * UI layer. Task 44 maps each to exactly one user-facing message.
 */
enum class HangupReason {
    /** The local user ended an established call. */
    LOCAL_HANGUP,

    /** The remote party ended an established call. */
    REMOTE_HANGUP,

    /** The local user rejected an incoming call. */
    LOCAL_REJECTED,

    /** The remote party rejected the call (486 Busy Here). */
    BUSY,

    /** The remote party declined (603 Decline). */
    DECLINED,

    /** Nobody answered before the ring timeout (408 / 480). */
    NO_ANSWER,

    /** The local user cancelled before the call was answered. */
    CANCELLED,

    /** Signalling transport failed: connection lost, TLS failure, registrar gone. */
    NETWORK_FAILURE,

    /** Codecs or SRTP could not be negotiated. See [SrtpPolicy.MANDATORY]. */
    MEDIA_FAILURE,

    /** The server refused for a reason the client cannot act on. */
    SERVER_ERROR,
    ;

    /** True when the call never reached an established state. */
    val endedBeforeAnswer: Boolean
        get() = this in setOf(BUSY, DECLINED, NO_ANSWER, CANCELLED, LOCAL_REJECTED)
}
