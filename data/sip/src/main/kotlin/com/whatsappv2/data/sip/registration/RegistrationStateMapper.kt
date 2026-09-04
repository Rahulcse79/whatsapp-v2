package com.whatsappv2.data.sip.registration

import com.whatsappv2.domain.engine.SipError
import com.whatsappv2.domain.engine.toRegistrationFailure
import com.whatsappv2.domain.model.RegistrationState

/**
 * Turns a stack event into the domain's registration state.
 *
 * Pure, and the only place the translation happens. Two screens deriving "is this
 * registered?" independently is how one of them ends up showing a working account while
 * the other shows a failure.
 *
 * The subtleties are all here rather than spread through the engine:
 *
 * - **Refreshing counts as registered.** The binding is still valid throughout a refresh
 *   and the account can place and receive calls, so reporting "Registering" would make a
 *   perfectly healthy account flicker every cycle.
 * - **A 401 or 407 is only a failure once it is final.** The first challenge is the normal
 *   digest handshake; the stack retries with credentials and only reports FAILED when that
 *   is rejected too. By the time an event reaches here, the state has already settled.
 * - **`Cleared` is a success, not a failure.** It is the acknowledgement of `Expires: 0`
 *   after a deliberate logout, and reporting it as failed would make every logout look
 *   like an error.
 */
internal object RegistrationStateMapper {

    /**
     * The domain state for [event].
     *
     * @param requestedExpirySeconds what the client asked for. The server's granted value
     *   is not exposed by the stack event, so this is the best available figure; §5.1's
     *   "server value wins if lower" is enforced by
     *   [com.whatsappv2.domain.registration.ExpiryRefreshPolicy] when the refresh is
     *   scheduled.
     * @param retryScheduled whether a retry has been queued, so the UI can distinguish
     *   "trying again shortly" from "this needs you".
     */
    fun toDomain(
        event: StackRegistrationEvent,
        requestedExpirySeconds: Int,
        retryScheduled: Boolean,
    ): RegistrationState = when (event.state) {
        StackRegistrationState.NONE, StackRegistrationState.CLEARED ->
            RegistrationState.Unregistered

        StackRegistrationState.PROGRESS -> RegistrationState.Registering

        // A refresh keeps the existing binding valid, so the account stays usable.
        StackRegistrationState.OK, StackRegistrationState.REFRESHING ->
            RegistrationState.Registered(requestedExpirySeconds)

        StackRegistrationState.FAILED -> RegistrationState.Failed(
            reason = toSipError(event).toRegistrationFailure(),
            retryScheduled = retryScheduled,
        )
    }

    /**
     * The error behind a failure.
     *
     * A status code is mapped through the single taxonomy in [SipError]; without one -
     * the request never reached a server - it is a transport failure rather than a
     * server rejection, which is the difference between "check your password" and
     * "check your network".
     */
    fun toSipError(event: StackRegistrationEvent): SipError = when {
        event.statusCode != null && event.statusCode > 0 ->
            SipError.fromResponseCode(event.statusCode)

        else -> SipError.TransportFailure(
            com.whatsappv2.domain.engine.TransportFailureKind.CONNECTION_LOST,
        )
    }

    /** True when the state means the account can currently place and receive calls. */
    fun isUsable(state: StackRegistrationState): Boolean =
        state == StackRegistrationState.OK || state == StackRegistrationState.REFRESHING
}
