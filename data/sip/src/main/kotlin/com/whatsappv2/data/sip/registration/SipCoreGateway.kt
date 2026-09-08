package com.whatsappv2.data.sip.registration

import com.whatsappv2.data.sip.network.TransportRebinder
import kotlinx.coroutines.flow.Flow

/**
 * The seam between this module and the SIP stack.
 *
 * Everything above it - the engine, the state mapping, the tests - is written against
 * these types, none of which come from the SDK. That is what makes Task 27's done-when
 * ("callback to Flow mapping is unit-tested with a stubbed SDK seam") achievable at all:
 * PJSIP cannot run on the JVM, so without this seam the mapping could only be
 * exercised on a device, which in practice means not exercised.
 *
 * The interface is deliberately small. Anything that can be decided without the stack -
 * backoff, refresh timing, error classification - belongs above it, where it is testable.
 */
internal interface SipCoreGateway : TransportRebinder {

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
     * Removes an account, sending `Expires: 0` first, and forgets its credentials.
     *
     * Returns once the request has been handed to the stack; the acknowledgement arrives
     * as a [StackRegistrationState.CLEARED] event.
     *
     * Dropping the credentials is part of the contract, not an afterthought: Task 29
     * requires that after a logout no decrypted password remains reachable, and the stack
     * keeps the one it was given in its own auth store for as long as it is running.
     * Removing the account without removing that leaves the password in memory for the
     * life of the process.
     */
    fun removeAccount(accountKey: String)

    /** Re-sends REGISTER for an account already known to the stack. */
    fun refreshAccount(accountKey: String)

    /**
     * Publishes RFC 8599 push parameters, or clears them with `null` (ADR-004, Task 38).
     *
     * They travel on the `Contact` header of every subsequent REGISTER, which is the
     * whole mechanism: the server learns where to send a wake-up without a side channel,
     * and a server that ignores them is no worse off than before. Applying them
     * re-registers, because a parameter the registrar has not seen does nothing.
     */
    fun setPushParameters(parameters: StackPushParameters?)

    /** Releases the stack, every transport it holds, and every stored credential. */
    fun stop()
}

/**
 * Everything the stack needs to register one account, with no domain types.
 *
 * Flattened on purpose: the gateway should not have to understand `SipAccount`, and a
 * change to the domain entity should not ripple into the SDK boundary.
 */
/**
 * RFC 8599 `Contact` parameters, as the stack needs them.
 *
 * A separate type from the domain's `PushToken` for the usual reason this module keeps
 * its own: the domain type is a contract with the whole app, and this one exists to be
 * handed to an SDK. Carrying no credential is not incidental — the payload the server
 * sends back says only "wake up and re-register" (ADR-004, DoD 12).
 */
internal data class StackPushParameters(
    /** `pn-provider`, e.g. `fcm`. */
    val provider: String,

    /** `pn-param` — the FCM sender or project identifier. */
    val param: String,

    /** `pn-prid` — the device registration token. */
    val prid: String,
)

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

    /**
     * How strictly media encryption is required (Task 62, §7, DoD 13).
     *
     * Carried per account rather than set once on the core, because it is an account
     * setting (§5.1): one identity may be an internal PBX that mandates SRTP while another
     * is a carrier trunk that cannot do it at all.
     */
    val mediaEncryption: StackMediaEncryption = StackMediaEncryption.OPTIONAL,

    /**
     * The audio codecs this account offers, most preferred first (§5.1).
     *
     * RTP mime types — `opus`, `PCMU` — rather than the domain's enum, for the same reason
     * every other field here is flattened: the gateway is handed strings an SDK
     * understands and never learns what a `CodecPreferences` is.
     *
     * Order is the whole point. It is the order the SDP offer lists, and therefore what
     * decides which codec a peer that supports several of them picks.
     */
    val audioCodecs: List<String> = emptyList(),

    /**
     * The video codecs this account offers, most preferred first (§5.2).
     *
     * Empty means an audio-only account: every video payload type is disabled, so no video
     * is offered and an escalation the far end asks for has nothing to negotiate with.
     */
    val videoCodecs: List<String> = emptyList(),

    /**
     * A PEM bundle to trust **in addition to** the system store, or null for system only.
     *
     * Null is the default and the only value an ordinary deployment should use. A custom CA
     * is for an enterprise with its own PBX certificate authority, and it is additive: it
     * never disables validation, which is the distinction between a supported deployment
     * and the permissive `TrustManager` §7 forbids outright.
     */
    val customCaPath: String? = null,
)

/**
 * Media encryption, as this module asks the stack for it (Task 62).
 *
 * A separate enum from `:domain`'s `SrtpPolicy` for the same reason every other type at
 * this seam is separate: the gateway's contract must not change shape because a domain
 * enum gained a case, and the mapping between them is a decision worth being able to test.
 */
internal enum class StackMediaEncryption {
    /** Cleartext RTP. */
    NONE,

    /** Offer SRTP, accept a peer that cannot do it. */
    OPTIONAL,

    /** Require SRTP. The stack fails the call rather than downgrading. */
    MANDATORY,
}
