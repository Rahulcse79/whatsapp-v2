package com.whatsappv2.domain.model

import com.whatsappv2.core.common.logging.redact
import com.whatsappv2.core.common.secret.Secret

/**
 * A configured SIP account — every field in §5.1.
 *
 * **Registration status is deliberately absent.** §5.1 marks it "derived, read-only",
 * and it is: it belongs to the engine, changes many times a second during a retry
 * storm, and is not persisted. Storing it here would invite a stale copy in the
 * database that disagrees with reality — exactly the "shows Registered while the
 * transport is down" failure §6 forbids. It is observed from
 * `SipEngine.registrationState`, keyed by [id], and paired for the UI by
 * [SipAccountStatus].
 *
 * Optional fields are `null` when unset rather than blank strings, so "the user
 * cleared this" and "the user never filled it in" are the same state, and every
 * fallback is expressed once as a derived property instead of at each call site.
 */
data class SipAccount(
    /** Stable identity. Never derived from the SIP URI, which the user may edit. */
    val id: AccountId,

    /** User-facing name for this account in the account list. */
    val label: String,

    /** SIP user part, e.g. `alice` or `1001`. */
    val username: String,

    /** Optional dialable extension. May equal [username]. */
    val extension: String?,

    /** Authentication username. Falls back to [username] — see [effectiveAuthUsername]. */
    val authUsername: String?,

    /** Encrypted at rest (Task 16); never logged (§7, DoD 12). */
    val password: Secret,

    /** Display name for the `From` header. */
    val displayName: String?,

    /** SIP domain. Also the registrar unless [registrar] overrides it. */
    val domain: String,

    /** Registrar override. Null means "use [domain]" — see [effectiveRegistrar]. */
    val registrar: HostPort?,

    /** Outbound proxy. Loose routing (`;lr`) is expressed by the engine, not stored here. */
    val outboundProxy: HostPort?,

    /** Signalling port. Null means the transport's default — see [effectivePort]. */
    val port: Int?,

    /** Signalling transport. */
    val transport: Transport,

    /** Requested registration expiry. The server's granted value wins if lower (§5.1). */
    val registrationExpirySeconds: Int,

    /** Optional STUN server for NAT discovery. */
    val stunServer: HostPort?,

    /** Optional TURN relay, with its own credentials. */
    val turn: TurnConfiguration?,

    /** ICE / STUN toggles and the keepalive interval. */
    val natPolicy: NatPolicy,

    /** Media encryption policy. [SrtpPolicy.MANDATORY] fails rather than downgrades. */
    val srtpPolicy: SrtpPolicy,

    /** Ordered codec preferences; the SDP offer order. */
    val codecs: CodecPreferences,

    /** Whether outgoing calls use this account by default. Exactly one account is. */
    val isDefault: Boolean,
) {
    init {
        require(label.isNotBlank()) { "label must not be blank" }
        require(username.isNotBlank()) { "username must not be blank" }
        require(domain.isNotBlank()) { "domain must not be blank" }
        require(port == null || port in MIN_PORT..MAX_PORT) { "port out of range: $port" }
        require(registrationExpirySeconds in MIN_EXPIRY_SECONDS..MAX_EXPIRY_SECONDS) {
            "registration expiry out of range: $registrationExpirySeconds"
        }
    }

    /** [authUsername] when set, otherwise [username]. Blank counts as unset. */
    val effectiveAuthUsername: String
        get() = authUsername?.takeIf { it.isNotBlank() } ?: username

    /** [port] when set, otherwise the transport's default (5060, or 5061 for TLS). */
    val effectivePort: Int get() = port ?: transport.defaultPort

    /** [registrar] when set, otherwise the account [domain] on the effective port. */
    val effectiveRegistrar: String
        get() = registrar?.render() ?: "$domain:$effectivePort"

    /** The AOR this account registers: `sip:username@domain` (`sips:` under TLS). */
    val addressOfRecord: String
        get() = "${if (transport == Transport.TLS) SipScheme.SIPS.token else SipScheme.SIP.token}:$username@$domain"

    /** True when signalling **and** media are both required to be encrypted. */
    val isFullySecure: Boolean
        get() = transport.isSecure && srtpPolicy.requiresEncryptedMedia

    /**
     * Redacted. The password is a [Secret] and masks itself, but the username,
     * extension and display name are personal data too, and a data class `toString()`
     * would put all of them into any log line that interpolates an account (§7, DoD 12).
     */
    override fun toString(): String =
        "SipAccount(id=$id, label=$label, user=${redact(username)}@$domain, " +
            "transport=$transport, port=$effectivePort, srtp=$srtpPolicy, default=$isDefault)"

    companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65_535

        /** Below ~30s the client spends more battery re-registering than it saves. */
        const val MIN_EXPIRY_SECONDS = 30

        /** 24 hours. Longer and a stale binding outlives any plausible network change. */
        const val MAX_EXPIRY_SECONDS = 86_400

        const val DEFAULT_EXPIRY_SECONDS = 3_600
    }
}

/**
 * An account paired with its live registration state, for the account list (Task 31).
 *
 * Kept separate from [SipAccount] so the persisted entity never carries a status that
 * could go stale.
 */
data class SipAccountStatus(
    val account: SipAccount,
    val registrationState: RegistrationState,
)
