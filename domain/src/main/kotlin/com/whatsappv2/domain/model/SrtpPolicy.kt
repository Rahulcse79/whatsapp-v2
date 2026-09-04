package com.whatsappv2.domain.model

/**
 * How strictly media encryption is required (§7, DoD 13).
 *
 * [Mandatory] means a call that cannot negotiate SRTP **fails**. It must never
 * silently fall back to cleartext — that would turn a security setting into a
 * suggestion, which is worse than not offering it.
 */
enum class SrtpPolicy {
    /** Never offer SRTP. Media is cleartext RTP. */
    DISABLED,

    /** Offer SRTP, accept cleartext if the peer cannot do it. */
    OPTIONAL,

    /** Require SRTP. Fail the call rather than downgrade. */
    MANDATORY,
    ;

    /** True when a failure to negotiate SRTP must terminate the call. */
    val requiresEncryptedMedia: Boolean get() = this == MANDATORY
}
