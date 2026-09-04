package com.whatsappv2.data.sip.registration

import kotlinx.coroutines.flow.Flow

/**
 * The seam between this module and the SIP stack.
 *
 * Everything above it - the engine, the state mapping, the tests - is written against
 * these types, none of which come from the SDK. That is what makes Task 27's done-when
 * ("callback to Flow mapping is unit-tested with a stubbed SDK seam") achievable at all:
 * liblinphone cannot run on the JVM, so without this seam the mapping could only be
 * exercised on a device, which in practice means not exercised.
 *
 * The interface is deliberately small. Anything that can be decided without the stack -
 * backoff, refresh timing, error classification - belongs above it, where it is testable.
 */
internal interface LinphoneCoreGateway {

    /** Registration state changes, as the stack reports them. */
    val registrationEvents: Flow<StackRegistrationEvent>

    /** Starts the stack. Idempotent: calling it twice must not create a second core. */
    fun start()

    /**
     * Adds or replaces an account and begins registering it.
     *
     * Replacing rather than adding a second is what enforces "one registration per
     * configured account" - two bindings for one identity would fight over the same
     * registrar record.
     */
    fun addAccount(account: StackAccount)

    /**
     * Removes an account, sending `Expires: 0` first.
     *
     * Returns once the request has been handed to the stack; the acknowledgement arrives
     * as a [StackRegistrationState.CLEARED] event.
     */
    fun removeAccount(accountKey: String)

    /** Re-sends REGISTER for an account already known to the stack. */
    fun refreshAccount(accountKey: String)

    /** Releases the stack and every transport it holds. */
    fun stop()
}

/**
 * Everything the stack needs to register one account, with no domain types.
 *
 * Flattened on purpose: the gateway should not have to understand `SipAccount`, and a
 * change to the domain entity should not ripple into the SDK boundary.
 */
internal data class StackAccount(
    val key: String,
    val username: String,
    val authUsername: String,
    val password: String,
    val domain: String,
    val registrarUri: String,
    val proxyUri: String?,
    val transport: String,
    val expirySeconds: Int,
    val registerEnabled: Boolean = true,
)
