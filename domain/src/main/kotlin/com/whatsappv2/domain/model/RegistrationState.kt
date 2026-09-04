package com.whatsappv2.domain.model

/**
 * Registration status of one account (§5.1).
 *
 * The UI must never show "Registered" while the transport is down (§6), so this is a
 * sealed hierarchy rather than a boolean plus a nullable error.
 */
sealed interface RegistrationState {

    /** No registration attempted, or deliberately unregistered after logout. */
    data object Unregistered : RegistrationState

    /** A REGISTER is in flight. */
    data object Registering : RegistrationState

    /**
     * Registered, with the expiry the **server** granted — which may be lower than the
     * value requested. Refresh timing is derived from this, not from the request.
     *
     * @param grantedExpirySeconds expiry as granted by the registrar.
     */
    data class Registered(val grantedExpirySeconds: Int) : RegistrationState {
        init {
            require(grantedExpirySeconds > 0) {
                "A granted expiry must be positive, was $grantedExpirySeconds"
            }
        }
    }

    /**
     * Registration failed. [retryScheduled] distinguishes "we will try again" from
     * "this needs the user to act", which are different messages in the UI.
     */
    data class Failed(
        val reason: RegistrationFailure,
        val retryScheduled: Boolean,
    ) : RegistrationState

    /** True only when the account can currently place and receive calls. */
    val isUsable: Boolean get() = this is Registered
}

/**
 * Why registration failed, at the granularity the user experience needs.
 *
 * Task 10's `SipError` maps onto this: a bad password must read "Authentication
 * failed", not a generic error, and no network is a distinct state from a rejection.
 */
enum class RegistrationFailure {
    /** 401/407 — wrong username, auth username or password. The user must fix it. */
    AUTHENTICATION_FAILED,

    /** 404/403 — the registrar does not accept this identity. */
    ACCOUNT_REJECTED,

    /** No usable network. Not a failure of the configuration. */
    NETWORK_UNAVAILABLE,

    /** Registrar unreachable or not responding (408). */
    TIMEOUT,

    /** 503 or similar — the server asked us to back off. */
    SERVER_UNAVAILABLE,

    /** TLS handshake or socket failure. */
    TRANSPORT_FAILURE,

    /** The configuration is invalid; registration was never attempted. */
    INVALID_CONFIGURATION,
    ;

    /** True when only the user can resolve it, so retrying is pointless. */
    val requiresUserAction: Boolean
        get() = this in setOf(AUTHENTICATION_FAILED, ACCOUNT_REJECTED, INVALID_CONFIGURATION)
}
