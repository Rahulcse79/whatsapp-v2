package com.whatsappv2.domain.model

/**
 * NAT traversal and keepalive settings for one account (§5.1).
 *
 * [keepaliveIntervalSeconds] is the client's half of the anti-stampede story in §2.1:
 * it is configurable per account rather than a hardcoded ping, so 5,000 clients are not
 * all forced onto the same interval.
 */
data class NatPolicy(
    val iceEnabled: Boolean,
    val stunEnabled: Boolean,
    val keepaliveIntervalSeconds: Int,
) {
    init {
        require(keepaliveIntervalSeconds in MIN_KEEPALIVE_SECONDS..MAX_KEEPALIVE_SECONDS) {
            "Keepalive must be between $MIN_KEEPALIVE_SECONDS and $MAX_KEEPALIVE_SECONDS seconds, " +
                "was $keepaliveIntervalSeconds"
        }
    }

    companion object {
        const val MIN_KEEPALIVE_SECONDS = 10
        const val MAX_KEEPALIVE_SECONDS = 600
        const val DEFAULT_KEEPALIVE_SECONDS = 30

        /**
         * ICE **off**, STUN on, with a NAT-binding-friendly interval.
         *
         * ## Why ICE is off by default
         *
         * Two reasons, and the second one is the reason it changed.
         *
         * **It costs 198 bytes of every offer** — `a=ice-ufrag`, `a=ice-pwd` and two host
         * `a=candidate` lines, measured off this app's own INVITE on 2026-09-10. The
         * reference path carries **1472 bytes** and drops IP fragments silently, and the
         * server offers no TCP to escalate to. Narrowing the SRTP offer to the two
         * `AES_CM_128` suites saves 216 bytes and leaves the INVITE at 1526 — still 54
         * bytes over, still fragmented, still unanswered. **ICE off is what closes the
         * gap**, at 1328 bytes. The arithmetic and its measurements are
         * `domain/…/sdp/SdpBudget.kt`; `SdpBudgetTest` pins both rows.
         *
         * **And it buys nothing here.** ICE needs a reflexive or relayed candidate to be
         * worth its bytes, and this client never gathers one: `SipAccount.stunServer` is
         * collected, validated and persisted, and **nothing below the domain reads it** —
         * no STUN server is ever handed to the stack's `UaConfig`, so `PJSUA_STUN_USE_DEFAULT`
         * has nowhere to ask. What is left is host candidates, which are the addresses the
         * offer already carries. On a flat LAN they are redundant; behind NAT they are
         * unusable by the far end, and the server's symmetric-RTP latching is what makes
         * media flow either way.
         *
         * It stays a per-account switch (`Enable ICE` in the account editor), so a
         * deployment with real ICE infrastructure — and the STUN plumbing to feed it —
         * turns it back on for that account alone. Turning it on without that plumbing
         * only makes the offer bigger.
         */
        val DEFAULT: NatPolicy = NatPolicy(
            iceEnabled = false,
            stunEnabled = true,
            keepaliveIntervalSeconds = DEFAULT_KEEPALIVE_SECONDS,
        )
    }
}
