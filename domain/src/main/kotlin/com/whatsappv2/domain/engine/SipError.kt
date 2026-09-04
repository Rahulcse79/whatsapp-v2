package com.whatsappv2.domain.engine

import com.whatsappv2.domain.model.HangupReason
import com.whatsappv2.domain.model.RegistrationFailure

/**
 * Everything that can go wrong in the SIP engine, expressed in domain terms.
 *
 * The point is that **no layer above `:data:sip` ever sees a SIP response code**. A
 * ViewModel deciding what to show should not be pattern-matching on 486 versus 603;
 * it should be matching on [Busy] versus [Declined]. Codes are recorded on the cases
 * that have them purely so a bug report can be traced back to the wire.
 *
 * Each case documents exactly which responses map to it (DoD: "Every `SipError` case
 * documents which SIP responses map to it"), and [fromResponseCode] is the single
 * place that mapping happens.
 */
sealed interface SipError {

    /** The SIP response code this error came from, when it came from one at all. */
    val responseCode: Int? get() = null

    // ---------------------------------------------------------------- authentication

    /**
     * Credentials were rejected.
     *
     * Maps: **401 Unauthorized**, **407 Proxy Authentication Required** after the
     * client has already retried with credentials. A first 401 is part of the normal
     * digest handshake and is not an error.
     */
    data class AuthenticationFailed(override val responseCode: Int) : SipError

    /**
     * The server understood the request and refuses it, and re-authenticating will not
     * help.
     *
     * Maps: **403 Forbidden**.
     */
    data object Forbidden : SipError {
        override val responseCode: Int get() = FORBIDDEN
    }

    // ---------------------------------------------------------------- addressing

    /**
     * The target does not exist on that server.
     *
     * Maps: **404 Not Found**, **410 Gone**, **604 Does Not Exist Anywhere**.
     */
    data class NotFound(override val responseCode: Int) : SipError

    /**
     * The request was syntactically rejected — a malformed URI, or an unsupported
     * extension. A bug on our side, not the user's.
     *
     * Maps: **400 Bad Request**, **413/414 too large**, **420 Bad Extension**.
     */
    data class BadRequest(override val responseCode: Int) : SipError

    // ---------------------------------------------------------------- callee state

    /**
     * The callee is on another call.
     *
     * Maps: **486 Busy Here**, **600 Busy Everywhere**.
     */
    data class Busy(override val responseCode: Int) : SipError

    /**
     * The callee actively refused.
     *
     * Distinct from [Busy]: "I don't want to talk to you" is a different message to
     * show than "the line is engaged".
     *
     * Maps: **603 Decline**.
     */
    data object Declined : SipError {
        override val responseCode: Int get() = DECLINE
    }

    /**
     * The callee is registered but cannot take the call right now — do-not-disturb, or
     * an endpoint that is registered but unreachable.
     *
     * Maps: **480 Temporarily Unavailable**.
     */
    data object TemporarilyUnavailable : SipError {
        override val responseCode: Int get() = TEMPORARILY_UNAVAILABLE
    }

    /**
     * Nobody answered before the timer expired.
     *
     * Maps: **408 Request Timeout**, and a locally expired transaction timer.
     */
    data object Timeout : SipError {
        override val responseCode: Int get() = REQUEST_TIMEOUT
    }

    /**
     * The request was cancelled before completion — usually by the local user hanging
     * up while it was still ringing.
     *
     * Maps: **487 Request Terminated**.
     */
    data object Cancelled : SipError {
        override val responseCode: Int get() = REQUEST_TERMINATED
    }

    // ---------------------------------------------------------------- server

    /**
     * The server is overloaded or in maintenance and told us when to come back.
     *
     * [retryAfterSeconds] carries the `Retry-After` header when present. It **must** be
     * honoured rather than replaced by our own backoff (§2.1): with 5,000 clients, a
     * server that asks for a delay and is ignored gets a second stampede immediately.
     *
     * Maps: **503 Service Unavailable**.
     */
    data class ServiceUnavailable(val retryAfterSeconds: Int?) : SipError {
        override val responseCode: Int get() = SERVICE_UNAVAILABLE
    }

    /**
     * The server failed in a way we cannot act on.
     *
     * Maps: **500 Server Internal Error**, **502 Bad Gateway**, **504 Server Time-out**,
     * and any other 5xx not covered above.
     */
    data class ServerError(override val responseCode: Int) : SipError

    // ---------------------------------------------------------------- media

    /**
     * No common codec, or SRTP could not be negotiated under
     * [com.whatsappv2.domain.model.SrtpPolicy.MANDATORY].
     *
     * A mandatory-SRTP call that reaches this **must fail** rather than retry in
     * cleartext (§7, DoD 13).
     *
     * Maps: **488 Not Acceptable Here**, **606 Not Acceptable**, **415 Unsupported
     * Media Type**, and a local SDP negotiation failure with no response code at all.
     */
    data class MediaNegotiationFailed(
        override val responseCode: Int?,
        val encryptionRequired: Boolean,
    ) : SipError

    // ---------------------------------------------------------------- transport

    /**
     * Signalling could not be delivered: socket closed, TLS handshake failed, or the
     * certificate was rejected.
     *
     * Raised locally: nothing came back from the wire, so [responseCode] is always
     * `null`.
     */
    data class TransportFailure(val detail: TransportFailureKind) : SipError

    /**
     * No usable network at all. Distinct from a server that is not answering.
     *
     * Raised locally: no SIP response maps to this.
     */
    data object NetworkUnavailable : SipError

    // ---------------------------------------------------------------- local

    /**
     * The operation named an account the engine does not know. A programming error.
     *
     * Raised locally: no SIP response maps to this.
     */
    data object UnknownAccount : SipError

    /**
     * The operation named a call that has already ended or never existed.
     *
     * Raised locally: no SIP response maps to this.
     */
    data object UnknownCall : SipError

    /**
     * The account must be registered first.
     *
     * Raised locally: no SIP response maps to this.
     */
    data object NotRegistered : SipError

    /**
     * The operation is not valid in the call's current state — resuming a call that is
     * not held, for instance. Surfaced rather than swallowed, because it means the UI
     * offered an action it should not have.
     *
     * Raised locally: no SIP response maps to this.
     */
    data class InvalidState(val detail: String) : SipError

    /**
     * The engine has not been started, or has been shut down.
     *
     * Raised locally: no SIP response maps to this.
     */
    data object EngineUnavailable : SipError

    /**
     * The stack reported something with no domain meaning. Carries whatever it said so
     * a bug report is actionable; never shown to the user verbatim.
     *
     * Maps: any response code outside the 4xx/5xx/6xx classes, and stack-level errors
     * with no response at all — in which case [responseCode] is `null`.
     */
    data class Unexpected(val detail: String, override val responseCode: Int? = null) : SipError

    companion object {
        // Response codes are named so the mapping below reads as intent, not arithmetic.
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val PROXY_AUTH_REQUIRED = 407
        const val REQUEST_TIMEOUT = 408
        const val GONE = 410
        const val UNSUPPORTED_MEDIA_TYPE = 415
        const val TEMPORARILY_UNAVAILABLE = 480
        const val BUSY_HERE = 486
        const val REQUEST_TERMINATED = 487
        const val NOT_ACCEPTABLE_HERE = 488
        const val SERVER_INTERNAL_ERROR = 500
        const val SERVICE_UNAVAILABLE = 503
        const val BUSY_EVERYWHERE = 600
        const val DECLINE = 603
        const val DOES_NOT_EXIST_ANYWHERE = 604
        const val NOT_ACCEPTABLE = 606

        /**
         * The single place a SIP response code becomes a domain error.
         *
         * Having exactly one mapping is what keeps 486 from meaning "busy" in one
         * screen and "unavailable" in another.
         *
         * @param code the final response code.
         * @param retryAfterSeconds the `Retry-After` header, when the server sent one.
         */
        @Suppress("CyclomaticComplexMethod")
        fun fromResponseCode(code: Int, retryAfterSeconds: Int? = null): SipError = when (code) {
            UNAUTHORIZED, PROXY_AUTH_REQUIRED -> AuthenticationFailed(code)
            FORBIDDEN -> Forbidden
            NOT_FOUND, GONE, DOES_NOT_EXIST_ANYWHERE -> NotFound(code)
            REQUEST_TIMEOUT -> Timeout
            TEMPORARILY_UNAVAILABLE -> TemporarilyUnavailable
            BUSY_HERE, BUSY_EVERYWHERE -> Busy(code)
            REQUEST_TERMINATED -> Cancelled
            DECLINE -> Declined
            NOT_ACCEPTABLE_HERE, NOT_ACCEPTABLE, UNSUPPORTED_MEDIA_TYPE ->
                MediaNegotiationFailed(code, encryptionRequired = false)
            SERVICE_UNAVAILABLE -> ServiceUnavailable(retryAfterSeconds)
            else -> fromResponseClass(code)
        }

        private fun fromResponseClass(code: Int): SipError = when (code / HUNDRED) {
            CLIENT_ERROR_CLASS -> BadRequest(code)
            SERVER_ERROR_CLASS -> ServerError(code)
            GLOBAL_ERROR_CLASS -> ServerError(code)
            else -> Unexpected("unmapped SIP response", code)
        }

        private const val HUNDRED = 100
        private const val CLIENT_ERROR_CLASS = 4
        private const val SERVER_ERROR_CLASS = 5
        private const val GLOBAL_ERROR_CLASS = 6
    }
}

/** What specifically failed at the transport layer. */
enum class TransportFailureKind {
    /** The socket could not be opened or was closed under us. */
    CONNECTION_LOST,

    /** The TLS handshake failed. */
    TLS_HANDSHAKE_FAILED,

    /** The peer's certificate was rejected. Never bypassed silently (§7). */
    CERTIFICATE_REJECTED,

    /** DNS could not resolve the registrar or proxy. */
    DNS_FAILURE,
}

/**
 * How a registration attempt failed, in the terms the account UI needs.
 *
 * This is the mapping Task 7 deferred: `RegistrationFailure` distinguishes what the
 * user must fix from what will retry itself, and this is where a wire-level error
 * becomes that distinction.
 */
fun SipError.toRegistrationFailure(): RegistrationFailure = when (this) {
    is SipError.AuthenticationFailed -> RegistrationFailure.AUTHENTICATION_FAILED
    is SipError.Forbidden, is SipError.NotFound -> RegistrationFailure.ACCOUNT_REJECTED
    is SipError.BadRequest, is SipError.InvalidState -> RegistrationFailure.INVALID_CONFIGURATION
    is SipError.NetworkUnavailable -> RegistrationFailure.NETWORK_UNAVAILABLE
    is SipError.Timeout -> RegistrationFailure.TIMEOUT
    is SipError.ServiceUnavailable, is SipError.ServerError -> RegistrationFailure.SERVER_UNAVAILABLE
    is SipError.TransportFailure -> RegistrationFailure.TRANSPORT_FAILURE
    else -> RegistrationFailure.SERVER_UNAVAILABLE
}

/**
 * Why a call ended, given the error that ended it.
 *
 * Task 44 turns each [HangupReason] into exactly one user-facing message; this is the
 * step before that, and having it here means a call log entry and an on-screen error
 * can never disagree.
 */
fun SipError.toHangupReason(): HangupReason = when (this) {
    is SipError.Busy -> HangupReason.BUSY
    is SipError.Declined -> HangupReason.DECLINED
    is SipError.Timeout, is SipError.TemporarilyUnavailable -> HangupReason.NO_ANSWER
    is SipError.Cancelled -> HangupReason.CANCELLED
    is SipError.MediaNegotiationFailed -> HangupReason.MEDIA_FAILURE
    is SipError.TransportFailure, is SipError.NetworkUnavailable -> HangupReason.NETWORK_FAILURE
    else -> HangupReason.SERVER_ERROR
}
