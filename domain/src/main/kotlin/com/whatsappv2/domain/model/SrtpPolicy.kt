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

    /**
     * Offer SRTP, accept cleartext if the peer cannot do it.
     *
     * **Not the default, and know what it sends before choosing it.** PJSIP implements
     * "optional" as `a=crypto` lines on an `RTP/AVP` media line — the form RFC 3711 does
     * not define — and FreeSWITCH refuses exactly that: `488 Not Acceptable Here`,
     * `INCOMPATIBLE_DESTINATION`, with `a=crypto in RTP/AVP, refer to rfc3711` in its own
     * log. Measured on 2026-09-10 against two FreeSWITCH servers, 100% of outgoing calls.
     * It works against a server whose profile sets `NDLB-allow-crypto-in-avp`, or one
     * that is not FreeSWITCH. The RFC-correct alternative, a second `RTP/SAVP` media line
     * (`srtpOptionalDupOffer`), costs ~330 bytes of an INVITE that has 324 to spare on
     * the reference path (1148 of 1472), so it is not the default either.
     */
    OPTIONAL,

    /** Require SRTP. Fail the call rather than downgrade. */
    MANDATORY,
    ;

    /** True when a failure to negotiate SRTP must terminate the call. */
    val requiresEncryptedMedia: Boolean get() = this == MANDATORY

    /**
     * Whether a call whose media encryption is [mediaEncrypted] may continue (Task 62,
     * DoD 13).
     *
     * The whole of "Mandatory means the call fails rather than going cleartext", as a
     * function of two values. It reads as a triviality and is not: the enforcement that
     * matters is the one that happens **after** negotiation, when the answer has come back
     * and the media is about to flow. A policy checked only when the offer is built is a
     * policy a peer can talk its way past by answering with something else.
     *
     * [DISABLED] and [OPTIONAL] permit both, which is what they mean — `OPTIONAL` offers
     * encryption and accepts a peer that cannot do it, and a user who chose that has
     * chosen it.
     */
    fun permits(mediaEncrypted: Boolean): Boolean = !requiresEncryptedMedia || mediaEncrypted
}
