package com.whatsappv2.data.sip.registration

/**
 * Registration states, mirrored from the SIP stack into this module's own vocabulary.
 *
 * A deliberate copy of `org.linphone.core.RegistrationState`, and the copy is the point:
 * it lets every layer above the gateway - including the tests - be written without an SDK
 * type in scope, which is what keeps the "no `org.linphone` import outside `:data:sip`"
 * rule (DoD 3) from being merely aspirational.
 *
 * If the SDK adds a state, [LinphoneCoreGateway] must map it here. That is a compile
 * error in one file rather than a silent behaviour change everywhere.
 */
internal enum class StackRegistrationState {
    /** No registration attempted, or the account was removed. */
    NONE,

    /** A REGISTER is in flight. */
    PROGRESS,

    /** Registered. */
    OK,

    /** Cleanly unregistered - the `Expires: 0` was acknowledged. */
    CLEARED,

    /** The registrar rejected it, or the request never completed. */
    FAILED,

    /**
     * A refresh is in flight while the existing binding is still valid.
     *
     * Treated as still-registered rather than as in-progress: the account can place and
     * receive calls throughout, and reporting "Registering" would make a working account
     * flicker in the UI every refresh cycle.
     */
    REFRESHING,
}

/**
 * One registration state change, in this module's own types.
 *
 * [statusCode] is the SIP response the registrar sent, when there was one - 401 for a
 * rejected password, 408 for a timeout. It is the input to the [SipError] mapping, and it
 * is why a wrong password can be reported as "Authentication failed" rather than as a
 * generic failure (Task 31).
 */
internal data class StackRegistrationEvent(
    val accountKey: String,
    val state: StackRegistrationState,
    val statusCode: Int?,
    val message: String?,
)
